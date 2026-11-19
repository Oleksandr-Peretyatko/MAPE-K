package org.anonymous.msrefactor.rt.analyze.detector;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.quality.MetricNormalizer;
import org.anonymous.msrefactor.rt.quality.QualityMetricThresholds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.anonymous.msrefactor.rt.quality.QualityMetricThresholds.*;

/**
 * Decomposes QA formula signals to the per-service level to identify which service
 * and which metric component is the dominant contributor to each QA deficit.
 *
 * <p>Covers all 7 quality attributes (PERFORMANCE, CONSISTENCY, MAINTAINABILITY,
 * COUPLING, COHESION, SCALABILITY, AUTONOMY), mirroring the weights and thresholds
 * from {@link org.anonymous.msrefactor.rt.quality.QualityAttributeCalculator}
 * but applied per service rather than system-wide.
 */
@Component
@RequiredArgsConstructor
public class PerServiceQaAnalyzer {

    /** Spring replaces this with the bean; the default keeps direct `new` usable in unit tests. */
    @Autowired(required = false)
    private AnalysisThresholdsConfig thresholds = new AnalysisThresholdsConfig();

    /** Per-service entity count: below 3 is fine, above 20 is problematic for maintainability. */
    private static final QualityMetricThresholds.Threshold ENTITY_COUNT =
            new QualityMetricThresholds.Threshold(3, 20);

    private final MetricNormalizer norm;

    /**
     * Per-service QA deficit with the dominant metric component and suggested hotspot type.
     *
     * @param severity            {@code 1 − per-service QA score}; overall deficit magnitude [0,1]
     */
    public record ServiceQaDeficit(
            String serviceName,
            QualityAttribute qa,
            HotspotType suggestedHotspotType,
            String dominantSignalLabel,
            double severity
    ) {}

    /**
     * Runs per-service QA analysis for all 7 quality attributes.
     * Returns deduplicated results: one entry per (serviceName, hotspotType), highest severity wins.
     */
    public List<ServiceQaDeficit> analyze(RuntimeEvidence runtime, StructuralEvidence structural) {
        Map<String, AggregateMetrics> perServiceMetrics =
                runtime != null && runtime.getPerServiceMetrics() != null
                        ? runtime.getPerServiceMetrics() : Map.of();
        Map<String, ServiceStaticMetrics> perService =
                structural != null && structural.getPerService() != null
                        ? structural.getPerService() : Map.of();

        List<ServiceQaDeficit> all = new ArrayList<>();
        all.addAll(analyzePerformance(perServiceMetrics));
        all.addAll(analyzeReadHeavy(perServiceMetrics));
        all.addAll(analyzeConsistency(perService, perServiceMetrics));
        all.addAll(analyzeMaintainability(perService, perServiceMetrics));
        all.addAll(analyzeCoupling(perService, perServiceMetrics));
        all.addAll(analyzeCohesion(perService));
        all.addAll(analyzeScalability(perService, perServiceMetrics));
        all.addAll(analyzeAutonomy(perService, perServiceMetrics));

        // Dedup by (serviceName, hotspotType) — keep highest severity
        Map<String, ServiceQaDeficit> best = new HashMap<>();
        for (ServiceQaDeficit d : all) {
            String key = d.serviceName() + "::" + d.suggestedHotspotType().name();
            if (!best.containsKey(key) || d.severity() > best.get(key).severity()) {
                best.put(key, d);
            }
        }
        return new ArrayList<>(best.values());
    }

    // ─── PERFORMANCE per service ───────────────────────────────────────────────
    // Formula (Jaeger path): 0.40×inv_norm(self p95) + 0.25×norm(ops/s) + 0.20×inv_norm(error)
    // + 0.15×inv_norm(bandwidth), renormalised over whichever signals were measurable.

