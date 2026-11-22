package org.anonymous.msrefactor.rt.monitor.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.collector.LocustCollector;
import org.anonymous.msrefactor.rt.monitor.collector.StaticAnalysisCollector;
import org.anonymous.msrefactor.rt.monitor.service.LocustRunnerService;
import org.anonymous.msrefactor.rt.monitor.service.MonitorService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.monitor.web.dto.AssembleSnapshotRequest;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionRequest;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionResult;
import org.anonymous.msrefactor.rt.monitor.web.dto.LocustRunResult;
import org.anonymous.msrefactor.rt.monitor.web.dto.LocustRunStatus;
import org.anonymous.msrefactor.rt.monitor.web.dto.PipelineStartRequest;
import org.anonymous.msrefactor.rt.monitor.service.MonitorPipelineService;

import org.springframework.http.HttpHeaders;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Monitor phase of the MAPE-K loop.
 *
 * <h3>Endpoints</h3>
 * <pre>
 * GET  /monitor/snapshot/{id}            Specific snapshot by ID
 * GET  /monitor/snapshot/{id}/export     Snapshot as a JSON download
 * GET  /monitor/static/versions          Version names available on disk
 * GET  /monitor/static/{version}         On-demand static analysis of one version
 * POST /monitor/pipeline/start           Automated static → Locust → Jaeger pipeline (the UI's path)
 * GET  /monitor/pipeline/{id}/status     Incremental pipeline snapshot
 * POST /monitor/pipeline/{id}/stop       Stop the pipeline after the current Locust poll
 * POST /monitor/locust/callback          Webhook from locust-service when a run completes
 * POST /monitor/collect/*, /snapshot/assemble   Stepwise collection, for manual use
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    @Value("${rt.monitor.jaeger.collection-window-minutes:2}")
    private int collectionWindowMinutes;

    @Value("${rt.monitor.locust.default-host:http://localhost:8080}")
    private String locustDefaultHost;

    private final MonitorService monitorService;
    private final MonitorPipelineService monitorPipelineService;
    private final StaticAnalysisCollector staticAnalysisCollector;
    private final LocustCollector locustCollector;
    private final LocustRunnerService locustRunnerService;
    private final LoopIterationService loopIterationService;

    /**
     * Returns a specific {@link MonitorSnapshot} by ID.
     *
     * @param id snapshot UUID
     * @return 200 with snapshot, or 404 if not found
     */
    @GetMapping("/snapshot/{id}")
    public ResponseEntity<MonitorSnapshot> getById(@PathVariable("id") String id) {
        return monitorService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/snapshot/{id}/export")
    public ResponseEntity<?> exportSnapshot(@PathVariable("id") String id) {
        return monitorService.getById(id)
                .map(snapshot -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"snapshot-" + id + ".json\"")
                        .body((Object) snapshot))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists all version names currently available on disk (live filesystem scan).
     * Does not require a prior collect cycle.
     *
     * @return 200 with list of version names (e.g., {@code ["v1", "v2"]})
     */
    @GetMapping("/static/versions")
    public ResponseEntity<List<String>> listStaticVersions() {
        return ResponseEntity.ok(staticAnalysisCollector.listVersions());
    }

    /**
     * Runs on-demand static analysis for a single named version.
     * Scans {@code <rootDir>/<version>/src/main/java} fresh — does not read from snapshot.
     *
     * @param version the version subdirectory name (e.g., {@code "v1"})
     * @return 200 with {@link VersionAnalysisResult} (metrics will be zero if version dir is absent)
     */
    @GetMapping("/static/{version}")
    public ResponseEntity<VersionAnalysisResult> analyzeStaticVersion(
            @PathVariable("version") String version) {
        return ResponseEntity.ok(staticAnalysisCollector.collectVersion(version));
    }

    // ─── Stepwise collection pipeline ────────────────────────────────────────

    /**
     * Step 1 — runs static analysis across all versions and returns results immediately.
     * Does not require the quizzes application to be running.
     */
    @PostMapping("/collect/static")
    public ResponseEntity<List<VersionAnalysisResult>> collectStatic() {
        log.info("Step-collect: static analysis");
        return ResponseEntity.ok(staticAnalysisCollector.collectAll());
    }

    /**
     * Step 2a — starts a Locust run on the locust-service.
     * Returns HTTP 202 immediately; poll {@code GET /monitor/collect/locust/status} for progress.
     *
     * @param body optional JSON body with {@code "host"} key (defaults to {@code http://localhost:8080})
     */
    @PostMapping("/collect/locust")
    public ResponseEntity<?> startLocust(
            @RequestBody(required = false) Map<String, String> body) {
        String host = (body != null && body.containsKey("host"))
                ? body.get("host") : locustDefaultHost;
        String iterationId = (body != null) ? body.get("iterationId") : null;
        log.info("Step-collect: starting Locust against {} (iterationId={})", host, iterationId);
        try {
            LocustRunStatus status = locustRunnerService.start(host, iterationId);
            return ResponseEntity.accepted().body(status);
        } catch (IllegalStateException e) {
            log.warn("Locust start rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 2b — polls the current Locust run status (IDLE/RUNNING/DONE/FAILED).
     */
    @GetMapping("/collect/locust/status")
    public ResponseEntity<LocustRunStatus> getLocustStatus() {
        return ResponseEntity.ok(locustRunnerService.getStatus());
    }

    /**
     * Step 2c — fetches Locust scenario metrics from the locust-service API (bypasses snapshot).
     * Call this after the run finishes to get results without a full collect cycle.
     */
    @GetMapping("/collect/locust/scenarios")
    public ResponseEntity<?> collectLocustScenarios(
            @RequestParam(required = false) String iterationId) {
        var locustResult = locustCollector.collectScenarios(iterationId);
        log.info("[locust] collectLocustScenarios: iterationId={} returning {} scenarios [status={}]",
                iterationId, locustResult.scenarios().size(), locustResult.status());
        return ResponseEntity.ok(locustResult.scenarios());
    }

    /**
     * Webhook callback invoked by the locust-service when a run completes.
     * Updates the local run-state machine and makes results available for collection.
     */
    @PostMapping("/locust/callback")
    public ResponseEntity<Void> locustCallback(@RequestBody LocustRunResult result) {
        log.info("[locust] callback received: run={} iteration={} status={}",
                result.getRunId(), result.getIterationId(), result.getStatus());
        locustRunnerService.handleCallback(result);
        return ResponseEntity.ok().build();
    }

    /**
     * Step 3 — collects Jaeger spans for the requested window and returns aggregated metrics.
     * Does not persist a snapshot. Designed to be called immediately after Locust finishes.
     *
     * @param req optional body; if {@code windowMinutes} is 0 the server default is used
     */
    @PostMapping("/collect/jaeger")
    public ResponseEntity<JaegerCollectionResult> collectJaeger(
            @RequestBody(required = false) JaegerCollectionRequest req) {
        if (req == null) req = new JaegerCollectionRequest(collectionWindowMinutes, null, null);
        log.info("Step-collect: Jaeger (windowMinutes={}, startMs={}, endMs={})",
                req.getWindowMinutes(), req.getWindowStartMs(), req.getWindowEndMs());
        return ResponseEntity.ok(monitorService.collectJaeger(req));
    }

    /**
     * Assembles a {@link MonitorSnapshot} from the three pipeline step results and persists it.
     */
    @PostMapping("/snapshot/assemble")
    public ResponseEntity<MonitorSnapshot> assembleSnapshot(
            @RequestBody AssembleSnapshotRequest req) {
        log.info("Assembling snapshot from pipeline results (iterationId={})", req.getIterationId());
        if (req.getIterationId() != null && !req.getIterationId().isBlank()
                && (req.getLocustScenarios() == null || req.getLocustScenarios().isEmpty())) {
            var locustResult = locustCollector.collectScenarios(req.getIterationId());
            if (!locustResult.scenarios().isEmpty()) {
                log.info("[assemble] locustScenarios in request: 0 items — overriding with {} from locust-service [status={}]",
                        locustResult.scenarios().size(), locustResult.status());
                req.setLocustScenarios(locustResult.scenarios());
            }
        }
        log.info("[assemble] locustScenarios in request: {} items",
                req.getLocustScenarios() != null ? req.getLocustScenarios().size() : 0);
        MonitorSnapshot snapshot = monitorService.assembleAndSave(req);
        if (req.getIterationId() != null && !req.getIterationId().isBlank()) {
            try {
                loopIterationService.linkSnapshot(req.getIterationId(), snapshot.getSnapshotId());
            } catch (Exception e) {
                log.warn("assembleSnapshot: failed to link iterationId={}: {}",
                        req.getIterationId(), e.getMessage());
            }
        }
        return ResponseEntity.ok(snapshot);
    }

    // ─── Automated collection pipeline ───────────────────────────────────────

    /**
     * Starts the automated 3-stage collection pipeline: static → Locust → Jaeger.
     * Creates an empty snapshot immediately and runs stages in the background.
     * Poll {@code GET /monitor/pipeline/{snapshotId}/status} for incremental results.
     */
    @PostMapping("/pipeline/start")
    public ResponseEntity<?> startPipeline(
            @RequestBody(required = false) PipelineStartRequest req) {
        if (req == null) req = new PipelineStartRequest(locustDefaultHost, collectionWindowMinutes, null);
        log.info("[pipeline] start request host={} window={}min iterationId={}",
                req.getHost(), req.getWindowMinutes(), req.getIterationId());
        try {
            MonitorSnapshot snapshot = monitorPipelineService.startPipeline(
                    req.getHost(), req.getWindowMinutes(), req.getIterationId());
            return ResponseEntity.ok(snapshot);
        } catch (Exception e) {
            log.error("[pipeline] failed to start: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Pipeline start failed"));
        }
    }

    /**
     * Returns the current pipeline snapshot from DB (partial data + pipelineStatus).
     * Poll this endpoint every 2 seconds to render tables incrementally.
     */
    @GetMapping("/pipeline/{snapshotId}/status")
    public ResponseEntity<?> getPipelineStatus(@PathVariable String snapshotId) {
        MonitorSnapshot snapshot = monitorPipelineService.getStatus(snapshotId);
        if (snapshot == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Requests the pipeline to stop after the current Locust poll cycle.
     * Jaeger collection still runs once the stop is processed.
     */
    @PostMapping("/pipeline/{snapshotId}/stop")
    public ResponseEntity<Void> stopPipeline(@PathVariable String snapshotId) {
        monitorPipelineService.stopPipeline(snapshotId);
        return ResponseEntity.ok().build();
    }
}
