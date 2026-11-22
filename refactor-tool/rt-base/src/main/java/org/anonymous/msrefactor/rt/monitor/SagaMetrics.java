package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Runtime metrics for a single SAGA workflow, collected over one observation window.
 *
 * <p>SAGA workflows in this system are identified by the root span whose operation name contains
 * the suffix {@code FunctionalitySagas} (e.g.,
 * {@code CreateTournamentFunctionalitySagas}). The child spans of that root span represent
 * individual SAGA steps, some of which are cross-service calls.
 *
 * <p>These metrics feed the {@code Analyze} phase directly:
 * <ul>
 *   <li>{@code avgCrossServiceCalls} is the primary driver for {@code CreateReplica} and
 *       {@code MergeAggregates} operation ranking.</li>
 *   <li>{@code compensationRate} is the primary signal for consistency violation frequency.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SagaMetrics {

    /**
     * Workflow name derived from the root span operation name
     * (e.g., {@code CreateTournamentFunctionalitySagas}).
     */
    private String workflowName;

    /** Average end-to-end duration of the full workflow in milliseconds. */
    private double avgTotalLatencyMs;

    /** 95th-percentile end-to-end latency in milliseconds. */
    private double p95TotalLatencyMs;

    /**
     * Average number of cross-service SAGA steps observed per workflow execution.
     * Counted as child spans whose service name differs from the root span's service.
     */
    private int avgCrossServiceCalls;

    /**
     * Fraction of workflow executions that triggered at least one compensation step.
     * Range: 0.0 (no compensations) – 1.0 (every execution compensated).
     * A high rate signals consistency or contention issues.
     */
    private double compensationRate;

    /** Number of times this workflow was executed in the collection window. */
    private long executionCount;

    /** Services involved in this workflow (derived from child span service names). */
    private java.util.List<String> involvedServices;

    /**
     * Per-aggregate step latency breakdown keyed by service name (= aggregate name).
     * Shows how long each aggregate's steps took across all observed workflow executions.
     * Null when no step spans were found in the trace data.
     */
    private Map<String, AggregateStepLatency> stepBreakdown;
}
