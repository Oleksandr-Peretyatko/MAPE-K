package org.anonymous.msrefactor.rt.analyze.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.InterServiceDependency;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds and scores CREATE_REPLICA opportunities.
 *
 * <p>A replica is warranted when one service <b>needs another service's entity</b>: the behaviour is
 * unchanged and consistency is traded for read locality. That sentence has three parts, and each is
 * answered from evidence rather than inferred:
 *
 * <ul>
 *   <li><b>Does A reach B at all?</b> {@code InterServiceDependency.readCommands} — the commands A's
 *       saga steps actually send. Empty means there is no read to make local, and no candidate.</li>
 *   <li><b>Is the reach read-only?</b> {@code writeCommands} must be empty. A write means the entity
 *       is misplaced, not remote; a replica would create a second source of truth.</li>
 *   <li><b>Which entity?</b> the read commands name it — {@code GetCourseExecutionByIdCommand}
 *       replicates {@code CourseExecution} — validated against B's real class set before use.</li>
 * </ul>
 *
 * <p>Runtime evidence scores an opportunity; it never creates or vetoes one. The structural fact is
 * that A needs B's entity, and that is true whether or not a load test exercised the path.
 */
@Slf4j
@Component
public class ReplicaCandidateAnalyzer {

    /**
     * Score below which an opportunity with no runtime backing is clamped, so structurally real but
     * never-exercised paths rank last rather than disappearing.
     */
    private static final double UNMEASURED_CEILING = 0.35;

    /** One (consumer ← source, entity) opportunity, with the evidence that justifies it. */
    public record ReplicaOpportunity(
            String sourceService,
            String entity,
            List<String> readCommands,
            List<String> readFunctionalities,
            boolean syncEventAvailable,
            boolean replicaAlreadyExists,
            boolean runtimeBacked,
            double score,
            String stalenessWindow
    ) {}

    /**
     * Every replica opportunity this consumer has, best first.
     *
     * @param consumerService the service that would host the replicas
     * @param smConsumer      its static metrics
     * @param allServices     all services, for validating the entity against its owner
     * @param runtime         runtime evidence (nullable) — scores, never gates
     * @param consistency     consistency evidence (nullable)
     * @param maxPerConsumer  cap; what is dropped is logged
     */
    public List<ReplicaOpportunity> findOpportunities(
            String consumerService,
            ServiceStaticMetrics smConsumer,
            Map<String, ServiceStaticMetrics> allServices,
            RuntimeEvidence runtime,
            ConsistencyEvidence consistency,
            int maxPerConsumer) {

        if (smConsumer == null || smConsumer.getInterServiceDependencies() == null) return List.of();

        List<ReplicaOpportunity> found = new ArrayList<>();

        for (Map.Entry<String, InterServiceDependency> e : smConsumer.getInterServiceDependencies().entrySet()) {
            String source = e.getKey();
            InterServiceDependency edge = e.getValue();
            if (source.equals(consumerService) || edge == null) continue;

            // Reach gate. Null means the snapshot predates command capture — unknown, not "none" —
            // and an unknown edge is not evidence of a read, so it yields nothing either way.
            List<String> reads = edge.getReadCommands();
            if (reads == null || reads.isEmpty()) continue;

            // Read-only gate. Suppressed silently: a written entity is an ownership problem, and a
            // CREATE_REPLICA candidate for it would be refused by the Execute phase every time.
            List<String> writes = edge.getWriteCommands();
            if (writes != null && !writes.isEmpty()) {
                log.debug("[analyze] {} ← {}: no replica — {} also writes it ({})",
                        consumerService, source, consumerService, writes);
                continue;
            }

            ServiceStaticMetrics smSource = allServices == null ? null : allServices.get(source);
            for (String entity : resolveEntities(reads, source, smSource, smConsumer)) {
                found.add(score(consumerService, smConsumer, source, entity, edge,
                        reads, runtime, consistency));
            }
        }

        found.sort(Comparator.comparingDouble(ReplicaOpportunity::score).reversed());
        if (found.size() > maxPerConsumer) {
            log.info("[analyze] {} has {} replica opportunities; keeping the top {} — dropped: {}",
                    consumerService, found.size(), maxPerConsumer,
                    found.subList(maxPerConsumer, found.size()).stream()
                            .map(o -> o.sourceService() + "/" + o.entity()).toList());
            found = new ArrayList<>(found.subList(0, maxPerConsumer));
        }
        return found;
    }

    // ─── Which entity ───────────────────────────────────────────────────────────

