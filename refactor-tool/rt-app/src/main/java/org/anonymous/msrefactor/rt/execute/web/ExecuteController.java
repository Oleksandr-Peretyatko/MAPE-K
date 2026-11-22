package org.anonymous.msrefactor.rt.execute.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.dto.ExecuteResponse;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.execute.service.ClaudeCommandMutator;
import org.anonymous.msrefactor.rt.execute.service.ExecuteService;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;

import org.springframework.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
@Slf4j
public class ExecuteController {

    private final ExecuteService executeService;
    private final ClaudeCommandMutator claudeCommandMutator;
    private final ExecuteConfig executeConfig;
    private final LoopIterationService loopIterationService;

    /**
     * Starts the Execute pipeline for the current plan.
     * Returns 202 immediately; the pipeline runs asynchronously.
     * Poll GET /execute/status to track progress.
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(
            @RequestParam(required = false) String iterationId) {
        try {
            if (executeService.isRunning()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Another execution is already running",
                                     "hint", "Poll GET /execute/status"));
            }
            ExecuteResponse response = executeService.startRun(iterationId);
            linkExecuteToIteration(iterationId, response.executeRefactorId());
            return ResponseEntity.accepted().body(response);
        } catch (IllegalStateException e) {
            log.warn("Execute start rejected: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Execute start failed: {} — {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                 "cause", e.getClass().getSimpleName()));
        }
    }

    /**
     * Starts the Execute pipeline for a specific plan by ID.
     * Returns 202 immediately; the pipeline runs asynchronously.
     * Poll GET /execute/status to track progress.
     */
    @PostMapping("/run/plan/{planId}")
    public ResponseEntity<?> runForPlan(
            @PathVariable String planId,
            @RequestParam(required = false) String iterationId) {
        try {
            if (executeService.isRunning()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Another execution is already running",
                                     "hint", "Poll GET /execute/status"));
            }
            ExecuteResponse response = executeService.startRunForPlan(planId, iterationId);
            linkExecuteToIteration(iterationId, response.executeRefactorId());
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Execute start rejected — plan not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Execute start rejected: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Execute start failed: {} — {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                 "cause", e.getClass().getSimpleName()));
        }
    }

    /**
     * Resumes a FAILED run at step 5a (test gate → image → deploy) on the refactor it already
     * committed, without re-running the Claude mutation. Returns 202; poll GET /execute/status.
     */
    @PostMapping("/{executeRefactorId}/resume")
    public ResponseEntity<?> resume(@PathVariable String executeRefactorId) {
        try {
            return ResponseEntity.accepted().body(executeService.resumeFromBuild(executeRefactorId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Execute resume rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns the latest iteration record (current status, log, image tag, commit SHA). */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return executeService.getLatest()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Starts a test-run pipeline: creates a branch, appends a timestamp to CLAUDE.md, builds, and deploys. No plan required. */
    @PostMapping("/test-run")
    public ResponseEntity<?> testRun() {
        if (executeService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Another execution is already running",
                                 "hint", "Poll GET /execute/status"));
        }
        try {
            ExecuteResponse response = executeService.startTestRun();
            return ResponseEntity.accepted().body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Rolls back the last DEPLOYED iteration: re-tags the previous image as :latest,
     * force-recreates the affected containers, and checks out master.
     */
    @PostMapping("/rollback")
    public ResponseEntity<?> rollback() {
        try {
            ExecuteResponse response = executeService.rollback();
            return ResponseEntity.accepted().body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Settles a ghost run parked at AWAITING_VERDICT.
     *
     * <p>Body: {@code { "executeRefactorId": "...", "verdict": "SUCCESS" | "FAILED",
     * "summary": "optional" }}. The verdict decides the {@code Result} of the CLAUDE.md
     * refactor-log entry the call then writes and commits, so it cannot be taken after the fact.
     */
    @PostMapping("/verdict")
    public ResponseEntity<?> verdict(@RequestBody Map<String, String> body) {
        String executeRefactorId = body.get("executeRefactorId");
        if (executeRefactorId == null || executeRefactorId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "executeRefactorId is required"));
        }
        String raw = body.getOrDefault("verdict", "").toUpperCase();
        if (!"SUCCESS".equals(raw) && !"FAILED".equals(raw)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid verdict '" + raw + "'. Use SUCCESS or FAILED."));
        }
        try {
            ExecuteResponse response = executeService.recordVerdict(
                    executeRefactorId, "SUCCESS".equals(raw), body.get("summary"));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Ghost verdict rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Ghost verdict failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName()));
        }
    }

    /**
     * Smoke-tests the claude CLI by modifying the repository README.
     * Verifies that the claude binary is installed, accessible, and authenticated.
     * Returns { success, exitCode, claudePath, output, error }.
     */
    @PostMapping("/test-claude-command")
    public ResponseEntity<?> testClaudeCommand() {
        String timestamp = Instant.now().toString();
        // Direct bash command — no file discovery needed, completes in 1 turn.
        String prompt = "Run this exact bash command once:\n" +
                "printf '\\n<!-- ran by test api command-base claude | " + timestamp + " -->\\n' >> README.md";
        String workingDir = executeConfig.getProjectRoot();

        String claudePath = null;
        try {
            claudePath = claudeCommandMutator.resolveBinary();
        } catch (IllegalStateException e) {
            log.error("[execute] test-claude-command: binary not found — {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "exitCode", -1,
                            "claudePath", "not found",
                            "output", "",
                            "error", e.getMessage()));
        }

        try {
            ProcessResult result = claudeCommandMutator.runOneShot(prompt, workingDir);
            boolean ok = result.succeeded();
            log.info("[execute] test-claude-command: exit={} success={}", result.exitCode(), ok);
            return ResponseEntity.status(ok ? 200 : 500)
                    .body(Map.of(
                            "success", ok,
                            "exitCode", result.exitCode(),
                            "claudePath", claudePath,
                            "output", result.combined(),
                            "error", ok ? "" : "claude exited with code " + result.exitCode()));
        } catch (Exception e) {
            log.error("[execute] test-claude-command failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "exitCode", -1,
                            "claudePath", claudePath != null ? claudePath : "unknown",
                            "output", "",
                            "error", e.getMessage()));
        }
    }

    @GetMapping("/{executeRefactorId}/export")
    public ResponseEntity<?> exportExecution(@PathVariable String executeRefactorId) {
        return executeService.getById(executeRefactorId)
                .map(exec -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"execution-" + executeRefactorId + ".json\"")
                        .body((Object) exec))
                .orElse(ResponseEntity.notFound().build());
    }

    private void linkExecuteToIteration(String iterationId, String executeIterationId) {
        if (iterationId == null || iterationId.isBlank()) return;
        try {
            loopIterationService.linkExecute(iterationId, executeIterationId);
        } catch (Exception e) {
            log.warn("[execute] failed to link iterationId={} executeId={}: {}",
                    iterationId, executeIterationId, e.getMessage());
        }
    }
}
