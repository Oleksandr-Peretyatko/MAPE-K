package org.anonymous.msrefactor.rt.analyze.generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.AggregateSnapshot;
import org.anonymous.msrefactor.rt.analyze.AlternativeCandidate;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.analyze.EntityMove;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.InvariantChange;
import org.anonymous.msrefactor.rt.analyze.InvariantImpact;
import org.anonymous.msrefactor.rt.analyze.MetricImpact;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.analyze.ValidationStep;
import org.anonymous.msrefactor.rt.analyze.selector.AggregatePartitionAnalyzer;
import org.anonymous.msrefactor.rt.analyze.selector.ConstraintAwareSelector;
import org.anonymous.msrefactor.rt.analyze.selector.InvariantImpactAnalyzer;
import org.anonymous.msrefactor.rt.analyze.selector.OwnershipSelector;
import org.anonymous.msrefactor.rt.analyze.selector.ReplicaCandidateAnalyzer;
import org.anonymous.msrefactor.rt.analyze.selector.SatelliteNaming;
import org.anonymous.msrefactor.rt.analyze.selector.ValidationPlanBuilder;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.OwnershipForest;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StructuralSignals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps detected hotspots to candidate refactoring operations.
 *
 * <p>Each hotspot type activates one or two candidate operations. Candidates are then
 * deduplicated by {@code (operation, targetService, secondaryTarget)}, keeping the
 * first-generated entry for each key. Ranking is performed later by {@code SimpleRankingStrategy}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateGenerator {

    /** Spring replaces this with the bean; the default keeps direct `new` usable in unit tests. */
    @Autowired(required = false)
    private AnalysisThresholdsConfig thresholds = new AnalysisThresholdsConfig();

    private final AggregatePartitionAnalyzer  aggregatePartitionAnalyzer;
    private final ReplicaCandidateAnalyzer    replicaCandidateAnalyzer;
    private final InvariantImpactAnalyzer     invariantImpactAnalyzer;
    private final ValidationPlanBuilder       validationPlanBuilder;

    /** Optional — present only in the Spring context; null in plain unit tests (legacy selection). */
    @Autowired(required = false)
    private ConstraintAwareSelector           constraintAwareSelector;

    /**
     * Optional, same idiom: null in plain unit tests, where the ownership gate is simply not applied.
     * In the Spring context it is always present — a merge between two aggregates with no ownership
     * relation is not a preference, it is an operation that does not exist.
     */
    @Autowired(required = false)
    private OwnershipSelector                 ownershipSelector;

    /** Null-safe: constraint-aware selection is active only when the bean is present and the flag is on. */
    private boolean constraintAware() {
        return constraintAwareSelector != null && constraintAwareSelector.enabled();
    }

    /**
     * Generates and deduplicates candidate operations from the supplied hotspots and evidence.
     *
     * <p>May return fewer candidates than there are hotspots, or none at all: a hotspot whose
     * operation turns out not to be applicable is dropped rather than emitted as a stub. There is no
     * padding — an earlier version of this comment promised a {@code MIN_CANDIDATES} floor that was
     * never implemented.
     *
     * @param hotspots         detected hotspots, sorted by severity
     * @param structural       structural M1/M2 evidence
     * @param runtime          runtime latency/throughput evidence
     * @param consistency      consistency violation and compensation evidence
     * @return deduplicated, padded candidate list (order reflects generation; scoring will re-rank)
     */
    public List<CandidateOperation> generate(List<Hotspot> hotspots,
                                             StructuralEvidence structural,
                                             RuntimeEvidence runtime,
                                             ConsistencyEvidence consistency,
                                             AnalysisConfig config,
                                             DiscoveryGraph graph) {
        List<CandidateOperation> raw = new ArrayList<>();

        // The ownership picture of this snapshot, computed once: the regexes behind it are the same
        // for every hotspot, and MERGE_AGGREGATES is defined only on an ownership edge.
        OwnershipSelector.Index ownership = ownershipSelector == null ? null : ownershipSelector.index(structural);

        for (Hotspot h : hotspots) {
            List<CandidateOperation> perHotspot = new ArrayList<>();
            switch (h.getType()) {
                case HIGH_LATENCY       -> { /* surfaced as evidence only — no direct structural op */ }
                case TIGHT_COUPLING     -> generateForTightCoupling(h, structural, runtime, consistency, perHotspot, config, graph, ownership);
                case LOW_COHESION       -> generateForLowCohesion(h, structural, runtime, consistency, perHotspot, config, graph);
                case HIGH_ENTITY_COUNT  -> generateForHighEntityCount(h, structural, runtime, consistency, perHotspot, config, graph);
                case HIGH_COMPENSATION  -> generateForHighCompensation(h, structural, runtime, consistency, perHotspot, config);
                case READ_HEAVY         -> generateForReadHeavy(h, structural, runtime, consistency, perHotspot, config);
                case CONSISTENCY_VIOLATION -> generateForConsistencyViolation(h, structural, runtime, consistency, perHotspot, config);
                case HIGH_ERROR_RATE    -> { /* surfaced as evidence only — no direct structural op */ }
            }
            // Apply hotspot rules: filter to operations allowed for this hotspot type
            List<OperationType> allowed = config.getHotspotRules() != null
                    ? config.getHotspotRules().get(h.getType()) : null;
            if (allowed == null) {
                raw.addAll(perHotspot);
            } else {
                perHotspot.stream().filter(c -> allowed.contains(c.getOperation())).forEach(raw::add);
            }
        }

        // CREATE_REPLICA is generated outside the hotspot loop: the fact that justifies it — service
        // A needs service B's entity — is structural, and holds whether or not a load test happened
        // to make A look read-heavy. Runtime evidence scores these, inside the selector.
        // Skipped outright when disabled: the replica analysis is the costliest generator.
        if (thresholds.isEnabled(OperationType.CREATE_REPLICA)) {
            generateReplicaCandidates(structural, runtime, consistency, raw);
        }

        // MERGE_AGGREGATES is generated outside the loop for the same reason: what makes a merge
        // possible — one root owns the other — is structural, and a feasible pair should not depend
        // on whether a load test happened to make either service look coupled.
        generateOwnershipMergeCandidates(ownership, structural, runtime, consistency, raw);

        log.debug("Analyze: generated {} raw candidate(s)", raw.size());
        List<CandidateOperation> deduped = deduplicate(dropDisabledOperations(raw));
        log.debug("Analyze: {} after dedup", deduped.size());
        return deduped;
    }

    /**
     * The single chokepoint for {@code rt.analyze.enabled-operations}: every generator above —
     * per-hotspot, replica, ownership merge, and blocked placeholders — feeds {@code raw}, so a
     * disabled operation never reaches deepening, ranking, constraints or the frontend.
     */
    private List<CandidateOperation> dropDisabledOperations(List<CandidateOperation> raw) {
        Map<OperationType, Long> dropped = raw.stream()
                .filter(c -> !thresholds.isEnabled(c.getOperation()))
                .filter(c -> c.getOperation() != null)
                .collect(Collectors.groupingBy(CandidateOperation::getOperation, LinkedHashMap::new,
                        Collectors.counting()));
        if (!dropped.isEmpty()) {
            log.info("[analyze] dropped {} candidate(s) of disabled operations {} (enabled: {})",
                    dropped.values().stream().mapToLong(Long::longValue).sum(), dropped,
                    thresholds.getEnabledOperations());
        }
        return raw.stream().filter(c -> thresholds.isEnabled(c.getOperation())).collect(Collectors.toList());
    }

    // ─── Per-hotspot generators ───────────────────────────────────────────────

    private void generateForTightCoupling(Hotspot h, StructuralEvidence structural,
                                          RuntimeEvidence runtime, ConsistencyEvidence consistency,
                                          List<CandidateOperation> out, AnalysisConfig config,
                                          DiscoveryGraph graph, OwnershipSelector.Index ownership) {
        List<String> svcs = h.getAffectedServices();
        String a = svcs.size() > 0 ? svcs.get(0) : "unknown";
        String hinted = svcs.size() > 1 ? svcs.get(1) : null;

        if (ownership != null) {
            // The ownership gate runs before the constraint gate: a constraint expresses what the
            // architect forbids, ownership what the operation cannot express at all.
            List<String> ranked = couplingPartnersDesc(a, structural);
            if (hinted != null && !ranked.contains(hinted)) ranked.add(0, hinted);
            if (ranked.isEmpty()) return;   // no coupled partner at all — nothing actionable to propose

            // The pair coupling would have proposed. If ownership refuses it, it is blocked rather
            // than dropped: the architect saw σ point at these two services and is owed the cause.
            String strongest = ranked.get(0);
            if (ownershipSelector.mergeable(ownership, a, strongest).isEmpty()) {
                out.add(buildOwnershipBlockedPlaceholder(a, strongest, h.getQualityAttribute(),
                        ownershipSelector.blockReason(ownership, a, strongest),
                        ownershipSelector.chainFor(ownership, a, strongest)));
            }

            List<OwnershipSelector.Pair> feasible = ranked.stream()
                    .map(b -> ownershipSelector.mergeable(ownership, a, b))
                    .flatMap(Optional::stream)
                    .toList();

            if (feasible.isEmpty()) return;   // the placeholder above is the whole answer

            List<String> allowedPartners = feasible.stream()
                    .map(p -> p.owner().equals(a) ? p.child() : p.owner())
                    .toList();
            Optional<String> partner = constraintAware()
                    ? constraintAwareSelector.firstAllowed(OperationType.MERGE_AGGREGATES.name(), a,
                            allowedPartners, b -> pairBridgingEntities(a, b, structural))
                    : Optional.of(allowedPartners.get(0));

            if (partner.isEmpty()) {
                out.add(buildBlockedPlaceholder(OperationType.MERGE_AGGREGATES, a,
                        allowedPartners.get(0), h.getQualityAttribute(),
                        constraintAwareSelector.blockingReasonsAcross(OperationType.MERGE_AGGREGATES.name(), a,
                                allowedPartners, b -> pairBridgingEntities(a, b, structural))));
                return;
            }

            OwnershipSelector.Pair pair = feasible.stream()
                    .filter(p -> p.owner().equals(partner.get()) || p.child().equals(partner.get()))
                    .findFirst().orElse(feasible.get(0));
            out.add(buildMergeCandidate(h, pair, structural, runtime, consistency, config));
            return;
        }

        if (constraintAware()) {
            // Constraint-aware: rank a's coupled partners strongest-first and, independently per
            // operation, pick the strongest partner no blocking constraint forbids. Emit a blocked
            // placeholder only when EVERY coupled partner is forbidden for that operation.
            List<String> ranked = couplingPartnersDesc(a, structural);
            if (hinted != null && !ranked.contains(hinted)) ranked.add(0, hinted);
            if (ranked.isEmpty()) return; // no coupled partner at all — nothing actionable to propose

            Optional<String> mergeB = constraintAwareSelector.firstAllowed(
                    OperationType.MERGE_AGGREGATES.name(), a, ranked, b -> pairBridgingEntities(a, b, structural));
            if (mergeB.isPresent()) {
                out.add(buildMergeCandidate(h, a, mergeB.get(), structural, runtime, consistency, config));
            } else {
                out.add(buildBlockedPlaceholder(OperationType.MERGE_AGGREGATES, a,
                        ranked.get(0), h.getQualityAttribute(),
                        constraintAwareSelector.blockingReasonsAcross(OperationType.MERGE_AGGREGATES.name(), a,
                                ranked, b -> pairBridgingEntities(a, b, structural))));
            }

            return;
        }

        // ── Legacy path (flag off): original first-pair / arg-max single-partner resolution ──
        String b = hinted;
        // Tier 1: prefer a pair from the above-threshold coupling list
        if (b == null) {
            for (String[] pair : structural.getHighCouplingPairs()) {
                if (pair[0].equals(a)) { b = pair[1]; break; }
                if (pair[1].equals(a)) { b = pair[0]; break; }
            }
        }
        // Tier 2: if still null, pick the service with the highest raw external-relation count
        if (b == null) {
            ServiceStaticMetrics maFallback = resolveStatic(structural, a);
            if (maFallback != null && maFallback.getExternalRelations() != null
                    && !maFallback.getExternalRelations().isEmpty()) {
                b = maFallback.getExternalRelations().entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
            }
        }
        out.add(buildMergeCandidate(h, a, b, structural, runtime, consistency, config));
    }

    /**
     * Builds a MERGE_AGGREGATES candidate for a confirmed ownership pair, with the owner as the
     * surviving service whichever side the hotspot fired on.
     */
    private CandidateOperation buildMergeCandidate(Hotspot h, OwnershipSelector.Pair pair,
                                                   StructuralEvidence structural, RuntimeEvidence runtime,
                                                   ConsistencyEvidence consistency, AnalysisConfig config) {
        return buildMergeCandidate(h, pair.owner(), pair.child(), pair.edge(),
                structural, runtime, consistency, config);
    }

    /** Builds a MERGE_AGGREGATES candidate for the {@code (a, b)} pair ({@code b} may be null in the legacy path). */
    private CandidateOperation buildMergeCandidate(Hotspot h, String a, String b,
                                                   StructuralEvidence structural, RuntimeEvidence runtime,
                                                   ConsistencyEvidence consistency, AnalysisConfig config) {
        return buildMergeCandidate(h, a, b, null, structural, runtime, consistency, config);
    }

    private CandidateOperation buildMergeCandidate(Hotspot h, String a, String b,
                                                   OwnershipForest.Edge edge,
                                                   StructuralEvidence structural, RuntimeEvidence runtime,
                                                   ConsistencyEvidence consistency, AnalysisConfig config) {
        int sigma = 0;
        List<String> bridging = new ArrayList<>();
        if (b != null) {
            ServiceStaticMetrics ma = resolveStatic(structural, a);
            ServiceStaticMetrics mb = resolveStatic(structural, b);
            if (ma != null) {
                sigma += ma.getExternalRelations().getOrDefault(b, 0);
                if (ma.getCrossServiceEntities() != null)
                    bridging.addAll(ma.getCrossServiceEntities().getOrDefault(b, List.of()));
            }
            if (mb != null) {
                sigma += mb.getExternalRelations().getOrDefault(a, 0);
                if (mb.getCrossServiceEntities() != null)
                    bridging.addAll(mb.getCrossServiceEntities().getOrDefault(a, List.of()));
            }
        }
        String bridgingStr = bridging.isEmpty() ? "" : " via [" + String.join(", ", bridging) + "]";

        RefactorBlueprint mergeBp = b != null ? buildMergeBlueprint(a, b, structural, runtime, consistency) : null;
        String mergeSummary = mergeBp != null ? mergeBp.getSummary()
                : (b != null ? "Merge " + a + " ↔ " + b + " aggregates" : "Merge aggregates in " + a);
        return CandidateOperation.builder()
                .operation(OperationType.MERGE_AGGREGATES)
                .targetService(a)
                .secondaryTarget(b)
                .summary(mergeSummary)
                .whyApplicable(String.format("High structural coupling (σ=%d) between %s and %s%s; "
                        + "merging eliminates cross-service invariant overhead%s",
                        sigma, a, b, bridgingStr, ownershipNote(h, a, edge)))
                // Null hotspot: an enumerated ownership edge, which no hotspot raised.
                .primaryQaTarget(h != null && h.getQualityAttribute() != null
                        ? h.getQualityAttribute() : QualityAttribute.PERFORMANCE)
                .secondaryQaEffects(Map.of(
                        QualityAttribute.MAINTAINABILITY, "Reduces aggregate count, increases aggregate size",
                        QualityAttribute.CONSISTENCY, "Inter-invariants become intra"))
                .preconditions(List.of(
                        edge != null
                                ? edge.ownerRoot() + " owns " + edge.childRoot()
                                  + " — every " + edge.childRoot() + " belongs to exactly one "
                                  + edge.ownerRoot() + " for its whole life"
                                : "Aggregates must share a bounded context",
                        "No independent lifecycle requirements"))
                .risks(List.of(
                        "Increases aggregate complexity",
                        "SAGA steps disappear but new intra-invariants may be needed"))
                .dependencies(List.of())
                .affectedEntities(bridging.isEmpty() ? null : bridging)
                .blueprint(mergeBp)
                .build();
    }

    /**
     * The ownership evidence, appended to {@code whyApplicable}. Names the hotspot's own service when
     * the direction was normalised, so nobody reads the swap as a mistake.
     */
    private String ownershipNote(Hotspot h, String owner, OwnershipForest.Edge edge) {
        if (edge == null) return "";
        String signals = String.format("O1 (%s)%s%s", edge.signals().viaMember(),
                edge.signals().o2() ? " + O2" : "", edge.signals().o3() ? " + O3" : "");
        String hotspotService = h != null && h.getAffectedServices() != null
                && !h.getAffectedServices().isEmpty() ? h.getAffectedServices().get(0) : null;
        String normalised = hotspotService != null && !hotspotService.equals(owner)
                ? String.format("; hotspot on %s, direction normalised", hotspotService) : "";
        return String.format(". %s owns %s — %s%s",
                edge.ownerRoot(), edge.childRoot(), signals, normalised);
    }

    /**
     * A pair coupling proposed that ownership refuses. Blocked rather than dropped: the architect
     * asked about these two services, and "it disappeared" is not an answer.
     *
     * <p>A sibling pair carries the chain that would make it reachable in {@code dependencies} — the
     * same {@code owner<-child} notation the prerequisite channel uses — so the entry says both that
     * this merge cannot run and what would have to run first.
     */
    private CandidateOperation buildOwnershipBlockedPlaceholder(String a, String b, QualityAttribute qa,
                                                                String reason, List<String> chain) {
        return CandidateOperation.builder()
                .operation(OperationType.MERGE_AGGREGATES)
                .targetService(a)
                .secondaryTarget(b)
                .summary("Merge " + a + " ↔ " + b + " is not expressible — no ownership relation")
                .whyApplicable("MergeAggregates is defined on an ownership edge: one root must hold a "
                        + "single-valued replica of the other and be created or queried in its scope. "
                        + reason)
                .primaryQaTarget(qa != null ? qa : QualityAttribute.PERFORMANCE)
                .secondaryQaEffects(Map.of())
                .preconditions(List.of())
                .risks(List.of())
                .dependencies(chain == null ? List.of() : chain)
                .blockedBy(reason)
                .build();
    }

    /**
     * One candidate per confirmed ownership edge, independent of any hotspot.
     *
     * <p>Without this the feasible merges of a codebase are only proposed when a load test happens to
     * make one of the two services look coupled, which is how {@code course ← topic} — the cleanest
     * merge in the reference application — never reached the list.
     */
    private void generateOwnershipMergeCandidates(OwnershipSelector.Index ownership,
                                                  StructuralEvidence structural, RuntimeEvidence runtime,
                                                  ConsistencyEvidence consistency,
                                                  List<CandidateOperation> out) {
        if (ownership == null) return;
        for (OwnershipForest.Edge edge : ownershipSelector.ownedEdges(ownership)) {
            String owner = edge.ownerService();
            String child = edge.childService();
            if (owner == null || child == null) continue;
            if (constraintAware() && constraintAwareSelector.isBlocked(
                    OperationType.MERGE_AGGREGATES.name(), owner, child,
                    pairBridgingEntities(owner, child, structural))) {
                continue;   // an architect constraint already answers this pair
            }
            out.add(buildMergeCandidate(null, owner, child, edge, structural, runtime, consistency, null));
        }
    }

    /** Ranks {@code a}'s coupled partners strongest-first by bidirectional σ (σ_ab + σ_ba). */
    private List<String> couplingPartnersDesc(String a, StructuralEvidence structural) {
        Map<String, Integer> sigmaByPartner = new LinkedHashMap<>();
        ServiceStaticMetrics ma = resolveStatic(structural, a);
        if (ma != null && ma.getExternalRelations() != null) {
            ma.getExternalRelations().forEach((b, v) -> sigmaByPartner.merge(b, v, Integer::sum));
        }
        if (structural.getPerService() != null) {
            structural.getPerService().forEach((b, mb) -> {
                if (b.equals(a) || mb.getExternalRelations() == null) return;
                int back = mb.getExternalRelations().getOrDefault(a, 0);
                if (back > 0) sigmaByPartner.merge(b, back, Integer::sum);
            });
        }
        return sigmaByPartner.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** Bridging entity classes for the pair: crossServiceEntities[a][b] ∪ crossServiceEntities[b][a]. */
    private List<String> pairBridgingEntities(String a, String b, StructuralEvidence structural) {
        List<String> out = new ArrayList<>();
        ServiceStaticMetrics ma = resolveStatic(structural, a);
        ServiceStaticMetrics mb = resolveStatic(structural, b);
        if (ma != null && ma.getCrossServiceEntities() != null)
            out.addAll(ma.getCrossServiceEntities().getOrDefault(b, List.of()));
        if (mb != null && mb.getCrossServiceEntities() != null)
            out.addAll(mb.getCrossServiceEntities().getOrDefault(a, List.of()));
        return out;
    }

    /**
     * Builds a non-executable placeholder candidate (carries {@code blockedBy}) for a hotspot whose
     * every operand is forbidden by a blocking architect constraint. Routed to the blocked section so
     * the architect still sees the hotspot was addressed.
     */
    private CandidateOperation buildBlockedPlaceholder(OperationType op,
                                                       String target, String secondary,
                                                       QualityAttribute qa, List<String> reasons) {
        String reasonStr = (reasons == null || reasons.isEmpty())
                ? "All " + op.name() + " options restricted by architect constraints"
                : String.join("; ", reasons);
        return CandidateOperation.builder()
                .operation(op)
                .targetService(target)
                .secondaryTarget(secondary)
                .summary("All " + op.name() + " options for " + target
                        + " are restricted by architect constraints")
                .whyApplicable("Every viable operand for this hotspot is forbidden by a blocking architect constraint.")
                .primaryQaTarget(qa != null ? qa : QualityAttribute.MAINTAINABILITY)
                .secondaryQaEffects(Map.of())
                .preconditions(List.of())
                .risks(List.of())
                .dependencies(List.of())
                .blockedBy(reasonStr)
                .build();
    }

    private void generateForLowCohesion(Hotspot h, StructuralEvidence structural,
                                        RuntimeEvidence runtime, ConsistencyEvidence consistency,
                                        List<CandidateOperation> out, AnalysisConfig config,
                                        DiscoveryGraph graph) {
        String target = primaryService(h);
        ServiceStaticMetrics sm = resolveStatic(structural, target);
        List<String> ents = sm != null && sm.getEntityNames() != null ? sm.getEntityNames() : List.of();

        AggregatePartitionAnalyzer.PartitionResult partition =
                aggregatePartitionAnalyzer.partition(target, sm, graph,
                        SatelliteNaming.takenNames(structural.getPerService(), graph));

        // Constraint-aware: if every satellite candidate entity is forbidden, emit a blocked placeholder.
        if (partition != null && partition.allBlocked()) {
            out.add(buildBlockedPlaceholder(OperationType.SPLIT_AGGREGATE,
                    target, null, h.getQualityAttribute(), partition.blockReasons()));
            return;
        }

        // No partition, no operation. The aggregate has fewer than two entities, so there is nothing
        // to divide; emitting the candidate anyway leaves the skill a SPLIT_AGGREGATE with no
        // blueprint to execute.
        if (partition == null) {
            log.debug("[analyze] {} — no viable partition ({} entities); SPLIT_AGGREGATE not emitted",
                    target, ents.size());
            return;
        }

        RefactorBlueprint splitBpLow =
                buildSplitBlueprintFromPartition(target, partition, structural, runtime, consistency);
        String splitSummaryLow = buildSplitSummary(target, partition, sm, splitBpLow);

        InvariantImpact invImpact = splitInvariantImpact(sm, partition, graph, target);

        List<AlternativeCandidate> alts = new ArrayList<>(partition.alternativePartitions());
        List<ValidationStep> valPlan = validationPlanBuilder.buildFor(OperationType.SPLIT_AGGREGATE, target, null);

        out.add(CandidateOperation.builder()
                .operation(OperationType.SPLIT_AGGREGATE)
                .targetService(target)
                .summary(splitSummaryLow)
                .whyApplicable(buildSplitWhyApplicable(target, partition, sm,
                        ents.isEmpty() ? "" : " with entities [" + String.join(", ", ents) + "]",
                        "low M1 contribution"))
                .primaryQaTarget(h.getQualityAttribute() != null
                        ? h.getQualityAttribute() : QualityAttribute.MAINTAINABILITY)
                .secondaryQaEffects(Map.of(
                        QualityAttribute.PERFORMANCE, "May introduce new cross-aggregate calls"))
                .preconditions(buildSplitPreconditions(invImpact))
                .risks(buildSplitRisks(invImpact))
                .dependencies(buildSplitDependencies(target, partition, sm))
                .affectedEntities(ents.isEmpty() ? null : ents)
                .blueprint(splitBpLow)
                .invariantImpact(invImpact)
                .alternatives(alts.isEmpty() ? null : alts)
                .validationPlan(valPlan)
                .build());
    }

    private void generateForHighEntityCount(Hotspot h, StructuralEvidence structural,
                                            RuntimeEvidence runtime, ConsistencyEvidence consistency,
                                            List<CandidateOperation> out, AnalysisConfig config,
                                            DiscoveryGraph graph) {
        String target = primaryService(h);
        ServiceStaticMetrics sm = resolveStatic(structural, target);
        List<String> ents = sm != null && sm.getEntityNames() != null ? sm.getEntityNames() : List.of();
        int n = sm != null ? sm.getEntityCount() : ents.size();
        String entStr = ents.isEmpty() ? "" : ": [" + String.join(", ", ents) + "]";

        AggregatePartitionAnalyzer.PartitionResult partition =
                aggregatePartitionAnalyzer.partition(target, sm, graph,
                        SatelliteNaming.takenNames(structural.getPerService(), graph));

        // Constraint-aware: if every satellite candidate entity is forbidden, emit a blocked placeholder.
        if (partition != null && partition.allBlocked()) {
            out.add(buildBlockedPlaceholder(OperationType.SPLIT_AGGREGATE,
                    target, null, h.getQualityAttribute(), partition.blockReasons()));
            return;
        }
        // No partition, no operation — see the twin guard in generateForLowCohesion.
        if (partition == null) {
            log.debug("[analyze] {} — no viable partition ({} entities); SPLIT_AGGREGATE not emitted",
                    target, ents.size());
            return;
        }

        RefactorBlueprint splitBpHigh =
                buildSplitBlueprintFromPartition(target, partition, structural, runtime, consistency);
        String splitSummaryHigh = buildSplitSummary(target, partition, sm, splitBpHigh);

        InvariantImpact invImpact = splitInvariantImpact(sm, partition, graph, target);

        List<AlternativeCandidate> alts = new ArrayList<>(partition.alternativePartitions());
        List<ValidationStep> valPlan = validationPlanBuilder.buildFor(OperationType.SPLIT_AGGREGATE, target, null);

        out.add(CandidateOperation.builder()
                .operation(OperationType.SPLIT_AGGREGATE)
                .targetService(target)
                .summary(splitSummaryHigh)
                .whyApplicable(buildSplitWhyApplicable(target, partition, sm,
                        String.format(" (N=%d%s)", n, entStr),
                        String.format("high entity count (N=%d)", n)))
                .primaryQaTarget(h.getQualityAttribute() != null
                        ? h.getQualityAttribute() : QualityAttribute.MAINTAINABILITY)
                .secondaryQaEffects(Map.of(
                        QualityAttribute.PERFORMANCE, "May introduce new cross-aggregate calls"))
                .preconditions(buildSplitPreconditions(invImpact))
                .risks(buildSplitRisks(invImpact))
                .dependencies(buildSplitDependencies(target, partition, sm))
                .affectedEntities(ents.isEmpty() ? null : ents)
                .blueprint(splitBpHigh)
                .invariantImpact(invImpact)
                .alternatives(alts.isEmpty() ? null : alts)
                .validationPlan(valPlan)
                .build());
    }

    private void generateForHighCompensation(Hotspot h, StructuralEvidence structural,
                                             RuntimeEvidence runtime, ConsistencyEvidence consistency,
                                             List<CandidateOperation> out, AnalysisConfig config) {
        String target = primaryService(h);
        ServiceStaticMetrics sm = resolveStatic(structural, target);
        List<String> invMethods = sm != null && sm.getInvariants() != null
                ? sm.getInvariants().stream().map(InvariantInfo::getName).toList() : List.of();
        Map<String, List<String>> cse = (sm != null && sm.getCrossServiceEntities() != null)
                ? sm.getCrossServiceEntities() : Map.of();
        Map.Entry<String, List<String>> primaryReplicaSource = cse.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size())).orElse(null);
        String replicaOwner = primaryReplicaSource != null ? primaryReplicaSource.getKey() : null;
        List<String> replicaEnts = primaryReplicaSource != null
                ? primaryReplicaSource.getValue() : List.of();

        // Constraint-aware REMOVE_REPLICA owner: pick the strongest-allowed owner; mark blocked if all forbidden.
        boolean removeReplicaBlocked = false;
        List<String> removeBlockReasons = List.of();
        String removePlaceholderSecondary = replicaOwner;
        if (constraintAware() && !cse.isEmpty()) {
            List<String> rankedOwners = cse.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()))
                    .map(Map.Entry::getKey).collect(Collectors.toList());
            removePlaceholderSecondary = rankedOwners.isEmpty() ? null : rankedOwners.get(0);
            Optional<String> allowedOwner = constraintAwareSelector.firstAllowed(
                    OperationType.REMOVE_REPLICA.name(), target, rankedOwners, o -> cse.getOrDefault(o, List.of()));
            if (allowedOwner.isPresent()) {
                replicaOwner = allowedOwner.get();
                replicaEnts = cse.getOrDefault(replicaOwner, List.of());
            } else {
                removeReplicaBlocked = true;
                removeBlockReasons = constraintAwareSelector.blockingReasonsAcross(
                        OperationType.REMOVE_REPLICA.name(), target, rankedOwners, o -> cse.getOrDefault(o, List.of()));
            }
        }

        double compRate = consistency != null ? consistency.getAvgCompensationRate() : 0;
        String compStr = compRate > 0 ? String.format(" (compensation rate %.0f%%)", compRate * 100) : "";
        String invStr = invMethods.isEmpty() ? "" : " — review invariants [" + String.join(", ", invMethods) + "]";

        if (removeReplicaBlocked) {
            out.add(buildBlockedPlaceholder(OperationType.REMOVE_REPLICA,
                    target, removePlaceholderSecondary, h.getQualityAttribute(), removeBlockReasons));
        } else {
            String replicaStr = replicaEnts.isEmpty() ? "" : " [" + String.join(", ", replicaEnts) + "]";
            String removeSummaryComp = replicaOwner != null
                    ? "Remove replica of " + replicaOwner + " data in " + target
                    : "Remove stale replica in " + target;
            out.add(CandidateOperation.builder()
                    .operation(OperationType.REMOVE_REPLICA)
                    .targetService(target)
                    .secondaryTarget(replicaOwner)
                    .summary(removeSummaryComp)
                    .whyApplicable("High compensation rate on " + target + compStr
                            + "; " + (replicaOwner != null
                                    ? "service " + target + " holds replicas of "
                                      + String.join(", ", replicaEnts) + " from " + replicaOwner + invStr
                                    : "review replica sources" + invStr)
                            + "; removing forces direct reads and eliminates staleness")
                    .primaryQaTarget(h.getQualityAttribute() != null
                            ? h.getQualityAttribute() : QualityAttribute.CONSISTENCY)
                    .secondaryQaEffects(Map.of(
                            QualityAttribute.PERFORMANCE, "Direct reads replace local replica reads — latency increases"))
                    .preconditions(List.of(
                            "A replica must exist for the affected entity",
                            "Replica lag is measurable"))
                    .risks(List.of(
                            "Performance degrades (remote reads required)",
                            "SAGA steps may be reintroduced"))
                    .dependencies(List.of())
                    .affectedEntities(replicaEnts.isEmpty() ? null : replicaEnts)
                    .blueprint(buildRemoveReplicaBlueprint(target, replicaOwner, replicaEnts))
                    .validationPlan(validationPlanBuilder.buildFor(OperationType.REMOVE_REPLICA, target, null))
                    .build());
        }
    }

    /**
     * READ_HEAVY no longer produces CREATE_REPLICA.
     *
     * <p>A read-heavy reading is a property of the service's own traffic and says nothing about
     * which other service it needs, so it could name neither the source nor the entity. Replica
     * opportunities are now enumerated from the read commands each service actually issues — see
     * {@link #generateReplicaCandidates} — and this hotspot's runtime evidence scores them there.
     */
    private void generateForReadHeavy(Hotspot h, StructuralEvidence structural,
                                      RuntimeEvidence runtime, ConsistencyEvidence consistency,
                                      List<CandidateOperation> out, AnalysisConfig config) {
        // Intentionally empty — see generateReplicaCandidates.
    }

    /** Most replica opportunities one consumer may contribute; the rest are logged and dropped. */
    private static final int MAX_REPLICAS_PER_CONSUMER = 3;

    /**
     * One CREATE_REPLICA candidate per (consumer, source, entity) that survives the read-only gate.
     *
     * <p>A consumer typically needs entities from several sources, and each is an independent
     * decision — separately scored, approved and executed — which is also what the single-operand
     * skill contract requires.
     */
    private void generateReplicaCandidates(StructuralEvidence structural, RuntimeEvidence runtime,
                                           ConsistencyEvidence consistency,
                                           List<CandidateOperation> out) {
        if (structural == null || structural.getPerService() == null) return;

        for (Map.Entry<String, ServiceStaticMetrics> e : structural.getPerService().entrySet()) {
            String consumer = e.getKey();
            List<ReplicaCandidateAnalyzer.ReplicaOpportunity> opportunities =
                    replicaCandidateAnalyzer.findOpportunities(consumer, e.getValue(),
                            structural.getPerService(), runtime, consistency,
                            MAX_REPLICAS_PER_CONSUMER);

            for (ReplicaCandidateAnalyzer.ReplicaOpportunity o : opportunities) {
                out.add(buildReplicaCandidate(consumer, o, structural, runtime));
            }
        }
    }

    private CandidateOperation buildReplicaCandidate(String consumer,
                                                     ReplicaCandidateAnalyzer.ReplicaOpportunity o,
                                                     StructuralEvidence structural,
                                                     RuntimeEvidence runtime) {
        String reads = String.join(", ", o.readCommands());
        String via = o.readFunctionalities().isEmpty() ? ""
                : " via " + String.join(", ", o.readFunctionalities());

        String why = consumer + " reads " + o.entity() + " from " + o.sourceService()
                + " across the service boundary (" + reads + ")" + via
                + "; replicating it locally removes " + o.readCommands().size()
                + " remote read path(s) at the cost of an eventual-consistency window"
                + (o.runtimeBacked() ? "" : ". NOTE: no runtime measurement covers this path — "
                        + "the coupling is structural only, so the benefit is unquantified");

        return CandidateOperation.builder()
                .operation(OperationType.CREATE_REPLICA)
                .targetService(consumer)
                .secondaryTarget(o.sourceService())
                .summary("Replicate " + o.entity() + " from " + o.sourceService() + " in " + consumer)
                .whyApplicable(why)
                .primaryQaTarget(QualityAttribute.PERFORMANCE)
                .secondaryQaEffects(Map.of(
                        QualityAttribute.CONSISTENCY, "Replica lag introduces eventual-consistency window",
                        QualityAttribute.MAINTAINABILITY, "Increases sync complexity"))
                .preconditions(List.of(
                        consumer + " only reads " + o.entity() + " — no write command targets it",
                        "Acceptable staleness window: " + o.stalenessWindow(),
                        o.syncEventAvailable()
                                ? o.sourceService() + " already publishes events " + consumer + " subscribes to"
                                : "A sync event must be created in " + o.sourceService()
                                  + " (the skill will request /new-event first)"))
                .risks(buildReplicaRisks(o))
                .dependencies(List.of())
                // Exactly one entity — CreateReplica replicates one per invocation.
                .affectedEntities(List.of(o.entity()))
                .blueprint(RefactorBlueprint.builder()
                        .summary("Create a local replica of " + o.entity()
                                + " from " + o.sourceService() + " in " + consumer)
                        .before(List.of())
                        .after(List.of())
                        .entityMoves(List.of())
                        .invariantChanges(List.of())
                        .implementationSteps(List.of(
                                (o.replicaAlreadyExists()
                                        ? "Extend the existing replica class in " + consumer
                                        : "Define a replica value object for " + o.entity()
                                          + " in " + consumer),
                                o.syncEventAvailable()
                                        ? "Subscribe to the existing " + o.sourceService() + " sync event"
                                        : "Create and subscribe to a sync event in " + o.sourceService(),
                                "Replace " + reads + " with local replica reads",
                                "Set staleness window to " + o.stalenessWindow() + " and add monitoring alert",
                                "Verify p95 read latency drops in the next Locust run"))
                        .metricImpact(buildCreateReplicaMetricImpact(consumer, structural, runtime))
                        .build())
                .validationPlan(validationPlanBuilder.buildFor(
                        OperationType.CREATE_REPLICA, consumer, o.entity()))
                .selectionScore(o.score())
                .build();
    }

    private void generateForConsistencyViolation(Hotspot h, StructuralEvidence structural,
                                                  RuntimeEvidence runtime, ConsistencyEvidence consistency,
                                                  List<CandidateOperation> out, AnalysisConfig config) {
        // Find services that carry cross-service entity references (likely replica holders)
        List<String> replicaServices = structural.getPerService() != null
                ? structural.getPerService().entrySet().stream()
                        .filter(e -> e.getValue().getCrossServiceEntities() != null
                                && !e.getValue().getCrossServiceEntities().isEmpty())
                        .map(Map.Entry::getKey).collect(Collectors.toList())
                : List.of();
        String suspectStr = replicaServices.isEmpty() ? ""
                : " (suspect services with replicas: " + String.join(", ", replicaServices) + ")";

        String primaryReplicaService = structural.getPerService() != null
                ? structural.getPerService().entrySet().stream()
                        .filter(e -> e.getValue().getCrossServiceEntities() != null
                                && !e.getValue().getCrossServiceEntities().isEmpty())
                        .max(Comparator.comparingInt(e ->
                                e.getValue().getCrossServiceEntities().values().stream()
                                        .mapToInt(List::size).sum()))
                        .map(Map.Entry::getKey).orElse("system")
                : "system";
        // Resolve authoritative source for the primary replica holder
        ServiceStaticMetrics pSm = structural.getPerService() != null
                ? structural.getPerService().get(primaryReplicaService) : null;
        String replicaSourceCv = null;
        List<String> crossEntsCv = List.of();
        if (pSm != null && pSm.getCrossServiceEntities() != null
                && !pSm.getCrossServiceEntities().isEmpty()) {
            Map.Entry<String, List<String>> biggest = pSm.getCrossServiceEntities().entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().size())).orElse(null);
            if (biggest != null) {
                replicaSourceCv = biggest.getKey();
                crossEntsCv = biggest.getValue();
            }
        }

        if (constraintAware() && replicaSourceCv != null
                && constraintAwareSelector.isBlocked(OperationType.REMOVE_REPLICA.name(),
                        primaryReplicaService, replicaSourceCv, crossEntsCv)) {
            out.add(buildBlockedPlaceholder(OperationType.REMOVE_REPLICA,
                    primaryReplicaService, replicaSourceCv, h.getQualityAttribute(),
                    constraintAwareSelector.blockingReasons(OperationType.REMOVE_REPLICA.name(),
                            primaryReplicaService, replicaSourceCv, crossEntsCv)));
        } else {
            String rrSummaryCv = replicaSourceCv != null
                    ? "Remove replica of " + replicaSourceCv + " data in " + primaryReplicaService
                    : "Remove stale replica in " + primaryReplicaService;
            out.add(CandidateOperation.builder()
                    .operation(OperationType.REMOVE_REPLICA)
                    .targetService(primaryReplicaService)
                    .secondaryTarget(replicaSourceCv)
                    .summary(rrSummaryCv)
                    .whyApplicable("Invariant violations detected in spans; stale replicas are a common cause"
                            + suspectStr)
                    .primaryQaTarget(h.getQualityAttribute() != null
                            ? h.getQualityAttribute() : QualityAttribute.CONSISTENCY)
                    .secondaryQaEffects(Map.of(
                            QualityAttribute.PERFORMANCE, "Performance degrades as direct reads replace local replica"))
                    .preconditions(List.of("A replica must exist and exhibit measurable lag"))
                    .risks(List.of("Cross-service reads reintroduced"))
                    .dependencies(List.of())
                    .affectedEntities(replicaServices.isEmpty() ? null : replicaServices)
                    .blueprint(buildRemoveReplicaBlueprint(primaryReplicaService, replicaSourceCv, crossEntsCv))
                    .validationPlan(validationPlanBuilder.buildFor(OperationType.REMOVE_REPLICA, primaryReplicaService, null))
                    .build());
        }

    }

    // ─── Deduplication ────────────────────────────────────────────────────────

    /**
     * Removes duplicate {@code (operation, targetService, secondaryTarget)} entries,
     * keeping the first-generated entry for each key.
     *
     * <p>The key includes {@code secondaryTarget} so TIGHT_COUPLING candidates for different
     * service pairs (e.g., A↔B and A↔C) are not incorrectly collapsed into one.
     */
    private List<CandidateOperation> deduplicate(List<CandidateOperation> candidates) {
        Map<String, CandidateOperation> seen = new LinkedHashMap<>();
        for (CandidateOperation c : candidates) {
            // The entity is part of the identity: one consumer can legitimately need two different
            // entities from the same source, and those are two separate operations. Without it the
            // second collapses into the first and is silently lost.
            String entity = c.getAffectedEntities() == null || c.getAffectedEntities().isEmpty()
                    ? "" : c.getAffectedEntities().get(0);
            String key = c.getOperation() + ":"
                    + c.getTargetService() + ":"
                    + (c.getSecondaryTarget() != null ? c.getSecondaryTarget() : "") + ":"
                    + entity;
            seen.putIfAbsent(key, c);
        }
        return new ArrayList<>(seen.values());
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private String primaryService(Hotspot h) {
        List<String> svcs = h.getAffectedServices();
        return (svcs != null && !svcs.isEmpty()) ? svcs.get(0) : "unknown";
    }

    /**
     * Looks up static metrics by service name, normalising runtime names (e.g. "tournament-service")
     * to the bare directory names used as keys in the static analysis map (e.g. "tournament").
     */
    private static ServiceStaticMetrics resolveStatic(StructuralEvidence structural, String name) {
        if (structural.getPerService() == null || name == null) return null;
        ServiceStaticMetrics sm = structural.getPerService().get(name);
        if (sm != null) return sm;
        String bare = name.endsWith("-service") ? name.substring(0, name.length() - 8) : name;
        return structural.getPerService().get(bare);
    }

    /**
     * Looks up runtime metrics by service name, trying the raw name first then appending "-service"
     * to bridge static analysis names (bare) to runtime names (with suffix).
     */
    private static AggregateMetrics resolveRuntime(RuntimeEvidence runtime, String name) {
        if (runtime == null || runtime.getPerServiceMetrics() == null) return null;
        AggregateMetrics am = runtime.getPerServiceMetrics().get(name);
        return am != null ? am : runtime.getPerServiceMetrics().get(name + "-service");
    }


    /**
     * How a chosen partition lands on the aggregate's rules, of both kinds.
     *
     * <p>Runs whenever either list is present. Guarding on the intra list alone would skip an
     * aggregate whose consistency is entirely cross-aggregate — {@code QuizAnswer} has no
     * {@code verifyInvariants()} body and three rules maintained by subscription.
     */
    private InvariantImpact splitInvariantImpact(ServiceStaticMetrics sm,
                                                 AggregatePartitionAnalyzer.PartitionResult partition,
                                                 DiscoveryGraph graph, String target) {
        if (sm == null) return null;
        List<InvariantInfo> intra = sm.getInvariants();
        List<InterInvariantInfo> inter = sm.getInterInvariants();
        if (intra == null && inter == null) return null;
        return invariantImpactAnalyzer.analyzeForSplit(
                partition.coreEntities(), partition.satelliteEntities(),
                intra == null ? List.of() : intra,
                inter == null ? List.of() : inter,
                resolveGraphInvariants(graph, target));
    }

    /** Names only, for the places that just list or count invariants. Null-safe. */
    private static List<String> invariantNames(ServiceStaticMetrics sm) {
        return sm == null || sm.getInvariants() == null ? List.of()
                : sm.getInvariants().stream().map(InvariantInfo::getName).toList();
    }

    // ─── Blueprint builders ───────────────────────────────────────────────────

    private RefactorBlueprint buildMergeBlueprint(String serviceA, String serviceB,
                                                   StructuralEvidence structural,
                                                   RuntimeEvidence runtime, ConsistencyEvidence consistency) {
        ServiceStaticMetrics smA = resolveStatic(structural, serviceA);
        ServiceStaticMetrics smB = resolveStatic(structural, serviceB);
        if (smA == null || smB == null) return null;

        List<String> entA = smA.getEntityNames()     != null ? smA.getEntityNames()     : List.of();
        List<String> entB = smB.getEntityNames()     != null ? smB.getEntityNames()     : List.of();
        List<String> invA = invariantNames(smA);
        List<String> invB = invariantNames(smB);
        List<String> mergedEnt = new ArrayList<>(entA); mergedEnt.addAll(entB);
        List<String> mergedInv = new ArrayList<>(invA); mergedInv.addAll(invB);

        String capA = Character.toUpperCase(serviceA.charAt(0)) + serviceA.substring(1);
        String capB = Character.toUpperCase(serviceB.charAt(0)) + serviceB.substring(1);

        List<InvariantChange> invChanges = mergedInv.stream().map(inv ->
                InvariantChange.builder().invariantName(inv).changeType("BECOMES_INTRA")
                        .fromLocation(invA.contains(inv) ? capA : capB)
                        .toLocation(capA + "+" + capB)
                        .rationale("Merging eliminates cross-service enforcement overhead").build())
                .collect(Collectors.toList());

        return RefactorBlueprint.builder()
                .summary("Merge " + capA + " and " + capB + " into a single aggregate")
                .before(List.of(
                        AggregateSnapshot.builder().aggregateName(capA).serviceName(serviceA)
                                .entities(entA).invariants(invA).isNew(false).build(),
                        AggregateSnapshot.builder().aggregateName(capB).serviceName(serviceB)
                                .entities(entB).invariants(invB).isNew(false).build()))
                .after(List.of(AggregateSnapshot.builder()
                        .aggregateName(capA + capB).serviceName(serviceA)
                        .entities(mergedEnt).invariants(mergedInv).isNew(true).build()))
                .entityMoves(List.of())
                .invariantChanges(invChanges)
                .implementationSteps(List.of(
                        "Move all entities from " + capB + " into " + capA,
                        "Convert inter-invariants between them into intra-invariants in " + capA,
                        "Remove SAGA steps that coordinated " + capA + " ↔ " + capB,
                        "Update all event subscriptions referencing " + capB,
                        "Run full test suite to verify invariant consolidation"))
                .metricImpact(buildMergeMetricImpact(serviceA, serviceB, smA, smB, structural, runtime))
                .build();
    }

    private RefactorBlueprint buildRemoveReplicaBlueprint(String target, String source,
                                                            List<String> entities) {
        if (entities == null) entities = List.of();
        String entityStr = entities.isEmpty() ? "the replica entity" : String.join(", ", entities);
        String sourceStr = source != null ? source : "the source service";
        String capTarget = cap(target);
        String syncEvent = source != null ? cap(source) + "Updated" : "sync";
        return RefactorBlueprint.builder()
                .summary("Remove replica of " + entityStr + " from " + capTarget
                        + (source != null ? " (authoritative source: " + source + ")" : ""))
                .before(List.of())
                .after(List.of())
                .entityMoves(List.of())
                .invariantChanges(List.of())
                .implementationSteps(List.of(
                        "Identify replica fields/tables in " + capTarget + " that mirror "
                                + entityStr + " from " + sourceStr,
                        "Replace local replica reads in " + capTarget
                                + " with direct calls to " + sourceStr,
                        "Remove the " + syncEvent + " event subscription in " + capTarget,
                        "Drop replica schema columns/tables from " + capTarget,
                        "Run Locust to verify compensation rate drops"))
                .build();
    }

    // ─── Metric impact builders ───────────────────────────────────────────────

    private MetricImpact buildSplitMetricImpact(String service, ServiceStaticMetrics sm,
                                                 List<String> coreEntities, List<String> satEntities,
                                                 StructuralEvidence structural, RuntimeEvidence runtime) {
        if (sm == null) return null;

        double m1Before = sm.getM1Contribution();
        // No m1After. This used to assert m1Before * 1.6 — the same +60% for every cut, so it could
        // not distinguish one partition from another, and it cannot happen anyway: M1 is
        // entityCount-and-relations of the *service*, and both aggregates of this split stay in the
        // same service. Reporting the measured baseline and declining to predict is the honest
        // rendering; direction() turns a null "after" into a neutral arrow.
        Double m1After = null;

        String entityCountBefore = String.valueOf(sm.getEntityCount());
        String entityCountAfter  = coreEntities.size() + " / " + satEntities.size();

        double m2Before = structural.getM2();
        // M2 is neutral on split — same cross-service boundary, no new links introduced.
        double m2After  = m2Before;

        // The cut itself adds one reference each way between the two halves and one more service.
        Map<String, ServiceStaticMetrics> all = structural.getPerService() != null
                ? structural.getPerService() : Map.of();
        Double couplingBefore = couplingDensityOf(all);
        Double couplingAfter  = all.isEmpty() ? null
                : couplingDensityOf(splitView(all, service, coreEntities.size(), satEntities.size()));
        Double cohesionBefore = clusterFactorOf(sm, all);

        int extRels = 0;
        if (sm.getExternalRelations() != null)
            extRels = sm.getExternalRelations().values().stream().mapToInt(Integer::intValue).sum();

        Double p95Before = null;
        {
            AggregateMetrics am = resolveRuntime(runtime, service);
            if (am != null && am.getP95LatencyMs() > 0) p95Before = am.getP95LatencyMs();
        }

        return MetricImpact.builder()
                .m1Before(m1Before)
                .m1After(m1After)
                .m2Before(m2Before > 0 ? m2Before : null)
                .m2After(m2After > 0 ? m2After : null)
                .cohesionBefore(cohesionBefore)
                .couplingDensityBefore(couplingBefore)
                .couplingDensityAfter(couplingAfter)
                .entityCountBefore(entityCountBefore)
                .entityCountAfter(entityCountAfter)
                .externalRelsBefore(extRels > 0 ? extRels : null)
                .externalRelsAfter(extRels > 0 ? extRels : null)
                .p95LatencyMsBefore(p95Before)
                // p95 after is omitted — split does not directly reduce latency
                .estimationNote("M1 is computed per service, and both aggregates of this split stay "
                        + "in the same service, so the service's entity and relation counts are "
                        + "unchanged and M1 cannot move — no 'after' value is claimed. What this "
                        + "operation measurably changes is the number of invariants the cut splits, "
                        + "reported with the partition; entity counts show the split itself.")
                .build();
    }

    private MetricImpact buildMergeMetricImpact(String serviceA, String serviceB,
                                                 ServiceStaticMetrics smA, ServiceStaticMetrics smB,
                                                 StructuralEvidence structural, RuntimeEvidence runtime) {
        if (smA == null || smB == null) return null;

        int sigmaAB = 0;
        if (smA.getExternalRelations() != null)
            sigmaAB += smA.getExternalRelations().getOrDefault(serviceB, 0);
        if (smB.getExternalRelations() != null)
            sigmaAB += smB.getExternalRelations().getOrDefault(serviceA, 0);

        double m1Before = (smA.getM1Contribution() + smB.getM1Contribution()) / 2.0;
        // Closed form, from the merged service's own counts: the σ_AB classes are the replicas each
        // side holds of the other, which the merge removes along with their back-reference, and the
        // absorbed root becomes a member held by one relation annotated on both sides.
        int nAfter  = Math.max(1, smA.getEntityCount() + smB.getEntityCount() - sigmaAB);
        int muAfter = Math.max(0, smA.getInternalRelations() + smB.getInternalRelations() - sigmaAB) + 2;
        Double m1After = (double) muAfter / ((double) nAfter * nAfter);
        double m2Before = structural.getM2();
        Map<String, ServiceStaticMetrics> all = structural.getPerService() != null
                ? structural.getPerService() : Map.of();
        Map<String, ServiceStaticMetrics> merged = all.isEmpty() ? Map.of()
                : mergedView(all, serviceA, serviceB, nAfter, muAfter);
        Double m2After = merged.isEmpty() ? null : m2Of(merged);
        Double couplingBefore = couplingDensityOf(all);
        Double couplingAfter  = merged.isEmpty() ? null : couplingDensityOf(merged);
        Double cohesionBefore = average(clusterFactorOf(smA, all), clusterFactorOf(smB, all));
        Double cohesionAfter  = merged.isEmpty() ? null : clusterFactorOf(merged.get(serviceA), merged);

        int extA = smA.getExternalRelations() != null
                ? smA.getExternalRelations().values().stream().mapToInt(Integer::intValue).sum() : 0;
        int extB = smB.getExternalRelations() != null
                ? smB.getExternalRelations().values().stream().mapToInt(Integer::intValue).sum() : 0;
        int extBefore = extA + extB;
        int extAfter  = Math.max(0, extBefore - sigmaAB);

        Double p95Before = null;
        Double p95After  = null;
        {
            double maxP95 = 0;
            AggregateMetrics amA = resolveRuntime(runtime, serviceA);
            AggregateMetrics amB = resolveRuntime(runtime, serviceB);
            if (amA != null && amA.getP95LatencyMs() > maxP95) maxP95 = amA.getP95LatencyMs();
            if (amB != null && amB.getP95LatencyMs() > maxP95) maxP95 = amB.getP95LatencyMs();
            // p95Before only. How much latency a merge actually removes depends on call volume and
            // on how much of the p95 was the network hop, neither of which static analysis knows.
            if (maxP95 > 0) p95Before = maxP95;
        }

        return MetricImpact.builder()
                .m1Before(m1Before > 0 ? m1Before : null)
                .m1After(m1After)
                .m2Before(m2Before > 0 ? m2Before : null)
                .m2After(m2After)
                .cohesionBefore(cohesionBefore)
                .cohesionAfter(cohesionAfter)
                .couplingDensityBefore(couplingBefore)
                .couplingDensityAfter(couplingAfter)
                .entityCountBefore(smA.getEntityCount() + " / " + smB.getEntityCount())
                .entityCountAfter(String.valueOf(nAfter))
                .externalRelsBefore(extBefore > 0 ? extBefore : null)
                .externalRelsAfter(extAfter > 0 ? extAfter : null)
                .p95LatencyMsBefore(p95Before)
                .p95LatencyMsAfter(p95After)
                .estimationNote("What this merge measurably changes: the two services become one, so "
                        + "the " + sigmaAB + " cross-service relation(s) between them stop being "
                        + "cross-service — that is the external-relations row, and it is exact "
                        + "arithmetic. M1, M2, cluster factor and coupling density 'after' are "
                        + "closed-form from the merged service's counts (replicas of each other "
                        + "removed, the absorbed root held by one relation). Raw M1 falls on most "
                        + "merges by construction (its N² denominator); the cluster factor and "
                        + "coupling density are the signals the scores read.")
                .build();
    }

    // ─── Closed-form structural estimates ────────────────────────────────────

    /** The service map after A absorbs B: one service, B's references re-pointed to A. */
    static Map<String, ServiceStaticMetrics> mergedView(Map<String, ServiceStaticMetrics> all,
                                                        String a, String b, int nAfter, int muAfter) {
        Map<String, ServiceStaticMetrics> out = new LinkedHashMap<>();
        Map<String, Integer> mergedRefs = new LinkedHashMap<>();
        for (String src : List.of(a, b)) {
            ServiceStaticMetrics s = all.get(src);
            if (s == null || s.getExternalRelations() == null) continue;
            s.getExternalRelations().forEach((target, n) -> {
                if (!target.equals(a) && !target.equals(b)) mergedRefs.merge(target, n, Integer::sum);
            });
        }
        for (Map.Entry<String, ServiceStaticMetrics> e : all.entrySet()) {
            if (e.getKey().equals(a) || e.getKey().equals(b)) continue;
            ServiceStaticMetrics s = e.getValue();
            Map<String, Integer> refs = new LinkedHashMap<>();
            if (s.getExternalRelations() != null) s.getExternalRelations().forEach((target, n) ->
                    refs.merge(target.equals(b) ? a : target, n, Integer::sum));
            out.put(e.getKey(), s.toBuilder().externalRelations(refs).build());
        }
        ServiceStaticMetrics base = all.get(a) != null ? all.get(a) : ServiceStaticMetrics.builder().serviceName(a).build();
        out.put(a, base.toBuilder().serviceName(a).entityCount(nAfter).internalRelations(muAfter)
                .externalRelations(mergedRefs).build());
        return out;
    }

    /** The service map after a split: the satellite is a new service, one reference each way. */
    static Map<String, ServiceStaticMetrics> splitView(Map<String, ServiceStaticMetrics> all,
                                                       String service, int coreN, int satN) {
        Map<String, ServiceStaticMetrics> out = new LinkedHashMap<>(all);
        ServiceStaticMetrics s = all.get(service);
        if (s == null) return out;
        String sat = service + "-satellite";
        Map<String, Integer> coreRefs = new LinkedHashMap<>();
        if (s.getExternalRelations() != null) coreRefs.putAll(s.getExternalRelations());
        coreRefs.merge(sat, 1, Integer::sum);
        out.put(service, s.toBuilder().entityCount(Math.max(1, coreN)).externalRelations(coreRefs).build());
        out.put(sat, ServiceStaticMetrics.builder().serviceName(sat).entityCount(Math.max(1, satN))
                .externalRelations(Map.of(service, 1)).build());
        return out;
    }

    /** Mancoridis M2 over a service map (same formula as the collector). */
    static Double m2Of(Map<String, ServiceStaticMetrics> services) {
        List<ServiceStaticMetrics> list = new ArrayList<>(services.values());
        int t = list.size();
        if (t <= 1) return 0.0;
        double sum = 0;
        for (int i = 0; i < t; i++) {
            for (int j = i + 1; j < t; j++) {
                ServiceStaticMetrics si = list.get(i), sj = list.get(j);
                int sigma = refs(si, sj.getServiceName()) + refs(sj, si.getServiceName());
                if (sigma > 0 && si.getEntityCount() > 0 && sj.getEntityCount() > 0)
                    sum += sigma / (2.0 * si.getEntityCount() * sj.getEntityCount());
            }
        }
        return sum / (t * (t - 1) / 2.0);
    }

    /** {@code Σσ / ((t'−1)·ΣN)} over a service map. */
    static Double couplingDensityOf(Map<String, ServiceStaticMetrics> services) {
        if (services == null || services.size() <= 1) return services == null ? null : 0.0;
        int totalN = services.values().stream().mapToInt(ServiceStaticMetrics::getEntityCount).sum();
        int totalRefs = services.values().stream().mapToInt(StructuralSignals::outgoingRefs).sum();
        double max = (double) (services.size() - 1) * totalN;
        return max == 0 ? 0.0 : Math.min(1.0, totalRefs / max);
    }

    private static Double clusterFactorOf(ServiceStaticMetrics s, Map<String, ServiceStaticMetrics> all) {
        if (s == null) return null;
        int ext = StructuralSignals.outgoingRefs(s)
                + (s.getServiceName() == null ? 0 : StructuralSignals.incomingRefs(s.getServiceName(), all.values()));
        return StructuralSignals.clusterFactor(s.getEntityCount(), s.getInternalRelations(), ext);
    }

    private static int refs(ServiceStaticMetrics s, String target) {
        return s.getExternalRelations() == null ? 0 : s.getExternalRelations().getOrDefault(target, 0);
    }

    private static Double average(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return (a + b) / 2.0;
    }

    private MetricImpact buildCreateReplicaMetricImpact(String service,
                                                         StructuralEvidence structural,
                                                         RuntimeEvidence runtime) {
        Double p95Before = null;
        Double p95After  = null;
        {
            // p95Before only. p95 is the service's whole traffic mix, and how much of it a local
            // read replaces depends on how often that particular read runs — which this figure does
            // not say. A fixed 70% reduction was the same claim for every replica.
            AggregateMetrics am = resolveRuntime(runtime, service);
            if (am != null && am.getP95LatencyMs() > 0) p95Before = am.getP95LatencyMs();
        }

        Double m2Before = structural.getM2() > 0 ? structural.getM2() : null;
        // A replica does add coupling, so M2 genuinely worsens; by how much depends on the σ of the
        // affected pair and the two services' entity counts, not on a flat +5%.
        Double m2After  = null;

        if (p95Before == null && m2Before == null) return null;

        return MetricImpact.builder()
                .m2Before(m2Before).m2After(m2After)
                .p95LatencyMsBefore(p95Before).p95LatencyMsAfter(p95After)
                .estimationNote("A replica trades consistency for read locality: the cross-service "
                        + "reads it removes are listed with the candidate, and the staleness window "
                        + "it introduces is stated there too. No 'after' value is claimed for p95 or "
                        + "M2 — both would move, but neither is derivable from static analysis alone.")
                .build();
    }

    // ─── Naming and graph helpers ─────────────────────────────────────────────

    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private List<DiscoveryGraph.DiscoveredInvariant> resolveGraphInvariants(
            DiscoveryGraph graph, String service) {
        if (graph == null || graph.getServices() == null) return null;
        return graph.getServices().stream()
                .filter(svc -> service.equals(svc.getName()))
                .filter(svc -> svc.getAggregates() != null)
                .flatMap(svc -> svc.getAggregates().stream())
                .filter(agg -> agg.getInvariants() != null)
                .flatMap(agg -> agg.getInvariants().stream())
                .collect(Collectors.toList());
    }

    // ─── Granular SPLIT_AGGREGATE helpers ─────────────────────────────────────

    private RefactorBlueprint buildSplitBlueprintFromPartition(
            String service, AggregatePartitionAnalyzer.PartitionResult partition,
            StructuralEvidence structural, RuntimeEvidence runtime, ConsistencyEvidence consistency) {
        ServiceStaticMetrics sm = resolveStatic(structural, service);
        if (sm == null) return null;

        List<String> allEntities = sm.getEntityNames() != null ? sm.getEntityNames() : List.of();
        List<InvariantInfo> invariants = sm.getInvariants() != null ? sm.getInvariants() : List.of();
        List<String> allInvariants = invariants.stream().map(InvariantInfo::getName).toList();
        List<String> core = partition.coreEntities();
        List<String> sat  = partition.satelliteEntities();
        String satName    = partition.satelliteName();
        String capSvc     = cap(service);

        // An invariant travels with the satellite only if every entity it reads went there and it
        // needs nothing from the root, which stays behind. Matching the method name against entity
        // names instead — the previous approach — never matched, so every rule was reported as
        // staying in the core, including the ones guarding entities that had just moved out.
        List<String> satInvariants = invariants.stream()
                .filter(inv -> {
                    List<String> reads = inv.getMemberEntities() == null ? List.of() : inv.getMemberEntities();
                    boolean needsRoot = inv.isReadsState() || inv.isReadsPrev()
                            || (inv.getRootScalars() != null && !inv.getRootScalars().isEmpty());
                    return !reads.isEmpty() && sat.containsAll(reads) && !needsRoot;
                })
                .map(InvariantInfo::getName)
                .collect(Collectors.toList());
        List<String> coreInvariants = allInvariants.stream()
                .filter(inv -> !satInvariants.contains(inv))
                .collect(Collectors.toList());

        List<EntityMove> moves = sat.stream().map(e -> EntityMove.builder()
                .entityName(e).fromAggregate(capSvc).toAggregate(satName)
                .rationale(partition.cost() == 0
                        ? "Moves without splitting any invariant"
                        : "Part of the cheapest available cut (" + partition.cost()
                            + " invariant(s) straddle it)")
                .build()).collect(Collectors.toList());

        List<InvariantChange> invChanges = new ArrayList<>();
        satInvariants.forEach(inv -> invChanges.add(InvariantChange.builder()
                .invariantName(inv).changeType("MOVES")
                .fromLocation(capSvc).toLocation(satName)
                .rationale("Invariant guards " + satName + " entities").build()));
        coreInvariants.forEach(inv -> invChanges.add(InvariantChange.builder()
                .invariantName(inv).changeType("STAYS")
                .fromLocation(capSvc).toLocation(capSvc).build()));

        String costNote = partition.cost() == 0
                ? " [no invariant is split]"
                : " [" + partition.cost() + " invariant(s) straddle the cut: "
                    + String.join(", ", partition.straddlingInvariants()) + "]";
        return RefactorBlueprint.builder()
                .summary("Split " + capSvc + " into " + capSvc + " (core) + " + satName + costNote)
                .before(List.of(AggregateSnapshot.builder().aggregateName(capSvc).serviceName(service)
                        .entities(allEntities).invariants(allInvariants).isNew(false).build()))
                .after(List.of(
                        AggregateSnapshot.builder().aggregateName(capSvc).serviceName(service)
                                .entities(core).invariants(coreInvariants).isNew(false).build(),
                        AggregateSnapshot.builder().aggregateName(satName).serviceName(service)
                                .entities(sat).invariants(satInvariants).isNew(true).build()))
                .entityMoves(moves)
                .invariantChanges(invChanges)
                .implementationSteps(List.of(
                        "Extract " + String.join(", ", sat) + " into new " + satName + " aggregate",
                        satInvariants.isEmpty() ? "No invariants move — verify manually"
                                : "Move invariants: " + String.join(", ", satInvariants),
                        "Update " + capSvc + " SAGA to coordinate with " + satName,
                        "Add cross-aggregate event subscriptions as needed",
                        "Run tests and verify invariants hold across the new boundary"))
                .metricImpact(buildSplitMetricImpact(service, sm, core, sat, structural, runtime))
                .build();
    }

    private String buildSplitSummary(String service, AggregatePartitionAnalyzer.PartitionResult partition,
                                      ServiceStaticMetrics sm, RefactorBlueprint fallbackBp) {
        if (partition == null) {
            return fallbackBp != null ? fallbackBp.getSummary()
                    : "Split " + service + " aggregate by sub-responsibility";
        }
        String satName = partition.satelliteName();
        // The cost is not invariants alone any more: a replica the cut carries across counts too,
        // because the satellite inherits the quad that keeps it fresh. Name each term for what it is.
        List<String> terms = new ArrayList<>();
        if (!partition.straddlingInvariants().isEmpty()) {
            terms.add(partition.straddlingInvariants().size() + " invariant(s) to rework");
        }
        if (!partition.interInvariantsMovingToSatellite().isEmpty()
                || !partition.interInvariantsSplittingAnchor().isEmpty()) {
            terms.add((partition.interInvariantsMovingToSatellite().size()
                    + partition.interInvariantsSplittingAnchor().size()) + " subscription(s) to re-home");
        }
        if (!partition.replicasMoved().isEmpty()) {
            terms.add(partition.replicasMoved().size() + " replica(s) to keep fresh");
        }
        String reason = terms.isEmpty()
                ? " (" + partition.satelliteEntities().size() + " entities, nothing to rework)"
                : " (" + partition.satelliteEntities().size() + " entities, "
                    + String.join(", ", terms) + ")";
        return "Split " + cap(service) + " → " + cap(service) + " (core, "
                + partition.coreEntities().size() + " entities) + " + satName + reason;
    }

    private String buildSplitWhyApplicable(String service,
                                            AggregatePartitionAnalyzer.PartitionResult partition,
                                            ServiceStaticMetrics sm, String hotspotEvidence,
                                            String hotspotReason) {
        StringBuilder sb = new StringBuilder();
        sb.append(cap(hotspotReason)).append(" on ").append(service).append(hotspotEvidence);
        if (partition != null) {
            sb.append("; cheapest available cut moves [")
              .append(String.join(", ", partition.satelliteEntities()))
              .append("] out");
            if (partition.cost() == 0) {
                sb.append(", splitting no invariant — every rule either stays or relocates verbatim");
            } else if (!partition.straddlingInvariants().isEmpty()) {
                sb.append("; ").append(partition.straddlingInvariants().size())
                  .append(" invariant(s) would straddle it (")
                  .append(String.join(", ", partition.straddlingInvariants())).append(")");
            }
            if (!partition.replicasMoved().isEmpty()) {
                sb.append("; the satellite takes over refreshing ")
                  .append(String.join(", ", partition.replicasMoved()));
            }
            if (!partition.replicasShared().isEmpty()) {
                // Both sides read the copy, so it cannot simply move: duplicate it, or re-point one
                // side to a remote read, which is a CREATE_REPLICA before the split.
                sb.append("; ").append(String.join(", ", partition.replicasShared()))
                  .append(" is read on both sides of the cut and has to be duplicated or re-pointed");
            }
            if (!partition.temporalInvariants().isEmpty()) {
                // getPrev() compares against this aggregate's own history, and after the split each
                // side keeps its own. Such a rule cannot be re-homed; it has to exist on both sides.
                sb.append(". ").append(String.join(", ", partition.temporalInvariants()))
                  .append(" compares against the previous version and must be duplicated on both sides");
            }
            sb.append(". The satellite name is provisional — a cheap cut is not by itself a "
                    + "coherent responsibility, so rename it to whatever it turns out to be");
        }
        return sb.toString();
    }

    private List<String> buildSplitPreconditions(InvariantImpact invImpact) {
        List<String> pre = new ArrayList<>();
        pre.add("Aggregate must have identifiable sub-responsibilities");
        pre.add("Entities can be grouped into cohesive sub-clusters");
        if (invImpact != null && invImpact.getBecomesCrossBoundary() != null
                && !invImpact.getBecomesCrossBoundary().isEmpty()) {
            pre.add("Cross-boundary invariants must be redesigned as inter-aggregate SAGAs");
        }
        return pre;
    }

    /**
     * The sub-operations that have to run <b>before</b> this split, derived from what the cut breaks.
     *
     * <p>The cost of a cut is the invariants it splits, and each of those has a resolution that
     * follows from <em>what</em> makes it cross. The rule is assumed to travel with the satellite —
     * it is the entities it iterates that moved — so what has to be arranged beforehand is whatever
     * it still reads on the core side:
     *
     * <ul>
     *   <li><b>Only a root scalar.</b> Replicate the scalar into the satellite with an event; the
     *       rule stays a plain {@code verifyInvariants()} check, reading a local field.</li>
     *   <li><b>A member entity of the core.</b> Replicating a collection is not a field copy: the
     *       rule becomes an inter-aggregate one, so the event comes with an {@code /inter-invariant}.
     *       </li>
     *   <li><b>{@code getPrev()}.</b> Nothing to prepare. Each side keeps its own version chain, so
     *       the rule cannot span the cut at all and has to be duplicated inside the split itself —
     *       {@code whyApplicable} already says so.</li>
     *   <li><b>The aggregate's own {@code getState()}.</b> Left as a question rather than a command:
     *       after the split the satellite has a state of its own, and whether the rule meant that one
     *       or the core's lifecycle is not something the footprint can settle.</li>
     * </ul>
     *
     * <p>This is the <em>derived</em> half of {@code dependencies}. The other half is reactive — a
     * chain a skill discovered by hitting the prerequisite during a real execution — and
     * {@code AnalyzeService} unions the two, reactive first.
     */
    private List<String> buildSplitDependencies(String service,
                                                AggregatePartitionAnalyzer.PartitionResult partition,
                                                ServiceStaticMetrics sm) {
        if (partition == null || partition.straddlingInvariants() == null
                || partition.straddlingInvariants().isEmpty()) return List.of();
        if (sm == null || sm.getInvariants() == null) return List.of();

        String core      = cap(service);
        String satellite = partition.satelliteName();
        List<String> sat = partition.satelliteEntities();

        Map<String, InvariantInfo> byName = sm.getInvariants().stream()
                .filter(i -> i.getName() != null)
                .collect(Collectors.toMap(InvariantInfo::getName, i -> i, (a, b) -> a, LinkedHashMap::new));

        // Two rules can need the same event. Keyed by the command so the chain asks for it once,
        // carrying every rule that needs it — a repeated /new-event would read as two events.
        Map<String, List<String>> reasonsByStep = new LinkedHashMap<>();

        for (String name : partition.straddlingInvariants()) {
            InvariantInfo inv = byName.get(name);
            if (inv == null || inv.isReadsPrev()) continue;

            List<String> reads = inv.getMemberEntities() == null ? List.of() : inv.getMemberEntities();
            List<String> crossMembers = reads.stream().filter(e -> !sat.contains(e)).toList();

            if (crossMembers.isEmpty()) {
                List<String> scalars = inv.getRootScalars() == null ? List.of() : inv.getRootScalars();
                for (String scalar : scalars) {
                    record(reasonsByStep, String.format(
                            "/new-event Update%s%sEvent %s %s — replicates %s.%s, which stays in the "
                            + "core, so the rule remains an intra-invariant of %s. Needed by",
                            core, cap(scalar), core, satellite, core, scalar, satellite), name);
                }
                if (scalars.isEmpty() && inv.isReadsState()) {
                    record(reasonsByStep, String.format(
                            "Decide before splitting: %s will have a state of its own, so if the rule "
                            + "meant %s's lifecycle it needs /new-event Update%sStateEvent %s %s, and "
                            + "if it meant its own there is nothing to do. Reads getState()",
                            satellite, core, core, core, satellite), name);
                }
            } else {
                for (String entity : crossMembers) {
                    record(reasonsByStep, String.format(
                            "/new-event Update%sEvent %s %s — replicates %s, which stays in the core. "
                            + "Needed by", entity, core, satellite, entity), name);
                }
                record(reasonsByStep, String.format(
                        "/inter-invariant %s %s — it reads %s across the new boundary, so it stops "
                        + "being a method on either aggregate and becomes event-maintained. Rule",
                        satellite, name, String.join(", ", crossMembers)), name);
            }
        }

        return reasonsByStep.entrySet().stream()
                .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.toList());
    }

    private static void record(Map<String, List<String>> reasonsByStep, String step, String invariant) {
        reasonsByStep.computeIfAbsent(step, k -> new ArrayList<>());
        List<String> names = reasonsByStep.get(step);
        if (!names.contains(invariant)) names.add(invariant);
    }

    private List<String> buildSplitRisks(InvariantImpact invImpact) {
        List<String> risks = new ArrayList<>();
        if (invImpact != null && invImpact.getBlockingWarnings() != null
                && !invImpact.getBlockingWarnings().isEmpty()) {
            risks.add("Most invariants straddle this cut ("
                    + String.join(", ", invImpact.getBlockingWarnings())
                    + ") — the split likely needs preparatory work first");
        }
        risks.add("New inter-aggregate dependencies required");
        risks.add("Existing SAGA steps may need redesign");
        return risks;
    }

    // ─── Granular CREATE_REPLICA helpers ──────────────────────────────────────

    private List<String> buildReplicaRisks(ReplicaCandidateAnalyzer.ReplicaOpportunity o) {
        List<String> risks = new ArrayList<>();
        risks.add("Replica lag may cause invariant violations");
        risks.add("Increases event subscription complexity");
        risks.add("Staleness window: " + o.stalenessWindow());
        if (!o.syncEventAvailable()) {
            risks.add("No sync event exists yet — a /new-event prerequisite runs before the replica");
        }
        if (!o.runtimeBacked()) {
            risks.add("No runtime measurement covers this read path — the latency benefit is unproven");
        }
        // The "target writes more than it reads" risk is gone: a written entity no longer produces a
        // candidate at all, so it can never appear here.
        return risks;
    }
}
