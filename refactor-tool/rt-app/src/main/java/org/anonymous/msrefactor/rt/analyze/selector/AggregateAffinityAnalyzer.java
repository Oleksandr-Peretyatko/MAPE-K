package org.anonymous.msrefactor.rt.analyze.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.CompositionForest;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.Collection;
import java.util.Map;

/**
 * Selects the specific aggregate class pair for a MERGE_AGGREGATES candidate.
 *
 * <p>Mirrors the purpose of the per-entity selectors but targets
 * MERGE_AGGREGATES. Always returns a result (no rejection threshold): if structural data is
 * insufficient, it falls back to capitalized service names so the candidate always carries
 * concrete aggregate class names after deepening.
 *
 * <h3>Selection strategy</h3>
 * <ol>
 *   <li><b>Structural</b> — each side's aggregate is its own root, resolved with
 *       {@link CompositionForest#resolveRoot(Map, Collection)} from the composition graph and
 *       {@code rootNames}. A service's name does not give its root ({@code answer}'s is
 *       {@code QuizAnswer}), and no class in the analysed application is named {@code *Aggregate}.</li>
 *   <li><b>Runtime confirmation</b> — if p95 latency for either service is above 200 ms,
 *       confidence is bumped, reinforcing that the merge would reduce overhead.</li>
 * </ol>
 *
 * <p>There used to be a third step, a projected M1 delta computed as {@code combinedM1 × 0.83}. It
 * was the same fixed factor that {@code buildMergeMetricImpact} carried, so it could not tell one
 * merge from another, and it reached nothing: it fed only a {@code rationale} string that the sole
 * caller ({@code CandidateDeepener}) never read — it composes its own summary. Both are gone.
 */
@Slf4j
@Component
public class AggregateAffinityAnalyzer {

    /** Spring replaces this with the bean; the default keeps direct `new` usable in unit tests. */
    @Autowired(required = false)
    private AnalysisThresholdsConfig thresholds = new AnalysisThresholdsConfig();

    public record AggregateMergeCandidate(
            String aggregateClassA,
            String aggregateClassB,
            double confidence
    ) {}

    /**
     * Resolves the specific aggregate pair for a MERGE_AGGREGATES candidate.
     *
     * @param serviceA   primary hotspot service name
     * @param serviceB   secondary hotspot service name (coupling partner)
     * @param structural structural evidence (cross-service entity maps)
     * @param runtime    runtime evidence (p95 latency per service, may be null)
     * @return always non-null; falls back to capitalized service names if data is missing
     */
    public AggregateMergeCandidate select(
            String serviceA,
            String serviceB,
            StructuralEvidence structural,
            RuntimeEvidence runtime) {

        Map<String, ServiceStaticMetrics> perService =
                structural != null ? structural.getPerService() : null;

        // ── 1. Structural resolution ─────────────────────────────────────────────
        String aggregateA = resolveAggregate(serviceA, perService);
        String aggregateB = resolveAggregate(serviceB, perService);

        // ── 2. Runtime confirmation ──────────────────────────────────────────────
        boolean runtimeConfirmed = false;
        if (runtime != null && runtime.getPerServiceMetrics() != null) {
            AggregateMetrics rtA = runtime.getPerServiceMetrics().get(serviceA);
            AggregateMetrics rtB = runtime.getPerServiceMetrics().get(serviceB);
            double p95A = rtA != null ? rtA.getP95LatencyMs() : 0;
            double p95B = rtB != null ? rtB.getP95LatencyMs() : 0;
            runtimeConfirmed = p95A > thresholds.getHighAggregateLatencyMs() || p95B > thresholds.getHighAggregateLatencyMs();
        }

        // ── 3. Confidence ────────────────────────────────────────────────────────
        double baseConfidence = hasCrossServiceData(serviceA, serviceB, perService) ? 0.62 : 0.40;
        double confidence = runtimeConfirmed ? Math.min(baseConfidence + 0.15, 0.90) : baseConfidence;

        log.debug("AggregateAffinityAnalyzer: {}/{} → {}/{} (confidence={}, runtimeConfirmed={})",
                serviceA, serviceB, aggregateA, aggregateB, String.format("%.2f", confidence), runtimeConfirmed);

        return new AggregateMergeCandidate(aggregateA, aggregateB, confidence);
    }

    /**
     * The service's own aggregate root class. Falls back to the capitalised service name only when
     * the snapshot carries neither a composition graph nor {@code rootNames} — never to a
     * {@code *Aggregate} name, which no class in the analysed application has.
     */
    private String resolveAggregate(String service, Map<String, ServiceStaticMetrics> perService) {
        ServiceStaticMetrics sm = perService == null ? null : perService.get(service);
        if (sm != null) {
            String root = CompositionForest.resolveRoot(sm.getCompositions(), sm.getRootNames());
            if (root != null && !root.isBlank()) return root;
        }
        return cap(service);
    }

    private boolean hasCrossServiceData(String serviceA, String serviceB,
                                         Map<String, ServiceStaticMetrics> perService) {
        if (perService == null) return false;
        ServiceStaticMetrics smA = perService.get(serviceA);
        ServiceStaticMetrics smB = perService.get(serviceB);
        boolean aHasData = smA != null && smA.getCrossServiceEntities() != null
                && smA.getCrossServiceEntities().containsKey(serviceB);
        boolean bHasData = smB != null && smB.getCrossServiceEntities() != null
                && smB.getCrossServiceEntities().containsKey(serviceA);
        return aHasData || bHasData;
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
