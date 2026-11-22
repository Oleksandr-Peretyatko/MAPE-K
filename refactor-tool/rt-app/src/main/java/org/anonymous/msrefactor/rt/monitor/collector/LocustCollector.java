package org.anonymous.msrefactor.rt.monitor.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.monitor.LocustCollectionResult;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.service.LocustRunnerService;
import org.anonymous.msrefactor.rt.monitor.service.LocustServiceClient;
import org.anonymous.msrefactor.rt.monitor.web.dto.LocustRunResult;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Collects Locust load-test metrics from the locust-service REST API.
 *
 * <p>Replaces the previous file-based approach that read {@code *_stats.csv} files
 * from a volume-mounted directory. Results are now fetched directly from the
 * locust-service's PostgreSQL store, eliminating disk-based coordination.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocustCollector {

    private final LocustServiceClient locustServiceClient;
    private final LocustRunnerService locustRunnerService;

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Collects metrics for the most recent locust run tracked by this rt-app instance.
     */
    public LocustCollectionResult collectScenarios() {
        String runId = locustRunnerService.getRemoteRunId();
        if (runId == null) {
            log.info("[locust] no active run_id — returning empty metrics");
            return LocustCollectionResult.noActiveRun();
        }
        return fetchByRunId(runId);
    }

    /**
     * Collects metrics for the run associated with the given iteration UUID.
     * Queries the locust-service DB directly so results survive rt-app restarts.
     */
    public LocustCollectionResult collectScenarios(String iterationId) {
        if (iterationId == null || iterationId.isBlank()) {
            return collectScenarios();
        }

        LocustRunResult result;
        try {
            result = locustServiceClient.getRunResultByIteration(iterationId);
        } catch (Exception e) {
            log.warn("[locust] service unreachable for iteration={}: {}", iterationId, e.getMessage());
            return LocustCollectionResult.unreachable();
        }
        if (result == null) {
            log.info("[locust] no run found for iteration={} — returning empty metrics", iterationId);
            return LocustCollectionResult.runNotFound();
        }
        log.info("[locust] fetched run={} for iteration={} (status={})",
                result.getRunId(), iterationId, result.getStatus());
        return LocustCollectionResult.ok(toMetrics(result));
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private LocustCollectionResult fetchByRunId(String runId) {
        LocustRunResult result;
        try {
            result = locustServiceClient.getRunResult(runId);
        } catch (Exception e) {
            log.warn("[locust] service unreachable for run={}: {}", runId, e.getMessage());
            return LocustCollectionResult.unreachable();
        }
        if (result == null) {
            log.warn("[locust] run {} not found on locust-service", runId);
            return LocustCollectionResult.runNotFound();
        }
        log.info("[locust] run={} status={}", runId, result.getStatus());
        return LocustCollectionResult.ok(toMetrics(result));
    }

    /**
     * Converts a {@link LocustRunResult} to the existing {@link LocustScenarioMetrics}
     * contract so downstream consumers (Analyze phase, snapshot builder) are unchanged.
     *
     * <p>Mapping: one {@code LocustScenarioMetrics} per endpoint row, where
     * {@code scenarioName} = HTTP endpoint path (e.g. "/executions/create") and
     * {@code sourceFile} = locust scenario name (e.g. "teacher_setup").
     */
    private List<LocustScenarioMetrics> toMetrics(LocustRunResult result) {
        if (result.getScenarios() == null) return Collections.emptyList();

        List<LocustScenarioMetrics> out = result.getScenarios().stream()
                .filter(s -> s.getMetrics() != null)
                .flatMap(s -> s.getMetrics().stream()
                        .filter(Objects::nonNull)
                        .map(m -> LocustScenarioMetrics.builder()
                                .scenarioName(m.getEndpointName())
                                .sourceFile(s.getScenarioName())
                                .requestCount(m.getRequestCount())
                                .failureCount(m.getFailureCount())
                                .errorRate(m.getErrorRate())
                                .medianResponseMs(m.getMedianMs())
                                .avgResponseMs(m.getAvgMs())
                                .minResponseMs(m.getMinMs())
                                .maxResponseMs(m.getMaxMs())
                                .p95ResponseMs(m.getP95Ms())
                                .p99ResponseMs(m.getP99Ms())
                                .maxPctResponseMs(m.getMaxPctMs())
                                .requestsPerSec(m.getRps())
                                .failuresPerSec(m.getFailuresPerSec())
                                .avgContentSizeBytes(m.getAvgContentSizeBytes())
                                .build()))
                .toList();

        log.info("[locust] converted {} endpoint metrics from {} scenarios",
                out.size(), result.getScenarios().size());
        return out;
    }
}
