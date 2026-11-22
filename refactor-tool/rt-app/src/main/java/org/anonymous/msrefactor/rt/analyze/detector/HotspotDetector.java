package org.anonymous.msrefactor.rt.analyze.detector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.anonymous.msrefactor.rt.constants.AnalysisConstants;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.HotspotThresholds;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces {@link Hotspot} objects by applying QA-formula-driven per-service analysis and
 * SAGA/system-level threshold rules to the three evidence objects.
 *
 * <p>Per-service hotspots are driven by {@link PerServiceQaAnalyzer}, which decomposes each
 * service's contribution to QA formula deficits across all 7 quality attributes
 * (PERFORMANCE, CONSISTENCY, MAINTAINABILITY, COUPLING, COHESION, SCALABILITY, AUTONOMY)
 * and identifies the dominant signal component. SAGA workflow and system-level consistency
 * hotspots are detected via independent threshold rules.
 *
 * <p>Each hotspot is labeled with a {@link QualityAttribute}. The resulting list is sorted by
 * severity descending.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotspotDetector {

    private final PerServiceQaAnalyzer qaAnalyzer;

    /**
     * Runs QA-driven per-service analysis and SAGA/consistency threshold rules against the
     * supplied evidence and returns detected hotspots sorted by severity descending.
     */
    public List<Hotspot> detect(StructuralEvidence structural,
                                RuntimeEvidence runtime,
                                ConsistencyEvidence consistency,
                                HotspotThresholds t) {
        List<Hotspot> hotspots = new ArrayList<>();

        // QA-formula-driven per-service hotspots
        List<PerServiceQaAnalyzer.ServiceQaDeficit> deficits = qaAnalyzer.analyze(runtime, structural);
        for (PerServiceQaAnalyzer.ServiceQaDeficit d : deficits) {
            hotspots.add(Hotspot.builder()
                    .type(d.suggestedHotspotType())
                    .qualityAttribute(d.qa())
                    .affectedServices(List.of(d.serviceName()))
                    .evidence(String.format("%s deficit (%.0f%%): %s is the dominant signal",
                            d.qa().name(), d.severity() * 100, d.dominantSignalLabel()))
                    .severity(d.severity())
                    .build());
        }

        // SAGA workflow latency — no per-service formula equivalent
        detectSagaLatency(runtime, hotspots, t);

        // SAGA compensation rate and invariant violations — aggregate consistency evidence
        detectHighCompensation(consistency, hotspots, t);
        detectConsistencyViolations(consistency, hotspots);

        // Assign QA to any saga/consistency hotspot not already labeled
        for (Hotspot h : hotspots) {
            if (h.getQualityAttribute() == null) {
                QualityAttribute qa = switch (h.getType()) {
                    case HIGH_LATENCY, READ_HEAVY, HIGH_ERROR_RATE -> QualityAttribute.PERFORMANCE;
                    case HIGH_COMPENSATION, CONSISTENCY_VIOLATION   -> QualityAttribute.CONSISTENCY;
                    default -> QualityAttribute.MAINTAINABILITY;
                };
                h.setQualityAttribute(qa);
            }
        }

        hotspots.sort((a, b) -> Double.compare(b.getSeverity(), a.getSeverity()));
        log.debug("Analyze: detected {} hotspot(s)", hotspots.size());
        return hotspots;
    }

    // ─── SAGA / aggregate threshold rules ────────────────────────────────────

    private void detectSagaLatency(RuntimeEvidence runtime, List<Hotspot> out, HotspotThresholds t) {
        for (String wf : runtime.getHighSagaLatencyWorkflows()) {
            out.add(Hotspot.builder()
                    .type(HotspotType.HIGH_LATENCY)
                    .affectedServices(List.of(wf))
                    .evidence(String.format("SAGA workflow p95 latency exceeds %.0f ms",
                            t.highSagaLatencyMs()))
                    .severity(0.6)
                    .build());
        }
    }

    private void detectHighCompensation(ConsistencyEvidence consistency, List<Hotspot> out,
                                         HotspotThresholds t) {
        for (String wf : consistency.getHighCompensationWorkflows()) {
            out.add(Hotspot.builder()
                    .type(HotspotType.HIGH_COMPENSATION)
                    .affectedServices(List.of(wf))
                    .evidence(String.format("Compensation rate exceeds %.0f%%",
                            t.highCompensationRate() * 100))
                    .severity(consistency.getAvgCompensationRate())
                    .build());
        }
    }

    private void detectConsistencyViolations(ConsistencyEvidence consistency, List<Hotspot> out) {
        int count = consistency.getViolationCount();
        if (count > 0) {
            double severity = Math.min(count / AnalysisConstants.VIOLATION_SEVERITY_NORM, 1.0);
            out.add(Hotspot.builder()
                    .type(HotspotType.CONSISTENCY_VIOLATION)
                    .affectedServices(List.of("system"))
                    .evidence(String.format("%d invariant violation(s) detected in spans", count))
                    .severity(severity)
                    .build());
        }
    }
}
