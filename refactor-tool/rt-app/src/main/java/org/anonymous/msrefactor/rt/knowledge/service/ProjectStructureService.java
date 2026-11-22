package org.anonymous.msrefactor.rt.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionRepository;
import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.CompositionForest;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InterServiceDependency;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ProjectStructure;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.collector.StaticAnalysisCollector;

import java.util.stream.Collectors;
import java.util.Objects;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rearranges one version's static analysis around its aggregates.
 *
 * <p>The collector reports per service, because that is the unit M1 and M2 are defined over. An
 * architect reasons in aggregates: what is inside this one, what rules it enforces, what it depends
 * on. Those are the same facts under a different grouping, and this is where the regrouping happens —
 * once, on the server, so the phases and the frontend all read the same arrangement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStructureService {

    /** The static-analysis name for the working tree, as opposed to a committed revision. */
    private static final String WORKING_TREE = "current";

    /** The label the baseline version carries; it has no commit of its own to point at. */
    private static final String BASELINE_LABEL = "baseline";

    private final CodeVersionRepository codeVersionRepository;
    private final StaticAnalysisCollector collector;

    public Optional<ProjectStructure> forVersion(String codeVersionId) {
        Optional<CodeVersionEntity> found = codeVersionRepository.findById(codeVersionId);
        if (found.isEmpty()) return Optional.empty();
        CodeVersionEntity version = found.get();

        Resolved resolved = resolveSource(version);
        log.info("[structure] codeVersionId={} label={} resolved={}",
                codeVersionId, version.getVersionLabel(), resolved.description());

        ProjectStructure structure = build(resolved.result(), version, resolved.description());
        return Optional.of(structure);
    }

    // ─── Which source tree this version is ──────────────────────────────────────

    public record Resolved(VersionAnalysisResult result, String description) {}

    /**
     * Branch tip first, then the recorded commit sha, then a tag of the same name, then the
     * working tree.
     *
     * <p>The branch is where the version lives on: a fix committed to it before a manual redeploy
     * belongs to the version, while the recorded sha is frozen at creation. The sha remains the
     * fallback for versions whose branch was deleted, and the {@code v1}/{@code v2} tags the
     * collector looks for are created outside this project and may simply not exist. The working
     * tree is the last resort, for a baseline without a branch of its own.
     *
     * <p>Public so the Monitor pipeline measures a version from the same source tree this view
     * shows — the two used to diverge whenever the shared working tree sat on another branch.
     */
    public Resolved resolveSource(CodeVersionEntity version) {
        String label = version.getVersionLabel();
        String sha = version.getGitCommitSha();
        String branch = version.getBranchName();

        if (branch != null && !branch.isBlank() && collector.hasRef(branch)) {
            VersionAnalysisResult byBranch = collector.collectCommit(branch, label);
            if (hasServices(byBranch)) {
                return new Resolved(byBranch, "branch " + branch);
            }
            log.debug("[structure] branch {} yielded no services; falling back", branch);
        }

        if (sha != null && !sha.isBlank()) {
            VersionAnalysisResult byCommit = collector.collectCommit(sha, label);
            if (hasServices(byCommit)) {
                return new Resolved(byCommit, "commit " + shortSha(sha));
            }
            log.debug("[structure] commit {} yielded no services; falling back", shortSha(sha));
        }

        if (label != null && !BASELINE_LABEL.equals(label) && collector.hasTag(label)) {
            VersionAnalysisResult byTag = collector.collectVersion(label);
            if (hasServices(byTag)) return new Resolved(byTag, "tag " + label);
        }

        return new Resolved(collector.collectVersion(WORKING_TREE), "working tree");
    }

    private boolean hasServices(VersionAnalysisResult r) {
        return r != null && r.getMetrics() != null
                && r.getMetrics().getServices() != null
                && !r.getMetrics().getServices().isEmpty();
    }

    private String shortSha(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    // ─── Service metrics → aggregate structures ─────────────────────────────────

    private ProjectStructure build(VersionAnalysisResult result, CodeVersionEntity version,
                                   String resolvedFrom) {
        Map<String, ServiceStaticMetrics> services =
                result.getMetrics() == null || result.getMetrics().getServices() == null
                        ? Map.of() : result.getMetrics().getServices();

        // Which aggregate to name at the far end of an edge: a service's own root. Built first
        // because an edge from tournament to user has to say "User", not "user".
        Map<String, String> rootByService = new LinkedHashMap<>();
        services.forEach((name, sm) -> {
            String root = CompositionForest.resolveRoot(sm.getCompositions(), sm.getRootNames());
            if (root != null) rootByService.put(name, root);
        });

        // The receiving half of every seam, built once: a sender addressing a service with no case
        // for its command is a break nothing else reports.
        Set<String> handled = handledIndex(services);

        Map<String, List<String>> orphans = new LinkedHashMap<>();
        List<ProjectStructure.AggregateStructure> aggregates = new ArrayList<>();

        for (Map.Entry<String, ServiceStaticMetrics> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            ServiceStaticMetrics sm = entry.getValue();

            List<String> roots = aggregateRoots(sm);
            if (roots.isEmpty()) {
                // No root and no composition data: every entity is unplaced. Reporting them beats
                // emitting an aggregate that owns nothing.
                if (sm.getEntityNames() != null && !sm.getEntityNames().isEmpty()) {
                    orphans.put(serviceName, new ArrayList<>(sm.getEntityNames()));
                }
                continue;
            }

            Map<String, String> replicaTargets = replicaTargets(sm);
            List<ProjectStructure.AggregateRelation> outgoing = outgoingRelations(sm, rootByService);
            List<ProjectStructure.AggregateRelation> incoming =
                    incomingRelations(serviceName, services, rootByService);

            Set<String> placed = new LinkedHashSet<>();
            for (String root : roots) {
                List<String> members = CompositionForest.subtree(root, sm.getCompositions());
                placed.addAll(members);
                aggregates.add(ProjectStructure.AggregateStructure.builder()
                        .name(root)
                        .serviceName(serviceName)
                        .entityCount(members.size())
                        .root(toEntityNode(CompositionForest.tree(root, sm.getCompositions()), replicaTargets))
                        .intraInvariants(invariantsOf(sm, root, roots))
                        .interInvariants(interInvariantsOf(sm, root, roots))
                        .outgoing(outgoing)
                        .incoming(incoming)
                        .functionalities(functionalitiesOf(serviceName, result, rootByService, handled))
                        .publishes(publishedBy(serviceName, services, rootByService))
                        .consumes(consumedBy(sm, services, rootByService))
                        .commands(commandsOf(sm, result, rootByService))
                        .relationsAreServiceWide(roots.size() > 1)
                        .build());
            }

            List<String> unplaced = (sm.getEntityNames() == null ? List.<String>of() : sm.getEntityNames())
                    .stream().filter(e -> !placed.contains(e)).toList();
            if (!unplaced.isEmpty()) orphans.put(serviceName, unplaced);
        }

        aggregates.sort(Comparator.comparing(ProjectStructure.AggregateStructure::getServiceName));

        return ProjectStructure.builder()
                .versionId(version.getVersionId())
                .versionLabel(version.getVersionLabel())
                .resolvedFrom(resolvedFrom)
                .collectedAt(Instant.now())
                .aggregates(aggregates)
                .orphanEntities(orphans.isEmpty() ? null : orphans)
                .build();
    }

    /**
     * The aggregate roots of a service.
     *
     * <p>{@code rootNames} is the direct answer — the classes that extend {@code Aggregate}. When a
     * snapshot predates that field, the composition forest still names one: the parent nothing else
     * contains.
     */
    private List<String> aggregateRoots(ServiceStaticMetrics sm) {
        if (sm.getRootNames() != null && !sm.getRootNames().isEmpty()) {
            return sm.getRootNames();
        }
        String derived = CompositionForest.resolveRoot(sm.getCompositions(), List.of());
        return derived == null ? List.of() : List.of(derived);
    }

    /**
     * Entity class → the aggregate root(s) it declares a copy of, by inverting
     * {@code replicaEntities}.
     *
     * <p>Not {@code crossServiceEntities}: that records every foreign service a class <em>mentions</em>,
     * and inverting it with {@code putIfAbsent} let map order decide the label —
     * {@code CourseExecutionStudent}, a copy of {@code User}, was shown as a replica of
     * {@code tournament} because its {@code equals} names {@code TournamentParticipant}. A class that
     * copies two roots now shows both instead of an arbitrary one.
     */
    private Map<String, String> replicaTargets(ServiceStaticMetrics sm) {
        Map<String, List<String>> byEntity = new LinkedHashMap<>();
        if (sm.getReplicaEntities() == null) return Map.of();
        sm.getReplicaEntities().forEach((root, entities) -> {
            if (entities == null) return;
            entities.forEach(e -> byEntity.computeIfAbsent(e, k -> new ArrayList<>()).add(root));
        });
        Map<String, String> out = new LinkedHashMap<>();
        byEntity.forEach((entity, roots) -> out.put(entity, String.join(", ", roots)));
        return out;
    }

    private ProjectStructure.EntityNode toEntityNode(CompositionForest.Node node,
                                                     Map<String, String> replicaTargets) {
        List<ProjectStructure.EntityNode> children = node.children().stream()
                .map(c -> toEntityNode(c, replicaTargets))
                .toList();
        return ProjectStructure.EntityNode.builder()
                .name(node.name())
                .replicaOf(replicaTargets.get(node.name()))
                .children(children.isEmpty() ? null : children)
                .build();
    }

    /**
     * The rules that belong to one aggregate.
     *
     * <p>{@code declaredIn} says which class holds each rule, so with several roots in a service each
     * gets its own. With a single root — every service in the reference application — the whole list
     * belongs to it, and filtering by {@code declaredIn} would only risk dropping a rule whose
     * declaring class the collector recorded differently.
     */
    private List<InvariantInfo> invariantsOf(ServiceStaticMetrics sm, String root, List<String> roots) {
        List<InvariantInfo> all = sm.getInvariants();
        if (all == null || all.isEmpty()) return List.of();
        if (roots.size() == 1) return all;
        return all.stream().filter(i -> root.equals(i.getDeclaredIn())).toList();
    }

    /** The cross-aggregate rules that belong to one aggregate; same {@code declaredIn} split. */
    private List<InterInvariantInfo> interInvariantsOf(ServiceStaticMetrics sm, String root,
                                                       List<String> roots) {
        List<InterInvariantInfo> all = sm.getInterInvariants();
        if (all == null || all.isEmpty()) return List.of();
        if (roots.size() == 1) return all;
        return all.stream().filter(i -> root.equals(i.getDeclaredIn())).toList();
    }

    // ─── Functionalities and event flow ─────────────────────────────────────────

    /**
     * The Functionality classes a service owns, already collected by the static passes.
     *
     * <p>Nothing is re-scanned here: {@code StaticMetrics.functionalities} is a flat list keyed by
     * {@code ownerService}, and the aggregate view only needs it grouped.
     */
    private List<ProjectStructure.FunctionalityRef> functionalitiesOf(
            String serviceName, VersionAnalysisResult result, Map<String, String> rootByService,
            Set<String> handled) {

        List<FunctionalityInfo> all = result.getMetrics() == null
                ? null : result.getMetrics().getFunctionalities();
        if (all == null) return null;

        List<ProjectStructure.FunctionalityRef> owned = all.stream()
                .filter(f -> serviceName.equals(f.getOwnerService()))
                .map(f -> ProjectStructure.FunctionalityRef.builder()
                        .operation(operationName(f.getName()))
                        .className(f.getName())
                        .protocol(f.getProtocol())
                        .stepCount(f.getStepCount())
                        .touchedAggregates(aggregateNames(f.getTouchedServices(), serviceName, rootByService))
                        .httpMethod(f.getHttpMethod())
                        .httpEndpoint(f.getHttpEndpoint())
                        .sendsCommands(sentCommands(f, rootByService, handled))
                        .steps(stepsOf(f, rootByService, handled))
                        .looseCommands(refs(f.getLooseCommands(), f.getName(), rootByService, handled))
                        .build())
                .sorted(Comparator.comparing(ProjectStructure.FunctionalityRef::getOperation))
                .toList();
        return owned.isEmpty() ? null : owned;
    }

    /** {@code AddParticipantFunctionalitySagas} → {@code AddParticipant}, the name the docs use. */
    private String operationName(String className) {
        if (className == null) return null;
        return className.replaceFirst("Functionality(Sagas)?$", "");
    }

    /** Service names to aggregate roots, dropping this service's own and anything unresolved. */
    private List<String> aggregateNames(List<String> serviceNames, String own,
                                        Map<String, String> rootByService) {
        if (serviceNames == null) return null;
        List<String> names = serviceNames.stream()
                .filter(s -> !s.equals(own))
                .map(rootByService::get)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        return names.isEmpty() ? null : names;
    }

    /**
     * The events this service registers, each with the aggregates that subscribe to it.
     *
     * <p>The consumer side is read back out of every other service's {@code eventSubscriptions},
     * which is where Pass 3 recorded it.
     */
    private List<ProjectStructure.EventFlow> publishedBy(
            String serviceName, Map<String, ServiceStaticMetrics> services,
            Map<String, String> rootByService) {

        ServiceStaticMetrics sm = services.get(serviceName);
        if (sm == null || sm.getPublishedEvents() == null || sm.getPublishedEvents().isEmpty()) {
            return null;
        }
        List<ProjectStructure.EventFlow> out = new ArrayList<>();
        sm.getPublishedEvents().forEach((eventType, where) -> {
            List<String> consumers = new ArrayList<>();
            services.forEach((consumer, csm) -> {
                if (consumer.equals(serviceName) || csm.getEventSubscriptions() == null) return;
                boolean subscribes = csm.getEventSubscriptions().values().stream()
                        .anyMatch(types -> types != null && types.contains(eventType));
                if (subscribes) consumers.add(rootByService.getOrDefault(consumer, consumer));
            });
            consumers.sort(Comparator.naturalOrder());
            out.add(ProjectStructure.EventFlow.builder()
                    .eventType(eventType)
                    .publishedIn(where)
                    .consumerAggregates(consumers.isEmpty() ? null : consumers)
                    .build());
        });
        out.sort(Comparator.comparing(ProjectStructure.EventFlow::getEventType));
        return out;
    }

    /**
     * The events this service subscribes to, each attributed to the service that registers it.
     *
     * <p>The publisher comes from the {@code registerEvent} scan, not from the anchor recorded on the
     * subscription. When no service registers the event the field stays null: the anchor would supply
     * an answer, and it is exactly the answer this is meant to replace.
     */
    private List<ProjectStructure.EventFlow> consumedBy(
            ServiceStaticMetrics sm, Map<String, ServiceStaticMetrics> services,
            Map<String, String> rootByService) {

        if (sm.getEventSubscriptions() == null || sm.getEventSubscriptions().isEmpty()) return null;

        Set<String> consumed = sm.getEventSubscriptions().values().stream()
                .filter(Objects::nonNull).flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ProjectStructure.EventFlow> out = consumed.stream().sorted().map(eventType -> {
            Map.Entry<String, ServiceStaticMetrics> publisher = services.entrySet().stream()
                    .filter(e -> e.getValue().getPublishedEvents() != null
                            && e.getValue().getPublishedEvents().containsKey(eventType))
                    .findFirst().orElse(null);
            if (publisher == null) {
                log.debug("[events] {} subscribes to {} but no service registers it",
                        sm.getServiceName(), eventType);
            }
            return ProjectStructure.EventFlow.builder()
                    .eventType(eventType)
                    .publisherAggregate(publisher == null
                            ? null : rootByService.getOrDefault(publisher.getKey(), publisher.getKey()))
                    .publishedIn(publisher == null
                            ? null : publisher.getValue().getPublishedEvents().get(eventType))
                    .build();
        }).toList();
        return out.isEmpty() ? null : out;
    }

    /**
     * The commands this aggregate accepts, each joined to the Functionalities that send it.
     *
     * <p>The two halves come from opposite ends of the source — the handler's {@code case} and every
     * saga's construction site — and only together do they describe the seam. A command whose
     * {@code sentBy} comes back empty is a surface nothing calls; {@code Course} has none of those,
     * which is the whole reason its empty controller is not a sign of dead code.
     */
    private List<CommandInfo> commandsOf(ServiceStaticMetrics sm, VersionAnalysisResult result,
                                         Map<String, String> rootByService) {
        List<CommandInfo> handled = sm.getHandledCommands();
        if (handled == null || handled.isEmpty()) return null;

        List<FunctionalityInfo> all = result.getMetrics() == null
                ? List.of() : Optional.ofNullable(result.getMetrics().getFunctionalities()).orElse(List.of());

        return handled.stream().map(cmd -> {
            List<CommandInfo.CommandCaller> callers = all.stream()
                    .filter(f -> f.getCommands() != null)
                    .flatMap(f -> f.getCommands().stream()
                            // Name alone is ambiguous: RemoveCourseExecutionCommand exists in
                            // execution, quiz and tournament, and UpdateTopicCommand in three more.
                            .filter(c -> c.getName().equals(cmd.getName())
                                    && sm.getServiceName().equals(c.getTargetService()))
                            .map(c -> CommandInfo.CommandCaller.builder()
                                    .functionality(f.getName())
                                    .fromAggregate(rootByService.getOrDefault(
                                            f.getOwnerService(), f.getOwnerService()))
                                    .access(c.getAccess())
                                    .build()))
                    .toList();
            return CommandInfo.builder()
                    .name(cmd.getName())
                    .handlerMethod(cmd.getHandlerMethod())
                    .serviceMethod(cmd.getServiceMethod())
                    .sentBy(callers.isEmpty() ? null : callers)
                    .build();
        }).sorted(Comparator.comparing(CommandInfo::getName)).toList();
    }

    /**
     * The other direction: what one Functionality sends, named in aggregate terms, and whether the
     * target actually accepts it.
     */
    private List<ProjectStructure.SentCommandRef> sentCommands(FunctionalityInfo f,
                                                               Map<String, String> rootByService,
                                                               Set<String> handled) {
        if (f.getCommands() == null || f.getCommands().isEmpty()) return null;
        return f.getCommands().stream()
                .map(c -> toRef(c, f.getName(), rootByService, handled))
                .toList();
    }

    /**
     * The workflow, step by step, with the commands of each named in aggregate terms.
     *
     * <p>Carries {@code dependsOn} and {@code wave} through untouched: they are what say which of
     * these steps are actually ordered with respect to each other, and recomputing that anywhere
     * downstream would be a second answer to a question the collector already answered.
     */
    private List<ProjectStructure.WorkflowStepRef> stepsOf(FunctionalityInfo f,
                                                           Map<String, String> rootByService,
                                                           Set<String> handled) {
        if (f.getSteps() == null || f.getSteps().isEmpty()) return null;
        return f.getSteps().stream()
                .map(s -> ProjectStructure.WorkflowStepRef.builder()
                        .index(s.getIndex())
                        .name(s.getName())
                        .dependsOn(s.getDependsOn())
                        .wave(s.getWave())
                        .registered(s.isRegistered())
                        .sends(refs(s.getCommands(), f.getName(), rootByService, handled))
                        // A compensation is not checked against the handler index: it mostly sends
                        // the bare Command, which every handler's default arm ignores on purpose —
                        // the lock release happens in SagaCommandHandler, after the dispatch.
                        .compensates(refs(s.getCompensation(), f.getName(), rootByService, null))
                        .build())
                .toList();
    }

    private List<ProjectStructure.SentCommandRef> refs(List<FunctionalityInfo.SentCommand> commands,
                                                       String functionality,
                                                       Map<String, String> rootByService,
                                                       Set<String> handled) {
        if (commands == null || commands.isEmpty()) return null;
        return commands.stream().map(c -> toRef(c, functionality, rootByService, handled)).toList();
    }

    /** @param handled null to skip the receiving-side check entirely */
    private ProjectStructure.SentCommandRef toRef(FunctionalityInfo.SentCommand c,
                                                  String functionality,
                                                  Map<String, String> rootByService,
                                                  Set<String> handled) {
        boolean unhandled = handled != null
                && !handled.contains(c.getTargetService() + "#" + c.getName());
        if (unhandled) {
            log.warn("[commands] {} sends {} to {}, whose handler has no case for it",
                    functionality, c.getName(), c.getTargetService());
        }
        return ProjectStructure.SentCommandRef.builder()
                .name(c.getName())
                .toAggregate(rootByService.getOrDefault(c.getTargetService(), c.getTargetService()))
                .access(c.getAccess())
                .unhandled(unhandled)
                .build();
    }

    /** {@code service#CommandName} for every accepted command — the receiving half of every seam. */
    private Set<String> handledIndex(Map<String, ServiceStaticMetrics> services) {
        Set<String> index = new LinkedHashSet<>();
        services.forEach((name, sm) -> {
            if (sm.getHandledCommands() == null) return;
            sm.getHandledCommands().forEach(c -> index.add(name + "#" + c.getName()));
        });
        return index;
    }

    // ─── Edges ──────────────────────────────────────────────────────────────────

    private List<ProjectStructure.AggregateRelation> outgoingRelations(
            ServiceStaticMetrics sm, Map<String, String> rootByService) {
        if (sm.getInterServiceDependencies() == null) return List.of();
        return sm.getInterServiceDependencies().entrySet().stream()
                .map(e -> toRelation(e.getKey(), e.getValue(), sm, rootByService))
                .sorted(Comparator.comparingInt(ProjectStructure.AggregateRelation::getTotalWeight).reversed())
                .toList();
    }

    /** The same edges seen from the other end: every service whose dependency map names this one. */
    private List<ProjectStructure.AggregateRelation> incomingRelations(
            String serviceName, Map<String, ServiceStaticMetrics> services,
            Map<String, String> rootByService) {
        List<ProjectStructure.AggregateRelation> in = new ArrayList<>();
        services.forEach((source, sm) -> {
            if (source.equals(serviceName) || sm.getInterServiceDependencies() == null) return;
            InterServiceDependency dep = sm.getInterServiceDependencies().get(serviceName);
            if (dep == null) return;
            // Named for the source: on an "incoming" list the interesting end is who depends on you.
            in.add(toRelation(source, dep, sm, rootByService));
        });
        in.sort(Comparator.comparingInt(ProjectStructure.AggregateRelation::getTotalWeight).reversed());
        return in;
    }

    private ProjectStructure.AggregateRelation toRelation(String otherService,
                                                          InterServiceDependency dep,
                                                          ServiceStaticMetrics owner,
                                                          Map<String, String> rootByService) {
        List<String> via = owner.getCrossServiceEntities() == null
                ? List.of() : owner.getCrossServiceEntities().getOrDefault(dep.getTargetService(), List.of());
        return ProjectStructure.AggregateRelation.builder()
                .toAggregate(rootByService.getOrDefault(otherService, otherService))
                .toService(otherService)
                .structuralRefs(dep.getStructuralRefs())
                .viaEntities(via.isEmpty() ? null : via)
                .events(emptyToNull(dep.getEventTypes()))
                .functionalities(emptyToNull(dep.getFunctionalityNames()))
                .readCommands(emptyToNull(dep.getReadCommands()))
                .writeCommands(emptyToNull(dep.getWriteCommands()))
                .totalWeight(dep.getTotalWeight())
                .build();
    }

    private List<String> emptyToNull(List<String> list) {
        return list == null || list.isEmpty() ? null : list;
    }
}
