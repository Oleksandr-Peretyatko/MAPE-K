package org.anonymous.msrefactor.rt.plan.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.plan.dto.PlanRequest;
import org.anonymous.msrefactor.rt.plan.dto.PlanResponse;
import org.anonymous.msrefactor.rt.plan.dto.PlanVersion;
import org.anonymous.msrefactor.rt.plan.dto.PlanVersionSummary;
import org.anonymous.msrefactor.rt.plan.service.PlanService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;

import org.springframework.http.HttpHeaders;
import java.util.List;
import java.util.Map;

/**
 * Controller for the Plan phase of the MAPE-K loop.
 *
 * <pre>
 * POST /plan/generate                    Generate and persist a plan from the Analyze selection
 * GET  /plan/current                     Retrieve the current in-memory plan
 * GET  /plan/{planId}                    Fetch a plan (reflects the active/approved version)
 * GET  /plan/{planId}/versions           List immutable plan versions (oldest first)
 * GET  /plan/versions/{versionId}        Fetch a specific plan version
 * POST /plan/{planId}/versions           Save a manual edit as a new USER_EDIT version
 * POST /plan/versions/{versionId}/approve Approve a version (demotes any other approved)
 * POST /plan/versions/{versionId}/reject  Reject a version
 * </pre>
 */
@RestController
@RequestMapping("/plan")
@RequiredArgsConstructor
@Slf4j
public class PlanController {

    private final PlanService planService;
    private final LoopIterationService loopIterationService;

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody PlanRequest req) {
        try {
            PlanResponse response = planService.generate(req);
            if (req.iterationId() != null && !req.iterationId().isBlank()) {
                try {
                    loopIterationService.linkPlan(req.iterationId(), response.planId());
                } catch (Exception e) {
                    log.warn("plan/generate: failed to link iterationId={}: {}",
                            req.iterationId(), e.getMessage());
                }
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Plan generation rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Plan generation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrent() {
        return planService.getCurrent()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{planId}")
    public ResponseEntity<PlanResponse> getPlan(@PathVariable String planId) {
        return planService.getPlan(planId)
                .<ResponseEntity<PlanResponse>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{planId}/export")
    public ResponseEntity<?> exportPlan(@PathVariable String planId) {
        return planService.getPlan(planId)
                .map(plan -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"plan-" + planId + ".json\"")
                        .body((Object) plan))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Plan versions ─────────────────────────────────────────────────────────

    @GetMapping("/{planId}/versions")
    public ResponseEntity<List<PlanVersionSummary>> listVersions(@PathVariable String planId) {
        return ResponseEntity.ok(planService.listVersions(planId));
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<PlanVersion> getVersion(@PathVariable String versionId) {
        return planService.getVersion(versionId)
                .<ResponseEntity<PlanVersion>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Saves a manually-edited plan document as a new USER_EDIT version. */
    @PostMapping("/{planId}/versions")
    public ResponseEntity<?> saveUserEdit(@PathVariable String planId,
                                          @RequestBody Map<String, String> body) {
        try {
            PlanVersion version = planService.saveUserEdit(planId, body.get("document"), body.get("note"));
            return ResponseEntity.ok(version);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/versions/{versionId}/approve")
    public ResponseEntity<?> approveVersion(@PathVariable String versionId,
                                            @RequestBody(required = false) Map<String, String> body) {
        return setApproval(versionId, "APPROVED", body);
    }

    @PostMapping("/versions/{versionId}/reject")
    public ResponseEntity<?> rejectVersion(@PathVariable String versionId,
                                           @RequestBody(required = false) Map<String, String> body) {
        return setApproval(versionId, "REJECTED", body);
    }

    private ResponseEntity<?> setApproval(String versionId, String state, Map<String, String> body) {
        try {
            String note = body != null ? body.get("note") : null;
            return ResponseEntity.ok(planService.setApproval(versionId, state, note));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}
