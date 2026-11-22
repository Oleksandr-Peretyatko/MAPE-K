package org.anonymous.msrefactor.rt.analyze.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.analyze.CrossVersionClues;
import org.anonymous.msrefactor.rt.analyze.HotspotThresholds;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StructuralSignals;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Transforms a raw {@link MonitorSnapshot} into the four typed evidence objects consumed
 * by {@code HotspotDetector}. All methods are null-safe and tolerate partially populated
 * snapshots — missing data yields empty collections and zero numeric values.
 */
@Slf4j
@Component
public class EvidenceExtractor {

    /**
     * Extracts structural M1/M2 evidence from the snapshot's static versions.
     * Uses the last version in the list (most recent), falling back to the first if only one.
     */
    public StructuralEvidence extractStructural(MonitorSnapshot snap, HotspotThresholds t) {
        List<VersionAnalysisResult> versions =
                snap.getStaticVersions() != null ? snap.getStaticVersions() : Collections.emptyList();

        if (versions.isEmpty()) {
            log.debug("Analyze: no static versions in snapshot — structural evidence is empty");
            return StructuralEvidence.builder()
                    .m1(0.0).m2(0.0).serviceCount(0)
                    .perService(Collections.emptyMap())
                    .highCouplingPairs(Collections.emptyList())
                    .lowCohesionServices(Collections.emptyList())
                    .highEntityCountServices(Collections.emptyList())
                    .sourceVersion(null)
                    .build();
        }

        // Use the last (most recent) version
        VersionAnalysisResult selected = versions.get(versions.size() - 1);
        StaticMetrics sm = selected.getMetrics();

        if (sm == null || sm.getServices() == null) {
            return StructuralEvidence.builder()
                    .m1(0.0).m2(0.0).serviceCount(0)
                    .perService(Collections.emptyMap())
                    .highCouplingPairs(Collections.emptyList())
                    .lowCohesionServices(Collections.emptyList())
                    .highEntityCountServices(Collections.emptyList())
                    .sourceVersion(selected.getVersion())
                    .build();
        }

        // Normalised signals for every consumer downstream (per-service QA, candidates, prompts).
        sm = StructuralSignals.enrich(sm);
        Map<String, ServiceStaticMetrics> services = sm.getServices();

        // Detect high-coupling pairs (σᵢⱼ > threshold in either direction)
        List<String[]> highCouplingPairs = new ArrayList<>();
        List<String> serviceNames = new ArrayList<>(services.keySet());
        for (int i = 0; i < serviceNames.size(); i++) {
            for (int j = i + 1; j < serviceNames.size(); j++) {
                String a = serviceNames.get(i);
                String b = serviceNames.get(j);
                int sigma = services.get(a).getExternalRelations().getOrDefault(b, 0)
                          + services.get(b).getExternalRelations().getOrDefault(a, 0);
                if (sigma > t.highExternalRels()) {
                    highCouplingPairs.add(new String[]{a, b});
                }
            }
        }

        // Detect low-cohesion services
        List<String> lowCohesion = new ArrayList<>();
        List<String> highEntityCount = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> e : services.entrySet()) {
            ServiceStaticMetrics ssm = e.getValue();
            if (cohesionSignal(ssm) < t.lowCohesionM1()
                    && ssm.getEntityCount() >= 2) {
                lowCohesion.add(e.getKey());
            }
            if (ssm.getEntityCount() > t.highEntityCount()) {
                highEntityCount.add(e.getKey());
            }
        }

