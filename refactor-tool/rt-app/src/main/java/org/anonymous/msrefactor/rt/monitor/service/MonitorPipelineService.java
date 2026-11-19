package org.anonymous.msrefactor.rt.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.anonymous.msrefactor.rt.constants.TimingConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.monitor.LocustCollectionResult;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.collector.LocustCollector;
import org.anonymous.msrefactor.rt.monitor.collector.StaticAnalysisCollector;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotEntity;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotRepository;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionRequest;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the automated Monitor collection pipeline.
 *
 * <p>Sequence: static analysis → Locust load tests → Jaeger collection.
 * The pipeline runs asynchronously; each completed stage patches the snapshot in DB
 * so the frontend can render tables incrementally via polling.
 *
 * <p>Stop semantics: a stop request skips waiting for Locust to finish but still
 * runs Jaeger with whatever data is available, then sets status STOPPED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorPipelineService {

    private static final int LOCUST_POLL_INTERVAL_MS = TimingConstants.LOCUST_POLL_INTERVAL_MS;

    @Value("${rt.monitor.locust.default-host:http://localhost:8080}")
    private String locustDefaultHost;

    @Value("${rt.monitor.jaeger.collection-window-minutes:2}")
    private int defaultWindowMinutes;

    private final StaticAnalysisCollector staticAnalysisCollector;
    private final MeasuredVersionResolver measuredVersionResolver;
    private final LocustRunnerService locustRunnerService;
    private final LocustCollector locustCollector;
    private final MonitorService monitorService;
    private final MonitorSnapshotRepository repository;
    private final LoopIterationService loopIterationService;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Set<String> stopFlags = ConcurrentHashMap.newKeySet();

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Creates an empty snapshot, persists it, then starts the pipeline in the background.
     * Returns immediately with the empty snapshot (pipelineStatus=RUNNING_STATIC).
     */
    public MonitorSnapshot startPipeline(String host, int windowMinutes, String iterationId) {
        String snapshotId = UUID.randomUUID().toString();
        String effectiveHost = (host != null && !host.isBlank()) ? host : locustDefaultHost;
        int effectiveWindow = windowMinutes > 0 ? windowMinutes : defaultWindowMinutes;

        MonitorSnapshot snapshot = MonitorSnapshot.builder()
                .snapshotId(snapshotId)
                .collectedAt(Instant.now())
                .pipelineStatus("RUNNING_STATIC")
                .build();
        save(snapshot);

        if (iterationId != null && !iterationId.isBlank()) {
            try {
                loopIterationService.linkSnapshot(iterationId, snapshotId);
            } catch (Exception e) {
                log.warn("[pipeline] could not link iterationId={}: {}", iterationId, e.getMessage());
            }
        }

        log.info("[pipeline] started snapshotId={} host={} window={}min iterationId={}",
                snapshotId, effectiveHost, effectiveWindow, iterationId);

        executor.submit(() -> runPipeline(snapshotId, effectiveHost, effectiveWindow, iterationId));
        return snapshot;
    }

    /** Returns the current snapshot from DB (partial data + pipelineStatus). */
    public MonitorSnapshot getStatus(String snapshotId) {
        return repository.findById(snapshotId)
                .map(this::deserialize)
                .orElse(null);
    }

    /**
     * Requests the pipeline to stop after the current Locust poll cycle.
     * Jaeger will still run once Locust polling is abandoned.
     */
    public void stopPipeline(String snapshotId) {
        stopFlags.add(snapshotId);
        log.info("[pipeline] stop requested for snapshotId={}", snapshotId);
    }

    // ─── Pipeline logic ───────────────────────────────────────────────────────

    private void runPipeline(String snapshotId, String host, int windowMinutes, String iterationId) {
        try {
            // What is being measured: the deployed version, its recorded topology, its own commit.
            // Never the shared working tree or the topology cache — see MeasuredVersionResolver.
            MeasuredVersionResolver.Measured measured = measuredVersionResolver.resolve(iterationId);
            log.info("[pipeline:{}] measuring {} sha={} topology={} (source={})", snapshotId,
                    measured.label(),
                    measured.version() != null ? measured.version().getGitCommitSha() : null,
                    measured.topology().serviceNames(), measured.topologySource());

            // Stage 1: static analysis
            log.info("[pipeline:{}] stage=STATIC", snapshotId);
            List<VersionAnalysisResult> staticVersions = measuredVersionResolver.staticVersion(measured)
                    .map(List::of)
                    // No version record yet (fresh database): the legacy working-tree scan.
                    .orElseGet(staticAnalysisCollector::collectAll);
            patchStaticVersions(snapshotId, staticVersions);
            log.info("[pipeline:{}] STATIC done — {} versions", snapshotId, staticVersions.size());

            // Stage 2: Locust (skip if stop already requested)
            if (!stopRequested(snapshotId)) {
                updateStatus(snapshotId, "RUNNING_LOCUST");
                try {
                    locustRunnerService.start(host, iterationId);
                    awaitLocust(snapshotId);
                } catch (Exception e) {
                    log.warn("[pipeline:{}] Locust failed to start or was interrupted: {}", snapshotId, e.getMessage());
                }
                LocustCollectionResult locustResult = locustCollector.collectScenarios(iterationId);
                List<LocustScenarioMetrics> scenarios = locustResult.scenarios();
                log.info("[pipeline:{}] LOCUST done — {} scenarios [status={}]",
                        snapshotId, scenarios.size(), locustResult.status());
                if (!scenarios.isEmpty()) {
                    patchLocustScenarios(snapshotId, scenarios);
                }
            }

            // Stage 3: Jaeger (always runs)
            updateStatus(snapshotId, "RUNNING_JAEGER");
            log.info("[pipeline:{}] stage=JAEGER window={}min", snapshotId, windowMinutes);
            try {
                JaegerCollectionResult jaeger = monitorService.collectJaeger(
                        jaegerWindow(windowMinutes, measured.deployedAt(), snapshotId), measured.topology());
                patchJaeger(snapshotId, jaeger);
                log.info("[pipeline:{}] JAEGER done — {} services, {} violations",
                        snapshotId, jaeger.getAggregateMetrics().size(), jaeger.getConsistencyViolationCount());
            } catch (Exception e) {
                log.warn("[pipeline:{}] Jaeger collection failed (Jaeger may be unreachable): {}", snapshotId, e.getMessage());
            }

            String finalStatus = stopRequested(snapshotId) ? "STOPPED" : "DONE";
            updateStatus(snapshotId, finalStatus);
            log.info("[pipeline:{}] completed with status={}", snapshotId, finalStatus);

        } catch (Exception e) {
            log.error("[pipeline:{}] unexpected failure: {}", snapshotId, e.getMessage(), e);
            updateStatusWithError(snapshotId, e.getMessage());
        } finally {
            stopFlags.remove(snapshotId);
        }
    }

    /**
     * The last {@code windowMinutes}, but never earlier than the measured version's deploy: spans
     * from before it were produced by the previous version's services and would be attributed to
     * this one.
     */
    static JaegerCollectionRequest jaegerWindow(int windowMinutes, Instant deployedAt, String snapshotId) {
        Instant to = Instant.now();
        Instant from = to.minus(java.time.Duration.ofMinutes(windowMinutes));
        if (deployedAt != null && deployedAt.isAfter(from) && deployedAt.isBefore(to)) {
            log.info("[pipeline:{}] Jaeger window clamped to deploy time {} (was {})", snapshotId, deployedAt, from);
            from = deployedAt;
        }
        return new JaegerCollectionRequest(windowMinutes, from.toEpochMilli(), to.toEpochMilli());
    }

    private void awaitLocust(String snapshotId) throws InterruptedException {
        while (true) {
            String locustStatus = locustRunnerService.getStatus().getStatus();
            if ("DONE".equals(locustStatus) || "FAILED".equals(locustStatus)) break;
            if (stopRequested(snapshotId)) {
                log.info("[pipeline:{}] stop flag set — abandoning Locust wait (locust continues in background)", snapshotId);
                break;
            }
            Thread.sleep(LOCUST_POLL_INTERVAL_MS);
        }
    }

    private boolean stopRequested(String snapshotId) {
        return stopFlags.contains(snapshotId);
    }

    // ─── Patch helpers ────────────────────────────────────────────────────────

    private void patchStaticVersions(String snapshotId, List<VersionAnalysisResult> versions) {
        patch(snapshotId, s -> s.setStaticVersions(versions));
    }

    private void patchLocustScenarios(String snapshotId, List<LocustScenarioMetrics> scenarios) {
        patch(snapshotId, s -> s.setLocustScenarios(scenarios));
    }

    private void patchJaeger(String snapshotId, JaegerCollectionResult result) {
        patch(snapshotId, s -> {
            s.setAggregateMetrics(result.getAggregateMetrics());
            s.setSagaMetrics(result.getSagaMetrics());
            s.setDiscoveredServices(result.getAggregateMetrics() != null
                    ? List.copyOf(result.getAggregateMetrics().keySet()) : List.of());
            s.setConsistencyViolationCount(result.getConsistencyViolationCount());
        });
    }

    private void updateStatus(String snapshotId, String status) {
        patch(snapshotId, s -> s.setPipelineStatus(status));
    }

    private void updateStatusWithError(String snapshotId, String error) {
        patch(snapshotId, s -> {
            s.setPipelineStatus("FAILED");
            s.setPipelineError(error);
        });
    }

    @FunctionalInterface
    private interface SnapshotPatcher {
        void apply(MonitorSnapshot snapshot);
    }

    private void patch(String snapshotId, SnapshotPatcher patcher) {
        repository.findById(snapshotId).ifPresentOrElse(entity -> {
            try {
                MonitorSnapshot snapshot = deserialize(entity);
                if (snapshot == null) return;
                patcher.apply(snapshot);
                entity.setPayload(objectMapper.writeValueAsString(snapshot));
                repository.save(entity);
            } catch (JsonProcessingException e) {
                log.error("[pipeline:{}] failed to patch snapshot: {}", snapshotId, e.getMessage());
            }
        }, () -> log.warn("[pipeline:{}] snapshot not found during patch", snapshotId));
    }

    // ─── Persistence helpers ──────────────────────────────────────────────────

    private void save(MonitorSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            repository.save(MonitorSnapshotEntity.builder()
                    .snapshotId(snapshot.getSnapshotId())
                    .collectedAt(snapshot.getCollectedAt())
                    .payload(json)
                    .build());
        } catch (JsonProcessingException e) {
            log.error("[pipeline] failed to persist initial snapshot: {}", e.getMessage());
        }
    }

    private MonitorSnapshot deserialize(MonitorSnapshotEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayload(), MonitorSnapshot.class);
        } catch (JsonProcessingException e) {
            log.error("[pipeline] failed to deserialize snapshot {}: {}", entity.getSnapshotId(), e.getMessage());
            return null;
        }
    }
}
