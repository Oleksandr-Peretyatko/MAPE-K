package org.anonymous.msrefactor.rt.monitor.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;

import java.time.Instant;
import java.util.Map;

/**
 * Result DTO returned by {@code POST /monitor/collect/jaeger}.
 * Contains aggregated service and SAGA metrics without persisting a snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JaegerCollectionResult {
    private Map<String, AggregateMetrics> aggregateMetrics;
    private Map<String, SagaMetrics>      sagaMetrics;
    private int                           consistencyViolationCount;
    private Instant                       collectedAt;
    private int                           windowMinutes;
}
