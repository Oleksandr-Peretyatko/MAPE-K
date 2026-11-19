package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-aggregate latency breakdown for a single SAGA workflow step group.
 *
 * <p>Keyed by service name (= aggregate name in the quizzes app) inside
 * {@link SagaMetrics#stepBreakdown}. Captures how long this aggregate's
 * steps took across all observed executions of the parent workflow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AggregateStepLatency {

    /** Service / aggregate name as registered in Jaeger (e.g. {@code quiz-service}). */
    private String aggregateName;

    /** Average duration of this aggregate's steps in milliseconds. */
    private double avgStepLatencyMs;

    /** 95th-percentile step duration in milliseconds. */
    private double p95StepLatencyMs;

    /** Total number of step spans observed across all workflow executions. */
    private long stepCount;
}
