package org.anonymous.msrefactor.rt.quality;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StructuralSignals;
import org.anonymous.msrefactor.rt.quality.QualityMetricThresholds.Threshold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.anonymous.msrefactor.rt.quality.QualityMetricThresholds.*;

/**
 * Computes current quality attribute scores from snapshot metrics.
 *
 * <p>Each calculation method accepts aggregated Jaeger, Locust, and static-analysis data
 * and returns a fully annotated {@link QualityAttributeScore} with per-signal breakdowns.
 * Missing metric groups are handled gracefully via weight rebalancing.
 *
 * <p>Each score also carries a per-entity breakdown ({@link QualityAttributeScore#getPerEntitySignals()})
 * showing how individual services (or Locust scenarios) contribute to each signal. This makes
 * the global aggregate transparent and directly traceable to root causes.
 */
@Component
@RequiredArgsConstructor
public class QualityAttributeCalculator {

    /** Threshold used for per-service entity count in per-entity breakdowns. */
    private static final Threshold ENTITY_COUNT_PER_SVC = new Threshold(3, 20);

    private final MetricNormalizer norm;

    // ─── Public entry point ───────────────────────────────────────────────────

    /**
     * @param sagaMetrics per-workflow SAGA metrics; feeds PERFORMANCE's workflow-latency signal.
     *                    Pass {@code Map.of()} when unavailable — the signal is then omitted and the
     *                    remaining weights renormalise.
     */
    public List<QualityAttributeScore> calculateAll(
            Map<String, AggregateMetrics> jaegerByService,
            List<LocustScenarioMetrics> locustScenarios,
            StaticMetrics staticMetrics,
            Map<String, SagaMetrics> sagaMetrics) {

        AggrJaeger jae = aggregate(jaegerByService);
        AggrLocust loc = aggregateLocust(locustScenarios);
        AggrSaga  sag  = aggregateSaga(sagaMetrics);
        // Fill the normalised signals on snapshots taken before the collector emitted them.
        staticMetrics = StructuralSignals.enrich(staticMetrics);

        List<QualityAttributeScore> scores = new ArrayList<>();
        scores.add(performance(jae, loc, sag, locustScenarios));
        scores.add(consistency(jae, loc, staticMetrics));
        scores.add(maintainability(jae, staticMetrics));
        scores.add(coupling(jae, staticMetrics));
        scores.add(cohesion(staticMetrics));
        scores.add(scalability(jae, loc, staticMetrics, locustScenarios));
        scores.add(autonomy(jae, staticMetrics));
        return scores;
    }

    // ─── PERFORMANCE ─────────────────────────────────────────────────────────

