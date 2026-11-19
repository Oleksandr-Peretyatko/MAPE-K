package org.anonymous.msrefactor.rt.analyze.deepener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.AggregateSnapshot;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.analyze.selector.AggregateAffinityAnalyzer;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Step 5.3 — Candidate deepening.
 *
 * <p>Receives service-level partial candidates from {@code CandidateGenerator} and resolves
 * the specific entity/aggregate pair for each operation type. After deepening, every candidate
 * carries a concrete, actionable summary instead of a placeholder.
 *
 * <h3>Per-operation behaviour</h3>
 * <ul>
 *   <li><b>MERGE_AGGREGATES</b> — calls {@link AggregateAffinityAnalyzer}. Updates
 *       {@code blueprint} with real aggregate class names.</li>
 *   <li><b>All other operations</b> — passed through unchanged. SPLIT_AGGREGATE and the replica
 *       operations are already resolved to concrete operands by their own selectors in
 *       {@code CandidateGenerator}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateDeepener {

    private final AggregateAffinityAnalyzer  aggregateAffinityAnalyzer;

    /**
     * Deepens a list of service-level partial candidates.
     *
     * @param candidates partial candidates from CandidateGenerator
     * @param structural structural evidence (per-service metrics, cross-service entities)
     * @param runtime    runtime evidence (p95 latency per service; may be null)
     * @param consistency consistency evidence (currently unused; kept for pipeline symmetry)
     * @param graph      discovery graph (currently unused; kept for pipeline symmetry)
     * @return the same candidates with updated summaries, blueprints, and selectionScores
     */
    public List<CandidateOperation> deepen(
            List<CandidateOperation> candidates,
            StructuralEvidence structural,
            RuntimeEvidence runtime,
            ConsistencyEvidence consistency,
            DiscoveryGraph graph) {

        if (candidates == null || candidates.isEmpty()) return candidates;

        List<CandidateOperation> result = new ArrayList<>(candidates.size());
        for (CandidateOperation c : candidates) {
            result.add(deepenOne(c, structural, runtime));
        }
        return result;
    }

    private CandidateOperation deepenOne(
            CandidateOperation c,
            StructuralEvidence structural,
            RuntimeEvidence runtime) {

        return switch (c.getOperation()) {
            case MERGE_AGGREGATES -> deepenMergeAggregates(c, structural, runtime);
            default               -> c;
        };
    }

    // ─── MERGE_AGGREGATES ────────────────────────────────────────────────────────

    private CandidateOperation deepenMergeAggregates(
            CandidateOperation c,
            StructuralEvidence structural,
            RuntimeEvidence runtime) {

        String serviceA = c.getTargetService();
        String serviceB = c.getSecondaryTarget();
        if (serviceB == null) return c;

        AggregateAffinityAnalyzer.AggregateMergeCandidate sel =
                aggregateAffinityAnalyzer.select(serviceA, serviceB, structural, runtime);

        String aggA = sel.aggregateClassA();
        String aggB = sel.aggregateClassB();
        // The merge dissolves B into A: the surviving root keeps its own name. Composing the two
        // ("TournamentUserAggregate") names a class that neither exists nor would be created.
        String mergedName = aggA;

        String resolvedSummary = "Merge " + aggB + " into " + aggA;

        RefactorBlueprint updatedBp = updateMergeBlueprint(c.getBlueprint(), aggA, aggB,
                mergedName, serviceA, serviceB);

        log.debug("CandidateDeepener MERGE_AGGREGATES resolved: {}", resolvedSummary);

        return c.toBuilder()
                .summary(resolvedSummary)
                .blueprint(updatedBp)
                .selectionScore(sel.confidence())
                .build();
    }

    private RefactorBlueprint updateMergeBlueprint(RefactorBlueprint bp,
                                                    String aggA, String aggB, String mergedName,
                                                    String serviceA, String serviceB) {
        if (bp == null) {
            return RefactorBlueprint.builder()
                    .summary("Merge " + aggB + " into " + aggA)
                    .before(List.of(
                            AggregateSnapshot.builder().aggregateName(aggA).serviceName(serviceA).build(),
                            AggregateSnapshot.builder().aggregateName(aggB).serviceName(serviceB).build()))
                    .after(List.of(
                            AggregateSnapshot.builder().aggregateName(mergedName).serviceName(serviceA)
                                    .build()))
                    .entityMoves(List.of())
                    .invariantChanges(List.of())
                    .build();
        }

        // Patch existing blueprint: update before/after aggregate names to resolved class names
        List<AggregateSnapshot> updatedBefore = patchAggregateNames(bp.getBefore(), serviceA, aggA, serviceB, aggB);
        List<AggregateSnapshot> updatedAfter  = patchMergedAggregateName(bp.getAfter(), mergedName, serviceA);

        return RefactorBlueprint.builder()
                .summary("Merge " + aggB + " into " + aggA)
                .before(updatedBefore)
                .after(updatedAfter)
                .entityMoves(bp.getEntityMoves())
                .invariantChanges(bp.getInvariantChanges())
                .implementationSteps(bp.getImplementationSteps())
                .metricImpact(bp.getMetricImpact())
                .build();
    }

    private List<AggregateSnapshot> patchAggregateNames(List<AggregateSnapshot> snapshots,
                                                          String serviceA, String aggA,
                                                          String serviceB, String aggB) {
        if (snapshots == null) return null;
        return snapshots.stream().map(s -> {
            if (serviceA.equals(s.getServiceName()))
                return AggregateSnapshot.builder()
                        .aggregateName(aggA).serviceName(s.getServiceName())
                        .entities(s.getEntities()).invariants(s.getInvariants()).isNew(s.isNew())
                        .build();
            if (serviceB.equals(s.getServiceName()))
                return AggregateSnapshot.builder()
                        .aggregateName(aggB).serviceName(s.getServiceName())
                        .entities(s.getEntities()).invariants(s.getInvariants()).isNew(s.isNew())
                        .build();
            return s;
        }).collect(Collectors.toList());
    }

    private List<AggregateSnapshot> patchMergedAggregateName(List<AggregateSnapshot> snapshots,
                                                               String mergedName, String serviceA) {
        if (snapshots == null) return null;
        return snapshots.stream().map(s -> AggregateSnapshot.builder()
                .aggregateName(mergedName).serviceName(serviceA)
                .entities(s.getEntities()).invariants(s.getInvariants()).isNew(true)
                .build()).collect(Collectors.toList());
    }

}