        return StructuralEvidence.builder()
                .m1(sm.getM1())
                .m2(sm.getM2())
                .serviceCount(sm.getServiceCount())
                .perService(services)
                .highCouplingPairs(highCouplingPairs)
                .lowCohesionServices(lowCohesion)
                .highEntityCountServices(highEntityCount)
                .sourceVersion(selected.getVersion())
                .build();
    }

    /**
     * Extracts runtime latency and throughput evidence from Jaeger and SAGA metrics.
     */
    public RuntimeEvidence extractRuntime(MonitorSnapshot snap, HotspotThresholds t) {
        Map<String, AggregateMetrics> aggMetrics =
                snap.getAggregateMetrics() != null ? snap.getAggregateMetrics() : Collections.emptyMap();
        Map<String, SagaMetrics> sagaMetrics =
                snap.getSagaMetrics() != null ? snap.getSagaMetrics() : Collections.emptyMap();
        List<LocustScenarioMetrics> locustScenarios =
                snap.getLocustScenarios() != null ? snap.getLocustScenarios() : Collections.emptyList();

        List<String> highLatency = new ArrayList<>();
        List<String> readHeavy   = new ArrayList<>();
        List<String> highBw      = new ArrayList<>();

        for (Map.Entry<String, AggregateMetrics> e : aggMetrics.entrySet()) {
            AggregateMetrics m = e.getValue();
            if (m.getP95LatencyMs() > t.highLatencyP95Ms()) {
                highLatency.add(e.getKey());
            }
            if (m.getReadWriteRatio() > t.readHeavyRatio()) {
                readHeavy.add(e.getKey());
            }
            // Flag bandwidth > 1 MB/s
            if (m.getBandwidthBytesPerSec() > 1_000_000) {
                highBw.add(e.getKey());
            }
        }

        List<String> highSagaLatency = new ArrayList<>();
        for (Map.Entry<String, SagaMetrics> e : sagaMetrics.entrySet()) {
            if (e.getValue().getP95TotalLatencyMs() > t.highSagaLatencyMs()) {
                highSagaLatency.add(e.getKey());
            }
        }

        // Join Locust scenarios with Functionality classes via locustTaskName
        Map<String, LocustScenarioMetrics> locustByTask = locustScenarios.stream()
                .filter(m -> m.getScenarioName() != null)
                .collect(Collectors.toMap(
                        LocustScenarioMetrics::getScenarioName, m -> m, (a, b) -> a));

        Map<String, LocustScenarioMetrics> functionalityLocustMetrics = null;
        List<VersionAnalysisResult> versions =
                snap.getStaticVersions() != null ? snap.getStaticVersions() : Collections.emptyList();
        if (!versions.isEmpty() && !locustByTask.isEmpty()) {
            VersionAnalysisResult latest = versions.get(versions.size() - 1);
            if (latest.getMetrics() != null && latest.getMetrics().getFunctionalities() != null) {
                Map<String, LocustScenarioMetrics> joined = new LinkedHashMap<>();
                for (FunctionalityInfo fi : latest.getMetrics().getFunctionalities()) {
                    if (fi.getLocustTaskName() == null) continue;
                    LocustScenarioMetrics lm = locustByTask.get(fi.getLocustTaskName());
                    if (lm == null) continue;
                    // Key = functionality base name (strip Sagas suffix)
                    String base = stripFunctionalitySuffix(fi.getName());
                    joined.putIfAbsent(base, lm);
                }
                if (!joined.isEmpty()) functionalityLocustMetrics = joined;
            }
        }

        return RuntimeEvidence.builder()
                .perServiceMetrics(aggMetrics)
                .highLatencyServices(highLatency)
                .readHeavyServices(readHeavy)
                .highBandwidthServices(highBw)
                .highSagaLatencyWorkflows(highSagaLatency)
                .sagaMetrics(sagaMetrics)
                .locustScenarios(locustScenarios)
                .functionalityLocustMetrics(functionalityLocustMetrics)
                .build();
    }

    /**
     * Extracts consistency evidence from violation counts and SAGA compensation rates.
     */
    public ConsistencyEvidence extractConsistency(MonitorSnapshot snap, HotspotThresholds t) {
        int violations = snap.getConsistencyViolationCount();
        Map<String, SagaMetrics> sagas =
                snap.getSagaMetrics() != null ? snap.getSagaMetrics() : Collections.emptyMap();

        List<String> highComp = new ArrayList<>();
        double totalCompRate  = 0.0;
        for (Map.Entry<String, SagaMetrics> e : sagas.entrySet()) {
            double rate = e.getValue().getCompensationRate();
            totalCompRate += rate;
            if (rate > t.highCompensationRate()) {
                highComp.add(e.getKey());
            }
        }
        double avgComp = sagas.isEmpty() ? 0.0 : totalCompRate / sagas.size();
        boolean hasSignals = violations > 0 || !highComp.isEmpty();

        return ConsistencyEvidence.builder()
                .violationCount(violations)
                .highCompensationWorkflows(highComp)
                .avgCompensationRate(avgComp)
                .hasConsistencySignals(hasSignals)
                .build();
    }

    /**
     * The per-service cohesion the LOW_COHESION threshold reads: functional cohesion when any
     * workflow resolves to the service, else its cluster factor; 1 when neither is known.
     */
    static double cohesionSignal(ServiceStaticMetrics ssm) {
        if (ssm.getFunctionalCohesion() != null) return ssm.getFunctionalCohesion();
        if (ssm.getClusterFactor() != null) return ssm.getClusterFactor();
        return 1.0;
    }

    /**
     * Extracts cross-version structural trends by comparing M1/M2 of first and last versions.
     * Returns INSUFFICIENT_DATA trends when fewer than two versions are available.
     */
    public CrossVersionClues extractCrossVersion(MonitorSnapshot snap) {
        List<VersionAnalysisResult> versions =
                snap.getStaticVersions() != null ? snap.getStaticVersions() : Collections.emptyList();

        List<String> versionNames = versions.stream()
                .map(VersionAnalysisResult::getVersion)
                .toList();

        if (versions.size() < 2) {
            return CrossVersionClues.builder()
                    .versionsAnalyzed(versionNames)
                    .m1Trend("INSUFFICIENT_DATA")
                    .m2Trend("INSUFFICIENT_DATA")
                    .notes(List.of("Only " + versions.size() + " version(s) available; need ≥2 for trends."))
                    .build();
        }

        StaticMetrics first = versions.get(0).getMetrics();
        StaticMetrics last  = versions.get(versions.size() - 1).getMetrics();

        String m1Trend = trend(first != null ? first.getM1() : 0.0, last != null ? last.getM1() : 0.0, true);
        String m2Trend = trend(first != null ? first.getM2() : 0.0, last != null ? last.getM2() : 0.0, false);

        List<String> notes = new ArrayList<>();
        notes.add(String.format("M1 %s → %s (%s)", fmtMetric(first, true), fmtMetric(last, true), m1Trend));
        notes.add(String.format("M2 %s → %s (%s)", fmtMetric(first, false), fmtMetric(last, false), m2Trend));

        return CrossVersionClues.builder()
                .versionsAnalyzed(versionNames)
                .m1Trend(m1Trend)
                .m2Trend(m2Trend)
                .notes(notes)
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * @param higherIsBetter true for M1 (cohesion), false for M2 (coupling should decrease)
     */
    private String trend(double from, double to, boolean higherIsBetter) {
        double delta = to - from;
        if (Math.abs(delta) < 1e-6) return "STABLE";
        boolean improved = (higherIsBetter && delta > 0) || (!higherIsBetter && delta < 0);
        return improved ? "IMPROVING" : "DEGRADING";
    }

    private String fmtMetric(StaticMetrics sm, boolean m1) {
        if (sm == null) return "n/a";
        return String.format("%.4f", m1 ? sm.getM1() : sm.getM2());
    }

    private String stripFunctionalitySuffix(String className) {
        for (String suffix : List.of("FunctionalitySagas", "Functionality")) {
            if (className.endsWith(suffix)) return className.substring(0, className.length() - suffix.length());
        }
        return className;
    }
}