    QualityAttributeScore performance(AggrJaeger jae, AggrLocust loc, AggrSaga saga,
                                       List<LocustScenarioMetrics> rawLocust) {
        List<MetricSignal> signals = new ArrayList<>();

        boolean hasLocust = loc != null && loc.requestCount > 0;

        if (hasLocust) {
            signals.add(signal("locust_p95", "Locust p95 latency", loc.p95Ms,
                    norm.normalizeLowerIsBetter(loc.p95Ms, P95_MS), 0.30,
                    "lower_is_better", "locust", "p95 response time under load; lower is better."));
            signals.add(signal("locust_p99", "Locust p99 latency", loc.p99Ms,
                    norm.normalizeLowerIsBetter(loc.p99Ms, P99_MS), 0.15,
                    "lower_is_better", "locust", "Tail latency; high values signal outlier slowness."));
            signals.add(signal("locust_req_s", "Locust throughput (req/s)", loc.requestsPerSec,
                    norm.normalizeHigherIsBetter(loc.requestsPerSec, REQUESTS_PER_S), 0.20,
                    "higher_is_better", "locust", "Higher throughput means better runtime efficiency."));
            signals.add(signal("locust_error_rate", "Locust error rate", loc.errorRate,
                    norm.normalizeLowerIsBetter(loc.errorRate, ERROR_RATE), 0.15,
                    "lower_is_better", "locust", "Error fraction under load."));

            if (jae != null) {
                signals.add(selfLatencySignal(jae, 0.05));
                // The one runtime signal a merge moves directly: collapsing broker round-trips
                // shortens the workflow even when no step gets faster. Weighted to be visible.
                signals.add(workflowLatencySignal(saga, 0.15));
            }
        } else if (jae != null) {
            // Fallback: Jaeger-only
            signals.add(selfLatencySignal(jae, 0.35));
            signals.add(workflowLatencySignal(saga, 0.20));
            signals.add(signal("jaeger_ops_per_s", "Jaeger ops/s", jae.opsPerSec,
                    norm.normalizeHigherIsBetter(jae.opsPerSec, OPS_PER_S), 0.20,
                    "higher_is_better", "jaeger", "Operation frequency."));
            signals.add(signal("jaeger_error_rate", "Jaeger error rate", jae.errorRate,
                    norm.normalizeLowerIsBetter(jae.errorRate, ERROR_RATE), 0.15,
                    "lower_is_better", "jaeger", "Fraction of spans with error=true."));
            signals.add(signal("jaeger_bandwidth", "Jaeger bandwidth (B/s)", jae.bandwidthBps,
                    norm.normalizeLowerIsBetter(jae.bandwidthBps, BANDWIDTH_BPS), 0.10,
                    "lower_is_better", "jaeger", "High bandwidth increases infrastructure cost."));
        }

        // Per-entity breakdown
        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        String perEntityType;
        if (hasLocust && rawLocust != null) {
            perEntityType = "scenario";
            for (LocustScenarioMetrics s : rawLocust) {
                List<MetricSignal> row = new ArrayList<>();
                row.add(svcSignal("locust_p95", "Locust p95 latency", s.getP95ResponseMs(),
                        norm.normalizeLowerIsBetter(s.getP95ResponseMs(), P95_MS), 0.30, "lower_is_better", "locust"));
                row.add(svcSignal("locust_p99", "Locust p99 latency", s.getP99ResponseMs(),
                        norm.normalizeLowerIsBetter(s.getP99ResponseMs(), P99_MS), 0.20, "lower_is_better", "locust"));
                row.add(svcSignal("locust_req_s", "Locust throughput (req/s)", s.getRequestsPerSec(),
                        norm.normalizeHigherIsBetter(s.getRequestsPerSec(), REQUESTS_PER_S), 0.20, "higher_is_better", "locust"));
                row.add(svcSignal("locust_error_rate", "Locust error rate", s.getErrorRate(),
                        norm.normalizeLowerIsBetter(s.getErrorRate(), ERROR_RATE), 0.15, "lower_is_better", "locust"));
                perEntity.put(scenarioName(s), row);
            }
        } else if (jae != null) {
            perEntityType = "service";
            for (Map.Entry<String, AggregateMetrics> e : jae.byService.entrySet()) {
                AggregateMetrics am = e.getValue();
                List<MetricSignal> row = new ArrayList<>();
                // Same weights PerServiceQaAnalyzer uses; the two must agree, or the score shown for
                // a service and the deficit used to rank it would describe different things.
                row.add(am.getMeasuredSpanCount() > 0
                        ? svcSignal("jaeger_self_p95", "Service self p95", am.getP95LatencyMs(),
                                norm.normalizeLowerIsBetter(am.getP95LatencyMs(), SELF_P95_MS),
                                0.40, "lower_is_better", "jaeger")
                        : signalMissing("jaeger_self_p95", "Service self p95", 0.40,
                                "lower_is_better", "jaeger", "No work spans for this service."));
                row.add(svcSignal("jaeger_ops_per_s", "Jaeger ops/s", am.getOperationFrequency() / 60.0,
                        norm.normalizeHigherIsBetter(am.getOperationFrequency() / 60.0, OPS_PER_S), 0.25, "higher_is_better", "jaeger"));
                row.add(svcSignal("jaeger_error_rate", "Jaeger error rate", am.getErrorRate(),
                        norm.normalizeLowerIsBetter(am.getErrorRate(), ERROR_RATE), 0.20, "lower_is_better", "jaeger"));
                row.add(svcSignal("jaeger_bandwidth", "Jaeger bandwidth (B/s)", am.getBandwidthBytesPerSec(),
                        norm.normalizeLowerIsBetter(am.getBandwidthBytesPerSec(), BANDWIDTH_BPS), 0.15, "lower_is_better", "jaeger"));
                perEntity.put(e.getKey(), row);
            }
        } else {
            perEntityType = null;
        }

        return build(QualityAttribute.PERFORMANCE, signals,
                FormulaExplanation.builder()
                        .shortDescription("Runtime efficiency based on service self time, workflow latency, throughput, errors, and bandwidth.")
                        .formula(hasLocust
                                ? "0.30 × inv_norm(Locust p95) + 0.15 × inv_norm(Locust p99) + 0.20 × norm(Req/s) + 0.15 × inv_norm(Error rate) + 0.05 × inv_norm(Service self p95) + 0.15 × inv_norm(Workflow p95)"
                                : "0.35 × inv_norm(Service self p95) + 0.20 × inv_norm(Workflow p95) + 0.20 × norm(Ops/s) + 0.15 × inv_norm(Error rate) + 0.10 × inv_norm(Bandwidth) [Locust fallback]")
                        .interpretation("Higher score is better. Service self time excludes downstream waits, so a slow dependency is charged to the dependency, not the caller. Signals that could not be measured are dropped and the remaining weights renormalised.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, perEntityType);
    }

    /**
     * Per-service self-time p95: how long services spend on their own work, excluding time blocked
     * downstream. Emitted as a missing signal when no work span was observed — the previous
     * behaviour reported the span duration of {@code TraceManager}'s scaffolding, which is workflow
     * duration and clamped the score to 0.0 regardless of how the system actually performed.
     */
    private MetricSignal selfLatencySignal(AggrJaeger jae, double weight) {
        String desc = "p95 of per-service self time (span duration minus downstream waits).";
        if (jae.p95Ms() == null) {
            return signalMissing("jaeger_self_p95", "Service self p95", weight,
                    "lower_is_better", "jaeger",
                    desc + " No work spans in this window, so latency was not measured.");
        }
        return signal("jaeger_self_p95", "Service self p95", jae.p95Ms(),
                norm.normalizeLowerIsBetter(jae.p95Ms(), SELF_P95_MS), weight,
                "lower_is_better", "jaeger", desc);
    }

    /**
     * Worst SAGA workflow p95. This is the term a MergeAggregates moves: collapsing broker
     * round-trips shortens the workflow even when no individual step gets faster, so without it the
     * score would be blind to the improvement the loop exists to produce.
     */
    private MetricSignal workflowLatencySignal(AggrSaga saga, double weight) {
        String desc = "p95 end-to-end latency of the slowest SAGA workflow.";
        if (saga == null || saga.p95Ms() == null) {
            return signalMissing("saga_workflow_p95", "Workflow p95", weight,
                    "lower_is_better", "jaeger",
                    desc + " No workflow spans in this window.");
        }
        return signal("saga_workflow_p95", "Workflow p95", saga.p95Ms(),
                norm.normalizeLowerIsBetter(saga.p95Ms(), SAGA_P95_MS), weight,
                "lower_is_better", "jaeger", desc);
    }

    // ─── CONSISTENCY ─────────────────────────────────────────────────────────
    // Static signals are the normalised ones from StructuralSignals; M1/M2 stay reported on the
    // snapshot but are not scored (see QualityMetricThresholds.M2).

    QualityAttributeScore consistency(AggrJaeger jae, AggrLocust loc, StaticMetrics sm) {
        List<MetricSignal> signals = new ArrayList<>();

        signals.add(ratioSignal("replica_density", "Replica density", sm != null ? sm.getReplicaDensity() : null,
                REPLICA_DENSITY, 0.30, "static",
                "Replicated copies per entity. Every copy is state that can go stale across services."));
        signals.add(ratioSignal("event_edge_density", "Event-edge density", sm != null ? sm.getEventEdgeDensity() : null,
                EVENT_EDGE_DENSITY, 0.25, "static",
                "Cross-service event subscriptions per entity: rules kept true only eventually."));
        signals.add(ratioSignal("avg_distributed_steps", "Distributed transaction length",
                sm != null ? sm.getAvgDistributedSteps() : null, DISTRIBUTED_STEPS, 0.20, "static",
                "Mean steps of the workflows that cross a service boundary; each step is a compensation point."));
        signals.add(unitSignal("functional_cohesion", "Functional cohesion", sm != null ? sm.getFunctionalCohesion() : null,
                0.15, "static", "Entities used together inside one aggregate are kept consistent by one transaction."));

        double writeDep;
        boolean writeDerived = false;
        if (jae != null && (jae.avgReadMs + jae.avgWriteMs) > 0) {
            writeDep = jae.avgWriteMs / (jae.avgReadMs + jae.avgWriteMs);
        } else {
            writeDep = 0.5;
            writeDerived = true;
        }
        signals.add(signalEstimated("write_dep_ratio", "Write dependency ratio", writeDep,
                norm.normalizeLowerIsBetter(writeDep, WRITE_DEP_RATIO), 0.10,
                "lower_is_better", "derived",
                "Approximated from avgWrite / (avgRead + avgWrite). High write ratio increases consistency pressure.",
                writeDerived));

        // Per-service breakdown
        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        for (String svc : serviceNames(jae, sm)) {
            ServiceStaticMetrics ssm = sm != null && sm.getServices() != null ? sm.getServices().get(svc) : null;
            AggregateMetrics am = jae != null ? jae.byService.get(svc) : null;
            Double svcWriteDep = null;
            if (am != null && (am.getAvgReadLatencyMs() + am.getAvgWriteLatencyMs()) > 0) {
                svcWriteDep = am.getAvgWriteLatencyMs() / (am.getAvgReadLatencyMs() + am.getAvgWriteLatencyMs());
            }

            List<MetricSignal> row = new ArrayList<>();
            row.add(svcRatio("replica_density", "Replica density", ssm != null ? ssm.getReplicaDensity() : null, REPLICA_DENSITY, 0.30));
            row.add(svcRatio("event_edge_density", "Event-edge density", ssm != null ? ssm.getEventEdgeDensity() : null, EVENT_EDGE_DENSITY, 0.25));
            row.add(svcRatio("avg_distributed_steps", "Distributed transaction length", ssm != null ? ssm.getAvgDistributedSteps() : null, DISTRIBUTED_STEPS, 0.20));
            row.add(svcUnit("functional_cohesion", "Functional cohesion", ssm != null ? ssm.getFunctionalCohesion() : null, 0.15));
            row.add(svcSignal("write_dep_ratio", "Write dependency ratio", svcWriteDep,
                    svcWriteDep != null ? norm.normalizeLowerIsBetter(svcWriteDep, WRITE_DEP_RATIO) : null,
                    0.10, "lower_is_better", "derived"));
            perEntity.put(svc, row);
        }

        return build(QualityAttribute.CONSISTENCY, signals,
                FormulaExplanation.builder()
                        .shortDescription("Likelihood of preserving business rules and avoiding stale or conflicting state.")
                        .formula("0.30 × inv_norm(replica_density) + 0.25 × inv_norm(event_edge_density) + 0.20 × inv_norm(avg_distributed_steps) + 0.15 × functional_cohesion + 0.10 × inv_norm(write_dep_ratio)")
                        .interpretation("Higher score means the architecture is more consistent. Replicas and event-maintained rules are the state that can drift; a merge removes both for the pair it joins.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, "service");
    }

    // ─── MAINTAINABILITY ─────────────────────────────────────────────────────

    QualityAttributeScore maintainability(AggrJaeger jae, StaticMetrics sm) {
        List<MetricSignal> signals = new ArrayList<>();

        signals.add(unitSignal("functional_cohesion", "Functional cohesion", sm != null ? sm.getFunctionalCohesion() : null,
                0.30, "static", "Aggregates whose entities are used together are understood and changed together."));
        signals.add(unitSignal("structural_cohesion", "Structural cohesion", sm != null ? sm.getStructuralCohesion() : null,
                0.20, "static", "Tree-normalised M1: how completely the aggregate's entities are wired to their root."));
        signals.add(unitSignalInverted("coupling_density", "Coupling density", sm != null ? sm.getCouplingDensity() : null,
                0.20, "static", "Cross-service references over the most the system could hold; lower simplifies change propagation."));
        double imbalance = sm != null ? entityImbalance(sm) : 0.0;
        signals.add(signal("entity_imbalance", "Entity size imbalance", imbalance,
                norm.normalizeLowerIsBetter(imbalance, ENTITY_IMBALANCE), 0.20,
                "lower_is_better", "derived",
                "stddev/avg of entity counts per service. High imbalance makes services hard to reason about."));
        signals.add(jae != null
                ? signal("error_rate", "Error rate", jae.errorRate,
                        norm.normalizeLowerIsBetter(jae.errorRate, ERROR_RATE), 0.10,
                        "lower_is_better", "jaeger", "High error rates suggest brittle coupling.")
                : signalMissing("error_rate", "Error rate", 0.10, "lower_is_better", "jaeger", "No Jaeger data."));

        // Per-service breakdown (entity_imbalance is system-level; use entity_count per service)
        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        for (String svc : serviceNames(jae, sm)) {
            ServiceStaticMetrics ssm = sm != null && sm.getServices() != null ? sm.getServices().get(svc) : null;
            AggregateMetrics am = jae != null ? jae.byService.get(svc) : null;
            int svcEntityCount = ssm != null ? ssm.getEntityCount() : 0;
            Double svcError = am != null ? am.getErrorRate() : null;

            List<MetricSignal> row = new ArrayList<>();
            row.add(svcUnit("functional_cohesion", "Functional cohesion", ssm != null ? ssm.getFunctionalCohesion() : null, 0.30));
            row.add(svcUnit("structural_cohesion", "Structural cohesion", ssm != null ? ssm.getStructuralCohesion() : null, 0.20));
            row.add(svcUnitInverted("coupling_density", "Coupling density", ssm != null ? ssm.getCouplingDensity() : null, 0.20));
            row.add(svcSignal("entity_count", "Entity count", (double) svcEntityCount,
                    norm.normalizeLowerIsBetter(svcEntityCount, ENTITY_COUNT_PER_SVC), 0.20, "lower_is_better", "static"));
            row.add(svcSignal("error_rate", "Error rate", svcError,
                    svcError != null ? norm.normalizeLowerIsBetter(svcError, ERROR_RATE) : null,
                    0.10, "lower_is_better", "jaeger"));
            perEntity.put(svc, row);
        }

        return build(QualityAttribute.MAINTAINABILITY, signals,
                FormulaExplanation.builder()
                        .shortDescription("Ease of understanding, evolving, and safely modifying the architecture.")
                        .formula("0.30 × functional_cohesion + 0.20 × structural_cohesion + 0.20 × inv_norm(coupling_density) + 0.20 × inv_norm(entity_imbalance) + 0.10 × inv_norm(error_rate)")
                        .interpretation("Higher score means the architecture is easier to maintain. Low functional cohesion and high coupling density are the primary risk factors.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, "service");
    }

    // ─── COUPLING ────────────────────────────────────────────────────────────

    QualityAttributeScore coupling(AggrJaeger jae, StaticMetrics sm) {
        List<MetricSignal> signals = new ArrayList<>();

        signals.add(unitSignalInverted("coupling_density", "Coupling density", sm != null ? sm.getCouplingDensity() : null,
                0.35, "static", "Σσ / ((t'−1)·ΣN): cross-service references over the most the system could hold. Replaces M2, whose pair average shrinks when services merge."));
        signals.add(unitSignal("local_step_ratio", "Local step ratio", sm != null ? sm.getLocalStepRatio() : null,
                0.25, "static", "Fraction of workflow steps that send no command to another service. A merge turns cross steps local; a split does the opposite."));
        signals.add(ratioSignal("replica_density", "Replica density", sm != null ? sm.getReplicaDensity() : null,
                REPLICA_DENSITY, 0.20, "static", "Replicated copies per entity: each is a structural dependency on another service."));
        signals.add(ratioSignal("event_edge_density", "Event-edge density", sm != null ? sm.getEventEdgeDensity() : null,
                EVENT_EDGE_DENSITY, 0.10, "static", "Cross-service event subscriptions per entity: behavioural coupling."));
        signals.add(jae != null
                ? signal("inter_svc_bandwidth", "Inter-service bandwidth (B/s)", jae.bandwidthBps,
                        norm.normalizeLowerIsBetter(jae.bandwidthBps, BANDWIDTH_BPS), 0.10,
                        "lower_is_better", "jaeger", "High bandwidth implies high data dependency between services.")
                : signalMissing("inter_svc_bandwidth", "Inter-service bandwidth (B/s)", 0.10,
                        "lower_is_better", "jaeger", "No Jaeger data."));

        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        for (String svc : serviceNames(jae, sm)) {
            ServiceStaticMetrics ssm = sm != null && sm.getServices() != null ? sm.getServices().get(svc) : null;
            AggregateMetrics am = jae != null ? jae.byService.get(svc) : null;
            Double svcBw = am != null ? am.getBandwidthBytesPerSec() : null;

            List<MetricSignal> row = new ArrayList<>();
            row.add(svcUnitInverted("coupling_density", "Coupling density", ssm != null ? ssm.getCouplingDensity() : null, 0.35));
            row.add(svcUnit("local_step_ratio", "Local step ratio", ssm != null ? ssm.getLocalStepRatio() : null, 0.25));
            row.add(svcRatio("replica_density", "Replica density", ssm != null ? ssm.getReplicaDensity() : null, REPLICA_DENSITY, 0.20));
            row.add(svcRatio("event_edge_density", "Event-edge density", ssm != null ? ssm.getEventEdgeDensity() : null, EVENT_EDGE_DENSITY, 0.10));
            row.add(svcSignal("inter_svc_bandwidth", "Bandwidth (B/s)", svcBw,
                    svcBw != null ? norm.normalizeLowerIsBetter(svcBw, BANDWIDTH_BPS) : null,
                    0.10, "lower_is_better", "jaeger"));
            perEntity.put(svc, row);
        }

        return build(QualityAttribute.COUPLING, signals,
                FormulaExplanation.builder()
                        .shortDescription("Degree of inter-service coupling (high score = low coupling).")
                        .formula("0.35 × inv_norm(coupling_density) + 0.25 × local_step_ratio + 0.20 × inv_norm(replica_density) + 0.10 × inv_norm(event_edge_density) + 0.10 × inv_norm(bandwidth)")
                        .interpretation("Higher score means services are more independent. Coupling density and the share of workflow steps that cross a boundary are the primary signals.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, "service");
    }

    // ─── COHESION ────────────────────────────────────────────────────────────

    QualityAttributeScore cohesion(StaticMetrics sm) {
        List<MetricSignal> signals = new ArrayList<>();

        double imbalance = sm != null ? entityImbalance(sm) : 0.0;
        double fragmentRatio = sm != null ? fragmentRatio(sm) : 0.0;

        signals.add(unitSignal("functional_cohesion", "Functional cohesion", sm != null ? sm.getFunctionalCohesion() : null,
                0.45, "static", "Mean fraction of an aggregate's entities that each functionality touching it uses. 1 when every workflow needs the whole aggregate."));
        signals.add(unitSignal("structural_cohesion", "Structural cohesion", sm != null ? sm.getStructuralCohesion() : null,
                0.25, "static", "Tree-normalised M1 (μ / 2(N−1)): 1 for a complete composition tree with back-references; a single entity is 1."));
        signals.add(unitSignal("cluster_factor", "Cluster factor", sm != null ? sm.getClusterFactor() : null,
                0.15, "static", "TurboMQ 2μ / (2μ + σ): internal relations against the references that cross the boundary."));
        signals.add(signal("entity_imbalance", "Entity size imbalance", imbalance,
                norm.normalizeLowerIsBetter(imbalance, ENTITY_IMBALANCE), 0.10,
                "lower_is_better", "derived", "Highly uneven service sizes may hide over-fragmentation."));
        signals.add(signal("fragment_ratio", "Unused single-entity service ratio", fragmentRatio,
                norm.normalizeLowerIsBetter(fragmentRatio, SINGLE_ENTITY_SVC_RATIO), 0.05,
                "lower_is_better", "derived",
                "Single-entity services that no functionality reaches on their own: fragments, not aggregates."));

        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        if (sm != null && sm.getServices() != null) {
            for (Map.Entry<String, ServiceStaticMetrics> e : sm.getServices().entrySet()) {
                ServiceStaticMetrics ssm = e.getValue();
                int svcEntityCount = ssm.getEntityCount();
                double fragment = isFragment(ssm) ? 1.0 : 0.0;

                List<MetricSignal> row = new ArrayList<>();
                row.add(svcUnit("functional_cohesion", "Functional cohesion", ssm.getFunctionalCohesion(), 0.45));
                row.add(svcUnit("structural_cohesion", "Structural cohesion", ssm.getStructuralCohesion(), 0.25));
                row.add(svcUnit("cluster_factor", "Cluster factor", ssm.getClusterFactor(), 0.15));
                row.add(svcSignal("entity_count", "Entity count", (double) svcEntityCount,
                        norm.normalizeLowerIsBetter(svcEntityCount, ENTITY_COUNT_PER_SVC), 0.10, "lower_is_better", "static"));
                row.add(svcSignal("fragment", "Unused single-entity service", fragment,
                        1.0 - fragment, 0.05, "lower_is_better", "derived"));
                perEntity.put(e.getKey(), row);
            }
        }

        return build(QualityAttribute.COHESION, signals,
                FormulaExplanation.builder()
                        .shortDescription("Whether entities that belong together are grouped together.")
                        .formula("0.45 × functional_cohesion + 0.25 × structural_cohesion + 0.15 × cluster_factor + 0.10 × inv_norm(entity_imbalance) + 0.05 × inv_norm(fragment_ratio)")
                        .interpretation("Higher score means better cohesion. Functional cohesion is what a merge or split moves: joining entities that workflows use together raises it, joining ones they do not lowers it. Raw M1 is reported on the snapshot but not scored: its N² denominator cannot reach 1 for a tree-shaped aggregate.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, "service");
    }

    // ─── SCALABILITY ─────────────────────────────────────────────────────────

    QualityAttributeScore scalability(AggrJaeger jae, AggrLocust loc, StaticMetrics sm,
                                       List<LocustScenarioMetrics> rawLocust) {
        List<MetricSignal> signals = new ArrayList<>();

        boolean hasLocust = loc != null && loc.requestCount > 0;
        Double couplingDensity = sm != null ? sm.getCouplingDensity() : null;

        if (hasLocust) {
            signals.add(signal("locust_req_s", "Locust throughput (req/s)", loc.requestsPerSec,
                    norm.normalizeHigherIsBetter(loc.requestsPerSec, REQUESTS_PER_S), 0.30,
                    "higher_is_better", "locust", "High throughput under load indicates scalable architecture."));
            signals.add(signal("locust_p95_load", "Locust p95 under load", loc.p95Ms,
                    norm.normalizeLowerIsBetter(loc.p95Ms, P95_MS), 0.25,
                    "lower_is_better", "locust", "Low p95 under load means the system scales gracefully."));
            signals.add(signal("locust_error_rate", "Locust error rate", loc.errorRate,
                    norm.normalizeLowerIsBetter(loc.errorRate, ERROR_RATE), 0.20,
                    "lower_is_better", "locust", "High errors under load signal scalability limits."));
        }

        double hotspot = jae != null ? hotspotLoad(jae) : 0.5;
        boolean hotspotDerived = jae == null;
        signals.add(signalEstimated("hotspot_load", "Hotspot load concentration", hotspot,
                norm.normalizeLowerIsBetter(hotspot, HOTSPOT_LOAD), hasLocust ? 0.15 : 0.45,
                "lower_is_better", "derived",
                "max(ops/s) / sum(ops/s). High concentration means one service limits total scalability.",
                hotspotDerived));

        signals.add(unitSignalInverted("coupling_density", "Coupling density", couplingDensity,
                hasLocust ? 0.10 : 0.55, "static", "Highly coupled services cannot scale independently."));

        // Per-entity breakdown
        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        String perEntityType;
        if (hasLocust && rawLocust != null) {
            perEntityType = "scenario";
            for (LocustScenarioMetrics s : rawLocust) {
                List<MetricSignal> row = new ArrayList<>();
                row.add(svcSignal("locust_req_s", "Locust throughput (req/s)", s.getRequestsPerSec(),
                        norm.normalizeHigherIsBetter(s.getRequestsPerSec(), REQUESTS_PER_S), 0.30, "higher_is_better", "locust"));
                row.add(svcSignal("locust_p95_load", "Locust p95 under load", s.getP95ResponseMs(),
                        norm.normalizeLowerIsBetter(s.getP95ResponseMs(), P95_MS), 0.25, "lower_is_better", "locust"));
                row.add(svcSignal("locust_error_rate", "Locust error rate", s.getErrorRate(),
                        norm.normalizeLowerIsBetter(s.getErrorRate(), ERROR_RATE), 0.20, "lower_is_better", "locust"));
                perEntity.put(scenarioName(s), row);
            }
        } else if (jae != null) {
            perEntityType = "service";
            double totalOps = jae.opsPerSec;
            for (String svc : serviceNames(jae, sm)) {
                AggregateMetrics am = jae.byService.get(svc);
                ServiceStaticMetrics ssm = sm != null && sm.getServices() != null ? sm.getServices().get(svc) : null;
                // null when the service produced no work span: a 0 ms p95 would normalise to a
                // perfect score, so unmeasured latency must drop out rather than flatter the service.
                Double svcP95 = am != null && am.getMeasuredSpanCount() > 0 ? am.getP95LatencyMs() : null;
                Double svcErr = am != null ? am.getErrorRate() : null;
                double svcOps = am != null ? am.getOperationFrequency() / 60.0 : 0.0;
                double svcLoad = totalOps > 0 ? norm.clamp01(svcOps / totalOps) : 0.0;
                Double svcCoupling = ssm != null ? ssm.getCouplingDensity() : null;

                List<MetricSignal> row = new ArrayList<>();
                row.add(svcSignal("jaeger_self_p95", "Service self p95", svcP95,
                        svcP95 != null ? norm.normalizeLowerIsBetter(svcP95, SELF_P95_MS) : null,
                        0.30, "lower_is_better", "jaeger"));
                row.add(svcSignal("jaeger_error_rate", "Jaeger error rate", svcErr,
                        svcErr != null ? norm.normalizeLowerIsBetter(svcErr, ERROR_RATE) : null,
                        0.20, "lower_is_better", "jaeger"));
                row.add(svcSignal("hotspot_load", "Hotspot load", svcLoad,
                        norm.normalizeLowerIsBetter(svcLoad, HOTSPOT_LOAD), 0.25, "lower_is_better", "derived"));
                row.add(svcUnitInverted("coupling_density", "Coupling density", svcCoupling, 0.25));
                perEntity.put(svc, row);
            }
        } else {
            perEntityType = null;
        }

        return build(QualityAttribute.SCALABILITY, signals,
                FormulaExplanation.builder()
                        .shortDescription("Ability to handle increasing load without latency and error degradation.")
                        .formula("0.30 × norm(Req/s) + 0.25 × inv_norm(p95 under load) + 0.20 × inv_norm(error_rate) + 0.15 × inv_norm(hotspot_load) + 0.10 × inv_norm(coupling_density)")
                        .interpretation("Higher score means the system scales better. Load concentration on a single service is the main bottleneck signal.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, perEntityType);
    }

    // ─── AUTONOMY ────────────────────────────────────────────────────────────

    QualityAttributeScore autonomy(AggrJaeger jae, StaticMetrics sm) {
        List<MetricSignal> signals = new ArrayList<>();

        signals.add(unitSignal("local_step_ratio", "Local step ratio", sm != null ? sm.getLocalStepRatio() : null,
                0.30, "static", "Fraction of workflow steps that complete without another service: what can run, deploy and fail on its own."));
        signals.add(unitSignalInverted("coupling_density", "Coupling density", sm != null ? sm.getCouplingDensity() : null,
                0.25, "static", "Cross-service references over the most the system could hold."));
        signals.add(ratioSignal("replica_density", "Replica density", sm != null ? sm.getReplicaDensity() : null,
                REPLICA_DENSITY, 0.20, "static", "Replicated copies per entity: data another service owns and must keep sending."));
        signals.add(ratioSignal("event_edge_density", "Event-edge density", sm != null ? sm.getEventEdgeDensity() : null,
                EVENT_EDGE_DENSITY, 0.15, "static", "Event subscriptions per entity: lifecycles this service reacts to."));
        signals.add(jae != null
                ? signal("inter_svc_bandwidth", "Inter-service bandwidth (B/s)", jae.bandwidthBps,
                        norm.normalizeLowerIsBetter(jae.bandwidthBps, BANDWIDTH_BPS), 0.10,
                        "lower_is_better", "jaeger", "High bandwidth signals strong runtime dependency between services.")
                : signalMissing("inter_svc_bandwidth", "Inter-service bandwidth (B/s)", 0.10,
                        "lower_is_better", "jaeger", "No Jaeger data."));

        Map<String, List<MetricSignal>> perEntity = new LinkedHashMap<>();
        for (String svc : serviceNames(jae, sm)) {
            ServiceStaticMetrics ssm = sm != null && sm.getServices() != null ? sm.getServices().get(svc) : null;
            AggregateMetrics am = jae != null ? jae.byService.get(svc) : null;
            Double svcBw = am != null ? am.getBandwidthBytesPerSec() : null;

            List<MetricSignal> row = new ArrayList<>();
            row.add(svcUnit("local_step_ratio", "Local step ratio", ssm != null ? ssm.getLocalStepRatio() : null, 0.30));
            row.add(svcUnitInverted("coupling_density", "Coupling density", ssm != null ? ssm.getCouplingDensity() : null, 0.25));
            row.add(svcRatio("replica_density", "Replica density", ssm != null ? ssm.getReplicaDensity() : null, REPLICA_DENSITY, 0.20));
            row.add(svcRatio("event_edge_density", "Event-edge density", ssm != null ? ssm.getEventEdgeDensity() : null, EVENT_EDGE_DENSITY, 0.15));
            row.add(svcSignal("inter_svc_bandwidth", "Bandwidth (B/s)", svcBw,
                    svcBw != null ? norm.normalizeLowerIsBetter(svcBw, BANDWIDTH_BPS) : null,
                    0.10, "lower_is_better", "jaeger"));
            perEntity.put(svc, row);
        }

        return build(QualityAttribute.AUTONOMY, signals,
                FormulaExplanation.builder()
                        .shortDescription("Degree to which services can evolve, deploy, scale, and operate independently.")
                        .formula("0.30 × local_step_ratio + 0.25 × inv_norm(coupling_density) + 0.20 × inv_norm(replica_density) + 0.15 × inv_norm(event_edge_density) + 0.10 × inv_norm(bandwidth)")
                        .interpretation("Higher score means services are more autonomous. Workflow steps that leave the service and the references it holds on others are the main risks.")
                        .build(),
                perEntity.isEmpty() ? null : perEntity, "service");
    }

    // ─── Aggregation helpers ──────────────────────────────────────────────────

    /** Aggregate Jaeger service metrics to system-level summary. */
    AggrJaeger aggregate(Map<String, AggregateMetrics> byService) {
        if (byService == null || byService.isEmpty()) return null;
        Collection<AggregateMetrics> all = byService.values();
        // Only services that actually measured something may contribute to the latency aggregate;
        // a service with no work spans reports 0 ms, which would drag the max() the wrong way.
        Double p95 = all.stream()
                .filter(m -> m.getMeasuredSpanCount() > 0)
                .mapToDouble(AggregateMetrics::getP95LatencyMs)
                .max().stream().boxed().findFirst().orElse(null);
        double avgR = all.stream().mapToDouble(AggregateMetrics::getAvgReadLatencyMs).average().orElse(0);
        double avgW = all.stream().mapToDouble(AggregateMetrics::getAvgWriteLatencyMs).average().orElse(0);
        double bw   = all.stream().mapToDouble(AggregateMetrics::getBandwidthBytesPerSec).sum();
        double err  = all.stream().mapToDouble(AggregateMetrics::getErrorRate).average().orElse(0);
        double ops  = all.stream().mapToDouble(m -> m.getOperationFrequency() / 60.0).sum();
        return new AggrJaeger(p95, avgR, avgW, bw, err, ops, byService);
    }

    /** Aggregate SAGA workflow metrics; the worst workflow drives the signal. */
    AggrSaga aggregateSaga(Map<String, SagaMetrics> byWorkflow) {
        if (byWorkflow == null || byWorkflow.isEmpty()) return null;
        Double p95 = byWorkflow.values().stream()
                .mapToDouble(SagaMetrics::getP95TotalLatencyMs)
                .max().stream().boxed().findFirst().orElse(null);
        return new AggrSaga(p95);
    }

    /** Aggregate Locust scenarios to system-level summary (weighted by requestCount). */
    AggrLocust aggregateLocust(List<LocustScenarioMetrics> scenarios) {
        if (scenarios == null || scenarios.isEmpty()) return null;
        long total = scenarios.stream().mapToLong(LocustScenarioMetrics::getRequestCount).sum();
        if (total == 0) return null;
        double p95 = wavg(scenarios, LocustScenarioMetrics::getP95ResponseMs, total);
        double p99 = wavg(scenarios, LocustScenarioMetrics::getP99ResponseMs, total);
        double rps = scenarios.stream().mapToDouble(LocustScenarioMetrics::getRequestsPerSec).sum();
        double err = wavg(scenarios, LocustScenarioMetrics::getErrorRate, total);
        return new AggrLocust(p95, p99, rps, err, (int) total);
    }

    @FunctionalInterface interface Extractor { double get(LocustScenarioMetrics m); }

    private double wavg(List<LocustScenarioMetrics> list, Extractor fn, long total) {
        return list.stream().mapToDouble(m -> fn.get(m) * m.getRequestCount()).sum() / total;
    }

    // ─── Derived metric helpers ───────────────────────────────────────────────

    double entityImbalance(StaticMetrics sm) {
        if (sm.getServices() == null) return 0.0;
        // Directories without entities are not services and must not widen the spread.
        double[] counts = sm.getServices().values().stream()
                .mapToDouble(ServiceStaticMetrics::getEntityCount).filter(c -> c > 0).toArray();
        if (counts.length < 2) return 0.0;
        double avg = 0;
        for (double c : counts) avg += c;
        avg /= counts.length;
        if (avg == 0) return 0.0;
        double variance = 0;
        for (double c : counts) variance += (c - avg) * (c - avg);
        return Math.sqrt(variance / counts.length) / avg;
    }

    double hotspotLoad(AggrJaeger jae) {
        if (jae.byService == null || jae.byService.isEmpty()) return 0.5;
        double max = jae.byService.values().stream()
                .mapToDouble(m -> m.getOperationFrequency() / 60.0).max().orElse(0);
        double sum = jae.opsPerSec;
        return sum == 0 ? 0.5 : norm.clamp01(max / sum);
    }

    /** Union of service names from Jaeger and static analysis maps, preserving insertion order. */
    private Set<String> serviceNames(AggrJaeger jae, StaticMetrics sm) {
        Set<String> names = new LinkedHashSet<>();
        if (jae != null && jae.byService != null) names.addAll(jae.byService.keySet());
        if (sm != null && sm.getServices() != null) names.addAll(sm.getServices().keySet());
        return names;
    }

    /** Derives a display name for a Locust scenario. Falls back to sourceFile if name is blank. */
    private String scenarioName(LocustScenarioMetrics s) {
        return (s.getScenarioName() != null && !s.getScenarioName().isBlank())
                ? s.getScenarioName() : s.getSourceFile();
    }

    /**
     * Services of one entity that no functionality reaches: a fragment rather than an aggregate.
     * With no functionality data at all, every single-entity service counts, as before.
     */
    double fragmentRatio(StaticMetrics sm) {
        if (sm.getServices() == null) return 0.0;
        long real = sm.getServices().values().stream().filter(s -> s.getEntityCount() > 0).count();
        if (real == 0) return 0.0;
        long fragments = sm.getServices().values().stream().filter(this::isFragment).count();
        return (double) fragments / real;
    }

    private boolean isFragment(ServiceStaticMetrics s) {
        return s.getEntityCount() == 1 && s.getFunctionalCohesion() == null;
    }

    // ─── Normalised-signal builders ───────────────────────────────────────────

    /** A [0,1] higher-is-better signal, missing when the snapshot has no value for it. */
    private MetricSignal unitSignal(String key, String label, Double value, double weight,
                                    String source, String desc) {
        if (value == null) return signalMissing(key, label, weight, "higher_is_better", source, desc);
        return signal(key, label, value, norm.clamp01(value), weight, "higher_is_better", source, desc);
    }

    /** A [0,1] lower-is-better signal, missing when the snapshot has no value for it. */
    private MetricSignal unitSignalInverted(String key, String label, Double value, double weight,
                                            String source, String desc) {
        if (value == null) return signalMissing(key, label, weight, "lower_is_better", source, desc);
        return signal(key, label, value, 1.0 - norm.clamp01(value), weight, "lower_is_better", source, desc);
    }

    /** A lower-is-better signal on a threshold, missing when the snapshot has no value for it. */
    private MetricSignal ratioSignal(String key, String label, Double value, Threshold t, double weight,
                                     String source, String desc) {
        if (value == null) return signalMissing(key, label, weight, "lower_is_better", source, desc);
        return signal(key, label, value, norm.normalizeLowerIsBetter(value, t), weight, "lower_is_better", source, desc);
    }

    private MetricSignal svcUnit(String key, String label, Double value, double weight) {
        return svcSignal(key, label, value, value != null ? norm.clamp01(value) : null, weight, "higher_is_better", "static");
    }

    private MetricSignal svcUnitInverted(String key, String label, Double value, double weight) {
        return svcSignal(key, label, value, value != null ? 1.0 - norm.clamp01(value) : null, weight, "lower_is_better", "static");
    }

    private MetricSignal svcRatio(String key, String label, Double value, Threshold t, double weight) {
        return svcSignal(key, label, value, value != null ? norm.normalizeLowerIsBetter(value, t) : null, weight, "lower_is_better", "static");
    }

    // ─── Signal builders ──────────────────────────────────────────────────────

    private MetricSignal signal(String key, String label, double raw, double normalized,
                                double weight, String direction, String source, String desc) {
        return MetricSignal.builder()
                .key(key).label(label)
                .rawValue(raw).normalizedValue(normalized)
                .weight(weight).contribution(normalized * weight)
                .direction(direction).source(source).description(desc)
                .estimated(false).build();
    }

    /**
     * A signal that could not be measured. {@code normalizedValue == null} makes
     * {@link MetricNormalizer#weightedAverage} skip both the value and its weight, so the remaining
     * signals renormalise and the attribute is scored on what was actually observed.
     *
     * <p>The alternative — substituting 0 for the raw value — is worse than it looks: every latency
     * threshold is lower-is-better, so 0 ms normalises to <b>1.0</b> and absent telemetry would read
     * as the best possible result. The weight is still carried so the UI can show what was skipped.
     */
    private MetricSignal signalMissing(String key, String label, double weight,
                                        String direction, String source, String desc) {
        return MetricSignal.builder()
                .key(key).label(label)
                .rawValue(null).normalizedValue(null)
                .weight(weight).contribution(0.0)
                .direction(direction).source(source).description(desc)
                .estimated(false).build();
    }

    private MetricSignal signalEstimated(String key, String label, double raw, double normalized,
                                          double weight, String direction, String source,
                                          String desc, boolean isEstimated) {
        return MetricSignal.builder()
                .key(key).label(label)
                .rawValue(raw).normalizedValue(normalized)
                .weight(weight).contribution(normalized * weight)
                .direction(direction).source(source).description(desc)
                .estimated(isEstimated).build();
    }

    /**
     * Builds a per-entity {@link MetricSignal} that gracefully handles missing data.
     * When {@code raw} is null the signal is marked estimated with zero contribution.
     */
    private MetricSignal svcSignal(String key, String label, Double raw, Double normalized,
                                    double weight, String direction, String source) {
        boolean missing = raw == null;
        double contrib = (missing || normalized == null) ? 0.0 : normalized * weight;
        return MetricSignal.builder()
                .key(key).label(label)
                .rawValue(raw).normalizedValue(normalized)
                .weight(weight).contribution(contrib)
                .direction(direction).source(source)
                .description("Per-service signal for " + label)
                .estimated(missing).build();
    }

    private QualityAttributeScore build(QualityAttribute attr,
                                         List<MetricSignal> signals,
                                         FormulaExplanation formula,
                                         Map<String, List<MetricSignal>> perEntitySignals,
                                         String perEntityType) {
        double score = norm.weightedAverage(signals);
        boolean hasEstimated = signals.stream().anyMatch(MetricSignal::isEstimated);
        String status = QualityAttributeStatus.of(score).label();
        int pct = (int) Math.round(score * 100);
        String topDriver = topDriver(attr, signals, score);
        return QualityAttributeScore.builder()
                .attribute(attr).score(score).percentage(pct).status(status)
                .formula(formula).signals(signals)
                .hasEstimatedData(hasEstimated).topDriver(topDriver)
                .perEntitySignals(perEntitySignals)
                .perEntityType(perEntitySignals != null ? perEntityType : null)
                .build();
    }

    private String topDriver(QualityAttribute attr, List<MetricSignal> signals, double score) {
        MetricSignal worst = signals.stream()
                .filter(s -> s.getNormalizedValue() != null)
                .min(java.util.Comparator.comparingDouble(s -> s.getNormalizedValue()))
                .orElse(null);
        if (worst == null) return null;
        boolean isNegative = worst.getNormalizedValue() < 0.5;
        if (!isNegative) return "All signals are in good shape";
        String direction = worst.getDirection().equals("lower_is_better") ? "High" : "Low";
        return direction + " " + worst.getLabel().toLowerCase() + " is reducing " + attr.name().toLowerCase();
    }

    // ─── Internal aggregated value objects ───────────────────────────────────

    /**
     * @param p95Ms        worst per-service self-time p95, or {@code null} when no service produced
     *                     a measurable work span. Nullable rather than 0.0 because 0 ms normalises
     *                     to a perfect score, so "not measured" would read as "excellent".
     */
    record AggrJaeger(Double p95Ms, double avgReadMs, double avgWriteMs, double bandwidthBps,
                      double errorRate, double opsPerSec, Map<String, AggregateMetrics> byService) {}

    /** Worst workflow p95 across SAGA workflows, or {@code null} when none were observed. */
    record AggrSaga(Double p95Ms) {}

    record AggrLocust(double p95Ms, double p99Ms, double requestsPerSec,
                      double errorRate, int requestCount) {}
}