    /**
     * The entities named by a set of read commands, keeping only those that are real classes of the
     * source.
     *
     * <p>Validation is the point. A parsed name that no class matches is dropped rather than
     * emitted: naming the wrong entity produces a candidate that looks valid and costs a full
     * iteration to disprove, whereas naming none is visibly incomplete.
     */
    private List<String> resolveEntities(List<String> readCommands, String source,
                                         ServiceStaticMetrics smSource, ServiceStaticMetrics smConsumer) {
        Set<String> known = new LinkedHashSet<>();
        if (smSource != null) {
            if (smSource.getEntityNames() != null) known.addAll(smSource.getEntityNames());
            if (smSource.getRootNames() != null) known.addAll(smSource.getRootNames());
        }
        // The consumer's own view of which of the source's entities it names — an independent
        // confirmation from Pass 2, useful when the source's entity list is incomplete.
        if (smConsumer.getReferencedForeignEntities() != null) {
            known.addAll(smConsumer.getReferencedForeignEntities().getOrDefault(source, List.of()));
        }

        Set<String> out = new LinkedHashSet<>();
        for (String command : readCommands) {
            String parsed = entityFromCommand(command);
            if (parsed == null) continue;

            // Exact match: the command names the class directly.
            String match = known.stream().filter(k -> k.equalsIgnoreCase(parsed)).findFirst().orElse(null);
            if (match != null) { out.add(match); continue; }

            // The DTO-family case, and it is the common one here. `execution`'s root is `Execution`
            // but its DTO and every replica of it are named `CourseExecution*`, so
            // GetCourseExecutionByIdCommand names `CourseExecution` — a real thing in that service's
            // vocabulary, just not a class name. Accept it when it extends one of the source's
            // roots, and keep the command's spelling: that is the name the replica is built from
            // (`new TournamentCourseExecution(courseExecutionDto)`), and the name the skill expects.
            boolean extendsARoot = known.stream()
                    .anyMatch(k -> parsed.length() > k.length() && parsed.endsWith(k));
            if (extendsARoot) { out.add(parsed); continue; }

            log.debug("[analyze] {} names '{}' but nothing in {} matches it — dropped",
                    command, parsed, source);
        }
        return new ArrayList<>(out);
    }

    /**
     * {@code GetCourseExecutionByIdCommand → CourseExecution},
     * {@code FindQuestionsByTopicIdsCommand → Question}.
     *
     * <p>The command vocabulary is the same one the replica is built from: the class named here is
     * the class whose {@code Dto} the replica's constructor takes.
     */
    String entityFromCommand(String commandClass) {
        if (commandClass == null || commandClass.isBlank()) return null;
        String s = commandClass;
        if (s.startsWith("Get")) s = s.substring(3);
        else if (s.startsWith("Find")) s = s.substring(4);
        else return null;                       // not a read verb — nothing to name
        if (s.endsWith("Command")) s = s.substring(0, s.length() - "Command".length());

        // Everything from a qualifier onward describes the lookup, not the entity.
        for (String qualifier : List.of("ByIds", "ById", "By", "For")) {
            int at = s.indexOf(qualifier);
            if (at > 0) { s = s.substring(0, at); break; }
        }
        s = depluralise(s);
        return s.isBlank() ? null : s;
    }

