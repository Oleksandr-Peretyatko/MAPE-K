package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;

import java.util.List;
import java.util.Map;

/**
 * Runtime evidence derived from Jaeger span data and SAGA workflow metrics.
 * Populated by {@code EvidenceExtractor.extractRuntime()}. All collections are never null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeEvidence {

    /** Per-service runtime metrics keyed by service name. */
    private Map<String, AggregateMetrics> perServiceMetrics;

    /** Services whose p95 latency exceeds the high-latency threshold. */
    private List<String> highLatencyServices;

    /** Services with a read/write ratio above the read-heavy threshold. */
    private List<String> readHeavyServices;

    /** Services with bandwidth above the high-bandwidth threshold. */
    private List<String> highBandwidthServices;

    /** SAGA workflow names whose p95 latency exceeds the SAGA latency threshold. */
    private List<String> highSagaLatencyWorkflows;

    /** Full SAGA workflow metrics from the snapshot, keyed by workflow name. */
    private Map<String, SagaMetrics> sagaMetrics;

    /** Locust load-test scenario metrics from the snapshot. */
    private List<LocustScenarioMetrics> locustScenarios;

    /**
     * Per-Functionality runtime metrics joined from Locust via {@code FunctionalityInfo.locustTaskName}.
     * Key = Functionality base name (e.g. {@code "AddParticipant"}).
     * Null when no Locust data was available in the snapshot.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, LocustScenarioMetrics> functionalityLocustMetrics;
}