    private List<ServiceQaDeficit> analyzePerformance(Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, AggregateMetrics> e : perServiceMetrics.entrySet()) {
            String svc = e.getKey();
            AggregateMetrics m = e.getValue();

            // Latency is only scored when it was actually measured. A service whose only spans were
            // TraceManager scaffolding has p95 = 0, and 0 ms normalises to a perfect 1.0 — so
            // including it would reward absent telemetry and, worse, disagree with
            // QualityAttributeCalculator, which drops the signal. The score shown for a service and
            // the deficit used to rank it must come from the same terms.
            boolean hasLatency = m.getMeasuredSpanCount() > 0;

            double n_p95 = hasLatency
                    ? norm.normalizeLowerIsBetter(m.getP95LatencyMs(), SELF_P95_MS) : 0.0;
            double n_ops = norm.normalizeHigherIsBetter(m.getOperationFrequency() / 60.0, OPS_PER_S);
            double n_err = norm.normalizeLowerIsBetter(m.getErrorRate(), ERROR_RATE);
            double n_bw  = norm.normalizeLowerIsBetter(m.getBandwidthBytesPerSec(), BANDWIDTH_BPS);

            // Weights renormalise over the signals that survived, mirroring
            // MetricNormalizer.weightedAverage.
            double w_p95 = hasLatency ? 0.40 : 0.0;
            double w_ops = 0.25, w_err = 0.20, w_bw = 0.15;
            double wSum  = w_p95 + w_ops + w_err + w_bw;

            double d_p95 = (w_p95 / wSum) * (1.0 - n_p95);
            double d_ops = (w_ops / wSum) * (1.0 - n_ops);
            double d_err = (w_err / wSum) * (1.0 - n_err);
            double d_bw  = (w_bw  / wSum) * (1.0 - n_bw);

            double score   = (w_p95 * n_p95 + w_ops * n_ops + w_err * n_err + w_bw * n_bw) / wSum;
            double deficit = 1.0 - score;

            String domKey; String domLabel; double domContrib; HotspotType ht;
            if (d_err >= d_p95 && d_err >= d_ops && d_err >= d_bw) {
                domKey = "jaeger_error_rate"; domLabel = "Jaeger error rate";
                domContrib = d_err; ht = HotspotType.HIGH_ERROR_RATE;
            } else if (d_p95 >= d_ops && d_p95 >= d_bw) {
                domKey = "jaeger_self_p95"; domLabel = "Service self p95";
                domContrib = d_p95; ht = HotspotType.HIGH_LATENCY;
            } else if (d_ops >= d_bw) {
                domKey = "jaeger_ops_per_s"; domLabel = "Jaeger throughput (ops/s)";
                domContrib = d_ops; ht = HotspotType.HIGH_LATENCY;
            } else {
                domKey = "jaeger_bandwidth"; domLabel = "Jaeger bandwidth";
                domContrib = d_bw; ht = HotspotType.HIGH_LATENCY;
            }

            if (domContrib > thresholds.getMinDeficitContribution()) {
                out.add(new ServiceQaDeficit(svc, QualityAttribute.PERFORMANCE, ht, domLabel, deficit));
            }
        }
        return out;
    }

    private List<ServiceQaDeficit> analyzeReadHeavy(Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, AggregateMetrics> e : perServiceMetrics.entrySet()) {
            String svc = e.getKey();
            double ratio = e.getValue().getReadWriteRatio();
            if (ratio > 5.0) {
                double severity = Math.min(ratio / 20.0, 1.0);
                out.add(new ServiceQaDeficit(svc, QualityAttribute.PERFORMANCE, HotspotType.READ_HEAVY,
                        String.format("Read/write ratio (%.1f)", ratio), severity));
            }
        }
        return out;
    }

    // ─── CONSISTENCY per service ───────────────────────────────────────────────
    // Formula: 0.30×inv_norm(replica_density) + 0.25×inv_norm(event_edge_density)
    //        + 0.20×inv_norm(avg_distributed_steps) + 0.15×functional_cohesion
    //        + 0.10×inv_norm(write_dep_ratio), renormalised over the signals the snapshot carries.
    private List<ServiceQaDeficit> analyzeConsistency(Map<String, ServiceStaticMetrics> perService,
                                                       Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> e : perService.entrySet()) {
            String svc = e.getKey();
            ServiceStaticMetrics sm = e.getValue();
            AggregateMetrics rtm = perServiceMetrics.get(svc);

            Weighted w = new Weighted();
            w.add("replica_density", "Replica density", HotspotType.CONSISTENCY_VIOLATION,
                    0.30, lower(sm.getReplicaDensity(), REPLICA_DENSITY));
            w.add("event_edge_density", "Event-edge density", HotspotType.TIGHT_COUPLING,
                    0.25, lower(sm.getEventEdgeDensity(), EVENT_EDGE_DENSITY));
            w.add("avg_distributed_steps", "Distributed transaction length", HotspotType.HIGH_COMPENSATION,
                    0.20, lower(sm.getAvgDistributedSteps(), DISTRIBUTED_STEPS));
            w.add("functional_cohesion", "Functional cohesion (low)", HotspotType.LOW_COHESION,
                    0.15, unit(sm.getFunctionalCohesion()));
            w.add("write_dep_ratio", "Write dependency ratio", HotspotType.HIGH_COMPENSATION,
                    0.10, norm.normalizeLowerIsBetter(writeDependencyRatio(rtm), WRITE_DEP_RATIO));
            w.emit(out, svc, QualityAttribute.CONSISTENCY, thresholds.getMinDeficitContribution());
        }
        return out;
    }

    // ─── MAINTAINABILITY per service ───────────────────────────────────────────
    // Formula: 0.30×functional_cohesion + 0.20×structural_cohesion + 0.20×inv_norm(coupling_density)
    //        + 0.20×inv_norm(entity_count) + 0.10×inv_norm(error_rate)
    private List<ServiceQaDeficit> analyzeMaintainability(Map<String, ServiceStaticMetrics> perService,
                                                            Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> e : perService.entrySet()) {
            String svc = e.getKey();
            ServiceStaticMetrics sm = e.getValue();
            AggregateMetrics rtm = perServiceMetrics.get(svc);

            Weighted w = new Weighted();
            w.add("functional_cohesion", "Functional cohesion (low)", HotspotType.LOW_COHESION,
                    0.30, unit(sm.getFunctionalCohesion()));
            w.add("structural_cohesion", "Structural cohesion (low)", HotspotType.LOW_COHESION,
                    0.20, unit(sm.getStructuralCohesion()));
            w.add("coupling_density", "Coupling density", HotspotType.TIGHT_COUPLING,
                    0.20, inverted(sm.getCouplingDensity()));
            w.add("entity_count", "Entity count", HotspotType.HIGH_ENTITY_COUNT,
                    0.20, norm.normalizeLowerIsBetter(sm.getEntityCount(), ENTITY_COUNT));
            w.add("error_rate", "Error rate", HotspotType.HIGH_ERROR_RATE,
                    0.10, rtm != null ? norm.normalizeLowerIsBetter(rtm.getErrorRate(), ERROR_RATE) : null);
            w.emit(out, svc, QualityAttribute.MAINTAINABILITY, thresholds.getMinDeficitContribution());
        }
        return out;
    }

    // ─── COUPLING per service ─────────────────────────────────────────────────
    // Formula: 0.35×inv_norm(coupling_density) + 0.25×local_step_ratio + 0.20×inv_norm(replica_density)
    //        + 0.10×inv_norm(event_edge_density) + 0.10×inv_norm(bandwidth)
    private List<ServiceQaDeficit> analyzeCoupling(Map<String, ServiceStaticMetrics> perService,
                                                    Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> e : perService.entrySet()) {
            String svc = e.getKey();
            ServiceStaticMetrics sm = e.getValue();
            AggregateMetrics rtm = perServiceMetrics.get(svc);

            Weighted w = new Weighted();
            w.add("coupling_density", "Coupling density", HotspotType.TIGHT_COUPLING,
                    0.35, inverted(sm.getCouplingDensity()));
            w.add("local_step_ratio", "Local step ratio (low)", HotspotType.TIGHT_COUPLING,
                    0.25, unit(sm.getLocalStepRatio()));
            w.add("replica_density", "Replica density", HotspotType.TIGHT_COUPLING,
                    0.20, lower(sm.getReplicaDensity(), REPLICA_DENSITY));
            w.add("event_edge_density", "Event-edge density", HotspotType.TIGHT_COUPLING,
                    0.10, lower(sm.getEventEdgeDensity(), EVENT_EDGE_DENSITY));
            w.add("inter_svc_bandwidth", "Inter-service bandwidth", HotspotType.HIGH_LATENCY,
                    0.10, rtm != null ? norm.normalizeLowerIsBetter(rtm.getBandwidthBytesPerSec(), BANDWIDTH_BPS) : null);
            w.emit(out, svc, QualityAttribute.COUPLING, thresholds.getMinDeficitContribution());
        }
        return out;
    }

    // ─── COHESION per service ─────────────────────────────────────────────────
    // Formula: 0.45×functional_cohesion + 0.25×structural_cohesion + 0.15×cluster_factor
    //        + 0.10×inv_norm(entity_count) + 0.05×fragment_penalty
    private List<ServiceQaDeficit> analyzeCohesion(Map<String, ServiceStaticMetrics> perService) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> e : perService.entrySet()) {
            String svc = e.getKey();
            ServiceStaticMetrics sm = e.getValue();
            // A single-entity service no functionality reaches is a fragment, not an aggregate.
            boolean fragment = sm.getEntityCount() <= 1 && sm.getFunctionalCohesion() == null;

            Weighted w = new Weighted();
            w.add("functional_cohesion", "Functional cohesion (low)", HotspotType.LOW_COHESION,
                    0.45, unit(sm.getFunctionalCohesion()));
            w.add("structural_cohesion", "Structural cohesion (low)", HotspotType.LOW_COHESION,
                    0.25, unit(sm.getStructuralCohesion()));
            w.add("cluster_factor", "Cluster factor (low)", HotspotType.TIGHT_COUPLING,
                    0.15, unit(sm.getClusterFactor()));
            w.add("entity_count", "Entity count", HotspotType.HIGH_ENTITY_COUNT,
                    0.10, norm.normalizeLowerIsBetter(sm.getEntityCount(), ENTITY_COUNT));
            w.add("fragment", "Unused single-entity service (over-fragmentation risk)", HotspotType.HIGH_ENTITY_COUNT,
                    0.05, fragment ? 0.0 : 1.0);
            w.emit(out, svc, QualityAttribute.COHESION, thresholds.getMinDeficitContribution());
        }
        return out;
    }

    // ─── SCALABILITY per service ──────────────────────────────────────────────
    // Formula (Jaeger path, no Locust per-service breakdown):
    //   0.30×inv_norm(p95) + 0.20×inv_norm(error_rate)
    //   + 0.25×inv_norm(hotspot_load) + 0.25×inv_norm(coupling_density)

    private List<ServiceQaDeficit> analyzeScalability(Map<String, ServiceStaticMetrics> perService,
                                                        Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        double totalOps = perServiceMetrics.values().stream()
                .mapToDouble(m -> m.getOperationFrequency() / 60.0).sum();

        for (Map.Entry<String, ServiceStaticMetrics> e : perService.entrySet()) {
            String svc = e.getKey();
            ServiceStaticMetrics sm = e.getValue();
            AggregateMetrics rtm = perServiceMetrics.get(svc);

            double n_p95  = norm.normalizeLowerIsBetter(rtm != null ? rtm.getP95LatencyMs() : 0.0, SELF_P95_MS);
            double n_err  = norm.normalizeLowerIsBetter(rtm != null ? rtm.getErrorRate() : 0.0, ERROR_RATE);

            // hotspot_load: fraction of total ops concentrated in this service
            double svcOps = rtm != null ? rtm.getOperationFrequency() / 60.0 : 0.0;
            double hotspotLoad = totalOps > 0 ? svcOps / totalOps : 0.0;
            double n_load = norm.normalizeLowerIsBetter(hotspotLoad, HOTSPOT_LOAD);

            // Coupling density stands in for M2 (whose pair average shrinks when services merge).
            double n_m2   = sm.getCouplingDensity() == null ? 1.0 : 1.0 - norm.clamp01(sm.getCouplingDensity());

            double d_p95  = 0.30 * (1.0 - n_p95);
            double d_err  = 0.20 * (1.0 - n_err);
            double d_load = 0.25 * (1.0 - n_load);
            double d_m2   = 0.25 * (1.0 - n_m2);

            double score   = 0.30 * n_p95 + 0.20 * n_err + 0.25 * n_load + 0.25 * n_m2;
            double deficit = 1.0 - score;

            String domKey; String domLabel; double domContrib; HotspotType ht;
            if (d_err >= d_p95 && d_err >= d_load && d_err >= d_m2) {
                domKey = "error_rate"; domLabel = "Error rate";
                domContrib = d_err; ht = HotspotType.HIGH_ERROR_RATE;
            } else if (d_load >= d_p95 && d_load >= d_m2) {
                domKey = "hotspot_load"; domLabel = "Hotspot load concentration";
                domContrib = d_load; ht = HotspotType.HIGH_LATENCY;
            } else if (d_p95 >= d_m2) {
                domKey = "jaeger_self_p95"; domLabel = "Service self p95";
                domContrib = d_p95; ht = HotspotType.HIGH_LATENCY;
            } else {
                domKey = "coupling_density"; domLabel = "Coupling density (limits independent scaling)";
                domContrib = d_m2; ht = HotspotType.TIGHT_COUPLING;
            }

            if (domContrib > thresholds.getMinDeficitContribution()) {
                out.add(new ServiceQaDeficit(svc, QualityAttribute.SCALABILITY, ht, domLabel, deficit));
            }
        }
        return out;
    }

    // ─── AUTONOMY per service ─────────────────────────────────────────────────
    // Formula: 0.30×local_step_ratio + 0.25×inv_norm(coupling_density) + 0.20×inv_norm(replica_density)
    //        + 0.15×inv_norm(event_edge_density) + 0.10×inv_norm(bandwidth)
    private List<ServiceQaDeficit> analyzeAutonomy(Map<String, ServiceStaticMetrics> perService,
                                                    Map<String, AggregateMetrics> perServiceMetrics) {
        List<ServiceQaDeficit> out = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> e : perService.entrySet()) {
            String svc = e.getKey();
            ServiceStaticMetrics sm = e.getValue();
            AggregateMetrics rtm = perServiceMetrics.get(svc);

            Weighted w = new Weighted();
            w.add("local_step_ratio", "Local step ratio (low)", HotspotType.TIGHT_COUPLING,
                    0.30, unit(sm.getLocalStepRatio()));
            w.add("coupling_density", "Coupling density", HotspotType.TIGHT_COUPLING,
                    0.25, inverted(sm.getCouplingDensity()));
            w.add("replica_density", "Replica density", HotspotType.TIGHT_COUPLING,
                    0.20, lower(sm.getReplicaDensity(), REPLICA_DENSITY));
            w.add("event_edge_density", "Event-edge density", HotspotType.HIGH_COMPENSATION,
                    0.15, lower(sm.getEventEdgeDensity(), EVENT_EDGE_DENSITY));
            w.add("inter_svc_bandwidth", "Inter-service bandwidth", HotspotType.HIGH_LATENCY,
                    0.10, rtm != null ? norm.normalizeLowerIsBetter(rtm.getBandwidthBytesPerSec(), BANDWIDTH_BPS) : null);
            w.emit(out, svc, QualityAttribute.AUTONOMY, thresholds.getMinDeficitContribution());
        }
        return out;
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    /** A [0,1] higher-is-better signal; null stays null so the weight renormalises. */
    private Double unit(Double v) {
        return v == null ? null : norm.clamp01(v);
    }

    /** A [0,1] lower-is-better signal. */
    private Double inverted(Double v) {
        return v == null ? null : 1.0 - norm.clamp01(v);
    }

    /** A lower-is-better signal on a threshold. */
    private Double lower(Double v, QualityMetricThresholds.Threshold t) {
        return v == null ? null : norm.normalizeLowerIsBetter(v, t);
    }

    /**
     * One attribute's weighted signals for one service. Signals whose value is null are dropped
     * and the remaining weights renormalised, mirroring {@code MetricNormalizer.weightedAverage},
     * so the score shown for a service and the deficit used to rank it describe the same thing.
     */
    private static final class Weighted {
        private record Term(String key, String label, HotspotType type, double weight, double value) {}
        private final List<Term> terms = new ArrayList<>();

        void add(String key, String label, HotspotType type, double weight, Double value) {
            if (value != null) terms.add(new Term(key, label, type, weight, value));
        }

        void emit(List<ServiceQaDeficit> out, String svc, QualityAttribute qa, double minContribution) {
            double wSum = terms.stream().mapToDouble(Term::weight).sum();
            if (wSum == 0) return;
            double score = terms.stream().mapToDouble(t -> t.weight() * t.value()).sum() / wSum;
            Term worst = null;
            double worstDeficit = -1;
            for (Term t : terms) {
                double d = (t.weight() / wSum) * (1.0 - t.value());
                if (d > worstDeficit) { worstDeficit = d; worst = t; }
            }
            if (worst != null && worstDeficit > minContribution) {
                out.add(new ServiceQaDeficit(svc, qa, worst.type(), worst.label(), 1.0 - score));
            }
        }
    }

    private double writeDependencyRatio(AggregateMetrics rtm) {
        if (rtm == null) return 0.5;
        double total = rtm.getAvgReadLatencyMs() + rtm.getAvgWriteLatencyMs();
        return total > 0 ? rtm.getAvgWriteLatencyMs() / total : 0.5;
    }
}
