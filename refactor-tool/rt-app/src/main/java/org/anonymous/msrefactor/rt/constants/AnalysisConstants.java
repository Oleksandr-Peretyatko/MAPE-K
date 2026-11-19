package org.anonymous.msrefactor.rt.constants;

/**
 * Thresholds used by Analyze-phase ranking and selection.
 *
 * <p>Defaults for {@code AnalysisThresholdsConfig}
 * ({@code @ConfigurationProperties(prefix = "rt.analyze")}), so each is overridable via
 * environment — e.g. {@code RT_ANALYZE_CANDIDATE_LIMIT=20}.
 *
 * <p>Hotspot <em>detection</em> thresholds live in {@code HotspotThresholds} (rt-base): they are
 * per-project data edited through {@code /config/heuristics}, not compile-time constants.
 *
 * <p>Not here on purpose: the QA scoring weights in {@code PerServiceQaAnalyzer}
 * ({@code 0.40*n_p95 + 0.25*n_ops + …}). Those define the scoring model itself; pulling them out
 * as loose constants makes the formulas unreadable without making them any more tunable.
 */
public final class AnalysisConstants {

    // ─── Severity normalisation denominators ──────────────────────────────────

    /** Denominator for normalising consistency violation count to a 0–1 severity. */
    public static final double VIOLATION_SEVERITY_NORM = 10.0;

    // ─── Detector / selector thresholds (previously scattered) ────────────────

    /** Minimum share of a QA deficit a metric must contribute to be reported as a driver. */
    public static final double MIN_DEFICIT_CONTRIBUTION = 0.05;

    /** Inter-aggregate call latency (ms) above which a merge becomes an affinity candidate. */
    public static final double HIGH_AGGREGATE_LATENCY_MS = 200.0;

    /** Hard cap on ranked results returned by the ranking strategy. */
    public static final int MAX_RANKED_RESULTS = 20;

    /** Candidate operations surfaced per analysis run. */
    public static final int CANDIDATE_LIMIT = 10;

    private AnalysisConstants() {}
}
