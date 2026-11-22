package org.anonymous.msrefactor.rt.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.monitor.web.dto.LocustRunResult;
import org.anonymous.msrefactor.rt.monitor.web.dto.LocustRunStatus;
import org.anonymous.msrefactor.rt.monitor.web.dto.StepStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the lifecycle of a single locust run by delegating to the
 * standalone locust-service over HTTP.
 *
 * <p>One run at a time: call {@link #start} to trigger, poll {@link #getStatus}
 * for progress. Results arrive via the webhook at
 * {@code POST /monitor/locust/callback} and are stored via {@link #handleCallback}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocustRunnerService {

    @Value("${rt.monitor.locust.default-host:http://localhost:8080}")
    private String defaultHost;

    @Value("${rt.app.base-url:http://rt-app:8090}")
    private String rtAppBaseUrl;

    private final LocustServiceClient locustServiceClient;

    // ─── State ────────────────────────────────────────────────────────────────

    enum RunState { IDLE, RUNNING, DONE, FAILED }

    private final AtomicReference<RunState> state = new AtomicReference<>(RunState.IDLE);
    private volatile String  remoteRunId;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile LocustRunResult lastResult;

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Triggers a new locust run against the locust-service.
     *
     * @param host        target application base URL
     * @param iterationId UUID of the current loop iteration (may be null)
     * @return initial {@link LocustRunStatus} (status = RUNNING)
     * @throws IllegalStateException if a run is already in progress
     */
    public synchronized LocustRunStatus start(String host, String iterationId) {
        return start(host, iterationId, null);
    }

    /**
     * Same as {@link #start(String, String)} but with explicit scenario list.
     */
    public synchronized LocustRunStatus start(String host, String iterationId,
                                               List<String> scenarios) {
        if (state.get() == RunState.RUNNING) {
            throw new IllegalStateException("A locust run is already in progress");
        }

        String targetHost = (host != null && !host.isBlank()) ? host : defaultHost;
        String iterId = (iterationId != null && !iterationId.isBlank()) ? iterationId : null;
        String callbackUrl = rtAppBaseUrl.stripTrailing() + "/monitor/locust/callback";

        state.set(RunState.RUNNING);
        startedAt     = Instant.now();
        finishedAt    = null;
        lastResult    = null;

        try {
            remoteRunId = locustServiceClient.startRun(
                    iterId != null ? iterId : "no-iteration",
                    targetHost,
                    callbackUrl,
                    scenarios);
            log.info("[locust-runner] delegated run={} to locust-service (iteration={}, host={})",
                    remoteRunId, iterId, targetHost);
        } catch (Exception e) {
            state.set(RunState.FAILED);
            finishedAt = Instant.now();
            log.error("[locust-runner] failed to start run: {}", e.getMessage());
            throw new IllegalStateException("Could not start locust run: " + e.getMessage(), e);
        }

        return getStatus();
    }

    /**
     * Receives the webhook callback from the locust-service when a run finishes.
     * Transitions the state machine and stores the final result.
     */
    public synchronized void handleCallback(LocustRunResult result) {
        log.info("[locust-runner] callback received: run={} status={}", result.getRunId(), result.getStatus());
        lastResult  = result;
        finishedAt  = Instant.now();
        boolean done = "DONE".equalsIgnoreCase(result.getStatus());
        state.set(done ? RunState.DONE : RunState.FAILED);
    }

    /** Returns the remote run_id assigned by the locust-service. */
    public String getRemoteRunId() { return remoteRunId; }

    /** Returns the current run status. Safe to call from any thread. */
    public LocustRunStatus getStatus() {
        RunState current = state.get();
        return LocustRunStatus.builder()
                .status(current.name())
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .exitCode(current == RunState.RUNNING ? null : (current == RunState.DONE ? 0 : 1))
                .log(null)
                .steps(computeSteps(current))
                .build();
    }

    // ─── Step status ──────────────────────────────────────────────────────────

    private List<StepStatus> computeSteps(RunState current) {
        List<String> SCENARIO_IDS = List.of(
                "teacherSetup", "studentEnrollment", "tournamentLifecycle",
                "studentManagement", "readOperations");
        List<String> SCENARIO_LABELS = List.of(
                "Teacher Course Setup", "Student Registration", "Full Tournament Lifecycle",
                "Student Management", "Read Operations");

        List<StepStatus> steps = new ArrayList<>();
        for (int i = 0; i < SCENARIO_IDS.size(); i++) {
            steps.add(StepStatus.builder()
                    .id(SCENARIO_IDS.get(i))
                    .label(SCENARIO_LABELS.get(i))
                    .marker(SCENARIO_IDS.get(i))
                    .status("PENDING")
                    .build());
        }

        if (current == RunState.IDLE) return steps;

        if (lastResult != null && lastResult.getScenarios() != null) {
            // Mark steps that appear in the callback result as DONE
            List<String> doneScenarios = lastResult.getScenarios().stream()
                    .map(s -> normalise(s.getScenarioName()))
                    .toList();
            for (StepStatus step : steps) {
                if (doneScenarios.contains(normalise(step.getId()))) {
                    step.setStatus(current == RunState.FAILED ? "FAILED" : "DONE");
                }
            }
        } else if (current == RunState.RUNNING) {
            // Run started but no results yet — mark first step as RUNNING
            steps.get(0).setStatus("RUNNING");
        }

        return steps;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase().replace("_", "").replace("-", "");
    }
}
