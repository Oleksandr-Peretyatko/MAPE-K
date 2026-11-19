package org.anonymous.msrefactor.rt.analyze;

/**
 * Per-project hotspot detection thresholds for the Analyze phase — the single source of these
 * values. Held by {@code ProjectConfig}, edited through {@code /config/heuristics}.
 */
public record HotspotThresholds(
        double highLatencyP95Ms,
        double highSagaLatencyMs,
        int    highCrossSvcCalls,
        double highCompensationRate,
        double highErrorRate,
        double readHeavyRatio,
        double highCouplingM2,
        /** Cohesion signal (functional cohesion, else cluster factor) below which LOW_COHESION fires. */
        double lowCohesionM1,
        int    highEntityCount,
        int    highExternalRels
) {
    public static HotspotThresholds defaults() {
        return new HotspotThresholds(1000.0, 2000.0, 3, 0.10, 0.05, 5.0, 0.30, 0.35, 5, 2);
    }
}
