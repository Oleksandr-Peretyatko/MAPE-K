package org.anonymous.msrefactor.rt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.constants.AnalysisConstants;

import java.util.EnumSet;
import java.util.Set;

/**
 * Runtime view of {@link AnalysisConstants}.
 *
 * <p>Defaults come from the constants; override through the environment, e.g.
 * {@code RT_ANALYZE_CANDIDATE_LIMIT=20}. Nothing is declared in {@code application.yml} so each
 * value exists exactly once in the repo.
 *
 * <p>Hotspot detection thresholds are <b>not</b> here: they are per-project data
 * ({@code HotspotThresholds}, held by {@code ProjectConfig} and edited through
 * {@code /config/heuristics}).
 */
@Component
@ConfigurationProperties(prefix = "rt.analyze")
@Data
public class AnalysisThresholdsConfig {

    // ─── Detector / selector ──────────────────────────────────────────────────

    private double minDeficitContribution = AnalysisConstants.MIN_DEFICIT_CONTRIBUTION;
    private double highAggregateLatencyMs = AnalysisConstants.HIGH_AGGREGATE_LATENCY_MS;
    private int maxRankedResults = AnalysisConstants.MAX_RANKED_RESULTS;
    private int candidateLimit = AnalysisConstants.CANDIDATE_LIMIT;

    // ─── Operation catalogue ──────────────────────────────────────────────────

    /**
     * Operations Analyze may propose. Everything else is dropped at generation, from stored
     * candidates, and refused by Plan. Override with a comma-separated list, e.g.
     * {@code RT_ANALYZE_ENABLED_OPERATIONS=MERGE_AGGREGATES,SPLIT_AGGREGATE,CREATE_REPLICA}.
     */
    private Set<OperationType> enabledOperations =
            EnumSet.of(OperationType.MERGE_AGGREGATES, OperationType.SPLIT_AGGREGATE);

    /** Null-safe: an unset or empty list falls back to the default pair rather than disabling everything. */
    public Set<OperationType> getEnabledOperations() {
        return enabledOperations == null || enabledOperations.isEmpty()
                ? EnumSet.of(OperationType.MERGE_AGGREGATES, OperationType.SPLIT_AGGREGATE)
                : enabledOperations;
    }

    public boolean isEnabled(OperationType op) {
        return op != null && getEnabledOperations().contains(op);
    }

    public boolean isEnabled(String opName) {
        if (opName == null) return false;
        try {
            return isEnabled(OperationType.valueOf(opName.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
