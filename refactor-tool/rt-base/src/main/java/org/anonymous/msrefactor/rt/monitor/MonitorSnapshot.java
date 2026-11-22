package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable carrier of all runtime metrics collected in a single MAPE-K Monitor cycle.
 *
 * <p>Produced by {@code MonitorAggregator} and consumed by the Analyze phase. Each snapshot
 * covers a fixed {@link #collectionWindow} ending at {@link #collectedAt}.
 *
 * <p>The snapshot is persisted as a JSON document in PostgreSQL so that the Analyze and
 * Knowledge modules can query historical snapshots for trend analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MonitorSnapshot {

    /** UUID assigned at collection time. Stable across persistence round-trips. */
    private String snapshotId;

    /** Timestamp when collection finished. */
    private Instant collectedAt;

    /**
     * Length of the observation window queried from Jaeger
     * (e.g., {@code PT5M} for the last 5 minutes).
     */
    private Duration collectionWindow;

    /** Inclusive start of the Jaeger trace query window. */
    private Instant windowStart;

    /** Inclusive end of the Jaeger trace query window (equals {@link #collectedAt} for atomic collects). */
    private Instant windowEnd;

    /**
     * Per-service runtime metrics keyed by service name
     * (e.g., {@code "tournament-service"}).
     */
    private Map<String, AggregateMetrics> aggregateMetrics;

    /**
     * Per-SAGA workflow metrics keyed by workflow name
     * (e.g., {@code "CreateTournamentFunctionalitySagas"}).
     */
    private Map<String, SagaMetrics> sagaMetrics;

    /** Metrics from the locust-service run, one entry per scenario name. */
    private List<LocustScenarioMetrics> locustScenarios;

    /**
     * Number of spans tagged with {@code error=true} and operation names matching
     * known invariant-break patterns (e.g., containing {@code INVARIANT_BREAK}).
     * Used by the Analyze phase as the primary consistency violation signal.
     */
    private int consistencyViolationCount;

    /** Services discovered from Jaeger in this collection cycle. */
    private List<String> discoveredServices;

    /**
     * Static structural metrics (M1/M2) per discovered version.
     * Null when the root directory is not reachable or no versions were found.
     */
    private List<VersionAnalysisResult> staticVersions;

    /**
     * Current stage of the automated collection pipeline.
     * Values: RUNNING_STATIC, RUNNING_LOCUST, RUNNING_JAEGER, DONE, STOPPED, FAILED.
     * Null for snapshots created before the pipeline feature was introduced.
     */
    private String pipelineStatus;

    /** Error message when {@code pipelineStatus} is FAILED. */
    private String pipelineError;
}
