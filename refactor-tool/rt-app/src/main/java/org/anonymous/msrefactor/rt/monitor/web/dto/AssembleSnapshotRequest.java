package org.anonymous.msrefactor.rt.monitor.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;

import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /monitor/snapshot/assemble}.
 * Carries pre-collected data from each pipeline step so the backend
 * can assemble and persist a {@link org.anonymous.msrefactor.rt.monitor.MonitorSnapshot}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssembleSnapshotRequest {
    private List<VersionAnalysisResult>   staticVersions;
    private List<LocustScenarioMetrics>   locustScenarios;
    private Map<String, AggregateMetrics> aggregateMetrics;
    private Map<String, SagaMetrics>      sagaMetrics;
    private int                           consistencyViolationCount;
    private int                           windowMinutes;
    /** Optional: ID of the LoopIteration to link this snapshot to after assembly. */
    private String                        iterationId;
}