    private String depluralise(String s) {
        if (s.endsWith("ies")) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("s") && !s.endsWith("ss")) return s.substring(0, s.length() - 1);
        return s;
    }

    // ─── Scoring ────────────────────────────────────────────────────────────────

    private ReplicaOpportunity score(String consumerService, ServiceStaticMetrics smConsumer,
                                     String source, String entity, InterServiceDependency edge,
                                     List<String> reads, RuntimeEvidence runtime,
                                     ConsistencyEvidence consistency) {

        AggregateMetrics rtConsumer = perService(runtime, consumerService);
        double readWriteRatio = rtConsumer != null ? rtConsumer.getReadWriteRatio() : 0.0;
        double compensationRate = consistency != null ? consistency.getAvgCompensationRate() : 0.0;

        // Latency and call-rate of the workflows that actually do the reading. Per-edge p95 does not
        // exist, but sagaMetrics and functionalityLocustMetrics are keyed by workflow/functionality
        // name, and Pass 4 records which of those touch this edge — so the join is exact.
        PathRuntime path = pathRuntime(runtime, edge.getFunctionalityNames());

        boolean syncEventAvailable = edge.getEventTypes() != null && !edge.getEventTypes().isEmpty();
        boolean replicaExists = replicaExists(smConsumer, source);
        boolean runtimeBacked = path.measured() || readWriteRatio > 0;

        // ── Factors ────────────────────────────────────────────────────────────
        double readIntensity  = Math.min(reads.size() / 3.0, 1.0);
        double crossCallCost  = Math.min(path.avgCrossServiceCalls() / 5.0, 1.0);
        double latencyCost    = Math.min(path.p95Ms() / 2000.0, 1.0);
        double readWriteSuit  = readWriteRatio > 0 ? readWriteRatio / (1.0 + readWriteRatio) : 0.5;
        double stalenessTol   = 1.0 - Math.min(compensationRate, 1.0);
        double invSafety      = invariantSafety(smConsumer, source);
        // Real feasibility, not a constant: an existing replica makes this an extension rather than
        // a new class, and an existing event removes the /new-event prerequisite the skill's case 5
        // would otherwise demand.
        double feasibility    = 0.4 + (syncEventAvailable ? 0.3 : 0.0) + (replicaExists ? 0.3 : 0.0);

        double weighted =
                  0.22 * readIntensity
                + 0.20 * crossCallCost
                + 0.18 * latencyCost
                + 0.12 * readWriteSuit
                + 0.12 * stalenessTol
                + 0.08 * invSafety
                + 0.08 * feasibility;

        double penalty = (invSafety < 0.3 ? 0.20 : 0.0) + (stalenessTol < 0.3 ? 0.15 : 0.0);
        double result = Math.max(weighted - penalty, 0.0);

        // Structurally real, never exercised: keep it, rank it last.
        if (!runtimeBacked) result = Math.min(result, UNMEASURED_CEILING);

        String stalenessWindow = invSafety < 0.5
                ? "1–2 seconds (invariant safety low — keep staleness minimal)"
                : "1–5 seconds (configurable)";

        return new ReplicaOpportunity(
                source, entity, reads,
                edge.getFunctionalityNames() == null ? List.of() : edge.getFunctionalityNames(),
                syncEventAvailable, replicaExists, runtimeBacked,
                round2(result), stalenessWindow);
    }

    /** Whether the consumer already holds a replica-shaped class naming this source. */
    private boolean replicaExists(ServiceStaticMetrics smConsumer, String source) {
        return smConsumer.getCrossServiceEntities() != null
                && !smConsumer.getCrossServiceEntities().getOrDefault(source, List.of()).isEmpty();
    }

    /**
     * How free the consumer's invariants are of the classes that reach this source.
     *
     * <p>An empty list returns a neutral 0.5, not 1.0. Absence of evidence is not perfect safety —
     * scoring it as such once made pairs with no relationship at all outrank genuinely coupled ones.
     */
    private double invariantSafety(ServiceStaticMetrics smConsumer, String source) {
        List<String> entities = smConsumer.getCrossServiceEntities() == null ? List.of()
                : smConsumer.getCrossServiceEntities().getOrDefault(source, List.of());
        List<InvariantInfo> invs = smConsumer.getInvariants() == null ? List.of()
                : smConsumer.getInvariants();
        if (entities.isEmpty() || invs.isEmpty()) return 0.5;

        double sum = 0.0;
        for (String entity : entities) {
            // Which invariants actually read this entity. This used to be a substring test on the
            // method name, which matches nothing: "invariantuniqueparticipant" does not contain
            // "tournamentparticipant". Every pair scored as perfectly safe.
            long affected = invs.stream()
                    .filter(inv -> inv.getMemberEntities() != null
                            && inv.getMemberEntities().contains(entity))
                    .count();
            sum += (double) (invs.size() - affected) / invs.size();
        }
        return sum / entities.size();
    }

    // ─── Runtime, attributed to the read path ───────────────────────────────────

    private record PathRuntime(double p95Ms, double avgCrossServiceCalls, boolean measured) {
        static PathRuntime none() { return new PathRuntime(0, 0, false); }
    }

    private PathRuntime pathRuntime(RuntimeEvidence runtime, List<String> functionalityNames) {
        if (runtime == null || functionalityNames == null || functionalityNames.isEmpty()) {
            return PathRuntime.none();
        }
        double p95 = 0.0, calls = 0.0;
        boolean measured = false;

        Map<String, SagaMetrics> sagas = runtime.getSagaMetrics();
        Map<String, LocustScenarioMetrics> locust = runtime.getFunctionalityLocustMetrics();

        for (String fn : functionalityNames) {
            if (sagas != null) {
                SagaMetrics sm = sagas.get(fn);
                if (sm != null) {
                    p95 = Math.max(p95, sm.getP95TotalLatencyMs());
                    calls = Math.max(calls, sm.getAvgCrossServiceCalls());
                    measured = true;
                }
            }
            if (locust != null) {
                LocustScenarioMetrics lm = locust.get(fn);
                if (lm != null) {
                    p95 = Math.max(p95, lm.getP95ResponseMs());
                    measured = true;
                }
            }
        }
        return measured ? new PathRuntime(p95, calls, true) : PathRuntime.none();
    }

    private static AggregateMetrics perService(RuntimeEvidence runtime, String name) {
        if (runtime == null || runtime.getPerServiceMetrics() == null) return null;
        AggregateMetrics am = runtime.getPerServiceMetrics().get(name);
        return am != null ? am : runtime.getPerServiceMetrics().get(name + "-service");
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
