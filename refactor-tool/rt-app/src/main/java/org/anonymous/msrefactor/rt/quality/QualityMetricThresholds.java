package org.anonymous.msrefactor.rt.quality;

/**
 * Bounded threshold pairs used for normalisation.
 *
 * <p>All values match the documented defaults from the QA scoring specification.
 * Thresholds are intentionally absolute (not dataset-relative) to keep scores stable
 * across snapshot versions.
 *
 * <p>{@code good} = value at which score reaches 1.0 (or 0.0 for lower-is-better).<br>
 * {@code bad}  = value at which score reaches 0.0 (or 1.0 for lower-is-better).
 */
public final class QualityMetricThresholds {

    public record Threshold(double good, double bad) {}

    // ── Latency ──────────────────────────────────────────────────────────────
    public static final Threshold P95_MS         = new Threshold(200,    1_200);
    public static final Threshold P99_MS         = new Threshold(400,    2_500);

    // ── Errors ───────────────────────────────────────────────────────────────
    public static final Threshold ERROR_RATE     = new Threshold(0.005,  0.10);

    // ── Throughput ───────────────────────────────────────────────────────────
    public static final Threshold REQUESTS_PER_S = new Threshold(100,    1);   // higher_is_better

    // ── Tracing ──────────────────────────────────────────────────────────────
    /**
     * Per-service p95 of <b>self time</b> — a span's duration minus what it was blocked on
     * downstream, so a service is not charged for the rest of the workflow.
     *
     * <p><b>Provisional.</b> The values are inherited from the old whole-span threshold rather than chosen,
     * because at the time of writing the traces contained no work spans at all to calibrate against
     * (only {@code TraceManager} scaffolding). Self time is bounded above by total time, so these
     * bounds can only be too lenient, never too harsh — the score cannot be unfairly depressed by
     * them. Recalibrate from the observed p50/p95 once a load run produces real work spans, or the
     * signal will sit pinned at 1.0, which is the same no-gradient failure this replaced.
     */
    public static final Threshold SELF_P95_MS    = new Threshold(100,    1_000);

    /**
     * p95 of a whole SAGA workflow. The <i>bad</i> bound is
     * {@code AnalysisConstants.HIGH_SAGA_LATENCY_MS}, so a workflow that scores 0 here is exactly
     * one {@code EvidenceExtractor} already reports as a high-saga-latency hotspot.
     *
     * <p>This is the signal a MergeAggregates should move: collapsing broker round-trips shortens
     * the workflow even when no individual step gets faster.
     */
    public static final Threshold SAGA_P95_MS    = new Threshold(200,    2_000);
    public static final Threshold BANDWIDTH_BPS  = new Threshold(10_000, 10_000_000);
    public static final Threshold OPS_PER_S      = new Threshold(100,    1);   // higher_is_better

    // ── Static ───────────────────────────────────────────────────────────────
    /**
     * stddev/mean of entity counts. {@code good} is 0.30 rather than 0: a system of aggregates of
     * identical size is not a goal, and a merge that creates one larger aggregate should not be
     * charged for leaving the rest as they were.
     */
    public static final Threshold ENTITY_IMBALANCE = new Threshold(0.30, 1.50);
    /** Declared replica classes per entity: one copy per entity is the worst case scored. */
    public static final Threshold REPLICA_DENSITY  = new Threshold(0.0,  1.0);
    /** Cross-service event subscriptions per entity. */
    public static final Threshold EVENT_EDGE_DENSITY = new Threshold(0.0, 1.0);
    /** Mean steps of the workflows that leave their service: two is a lookup, eight a long saga. */
    public static final Threshold DISTRIBUTED_STEPS = new Threshold(2.0, 8.0);
    public static final Threshold SINGLE_ENTITY_SVC_RATIO = new Threshold(0.0, 0.80);

    // ── Derived ──────────────────────────────────────────────────────────────
    public static final Threshold HOTSPOT_LOAD        = new Threshold(0.20,  0.90);
    public static final Threshold WRITE_DEP_RATIO     = new Threshold(0.0,   0.80);
}
