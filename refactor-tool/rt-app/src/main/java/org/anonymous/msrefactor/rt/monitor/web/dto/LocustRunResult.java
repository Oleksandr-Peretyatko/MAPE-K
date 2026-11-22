package org.anonymous.msrefactor.rt.monitor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload exchanged between rt-app and the locust-service.
 *
 * <p>Sent by the locust-service as the webhook callback body when a run finishes.
 * Also returned by {@code GET /api/runs/{runId}} on the locust-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LocustRunResult {

    private String runId;
    private String iterationId;

    /** PENDING | RUNNING | DONE | FAILED */
    private String status;

    private List<LocustScenarioResult> scenarios;
    private String error;
    private String startedAt;
    private String completedAt;

    // ─── Nested types ─────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class LocustScenarioResult {
        /** Locust scenario file name, e.g. "teacher_setup". */
        private String scenarioName;
        private List<LocustEndpointMetric> metrics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class LocustEndpointMetric {
        /** Locust "Name" column, e.g. "/executions/create". */
        private String endpointName;
        private int requestCount;
        private int failureCount;
        private double errorRate;
        private double medianMs;
        private double p95Ms;
        private double p99Ms;
        private double maxPctMs;
        private double avgMs;
        private double minMs;
        private double maxMs;
        private double rps;
        private double failuresPerSec;
        private double avgContentSizeBytes;
    }
}
