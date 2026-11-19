package org.anonymous.msrefactor.rt.knowledge.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService.CodeVersionDetail;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService.CodeVersionSummary;
import org.anonymous.msrefactor.rt.knowledge.service.DiscoveryService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService.LoopIterationDetail;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService.LoopIterationSummary;
import org.anonymous.msrefactor.rt.knowledge.service.ProjectStructureService;
import org.anonymous.msrefactor.rt.knowledge.service.VersionEvolutionService;
import org.anonymous.msrefactor.rt.monitor.ProjectStructure;

import java.util.List;
import java.util.Map;

/**
 * Knowledge base of the MAPE-K loop.
 *
 * <h3>Iteration endpoints</h3>
 * <pre>
 * POST  /knowledge/iterations                       Create new iteration
 * GET   /knowledge/iterations                       List all iterations (summary)
 * GET   /knowledge/iterations/{id}                  Full detail with resolved phase data
 * PATCH /knowledge/iterations/{id}/plan             Link a plan to an iteration
 * PATCH /knowledge/iterations/{id}/execute          Link an execute iteration
 * POST  /knowledge/iterations/seed                  Create iterations for orphaned phase records
 * </pre>
 *
 * <h3>Version endpoints</h3>
 * <pre>
 * GET  /knowledge/versions[/tree|/{id}|/{id}/iterations|/{id}/structure]
 * POST /knowledge/versions/{id}/graph               Trigger Claude-powered discovery for a version
 * GET  /knowledge/versions/{id}/graph               Retrieve the stored discovery graph
 * </pre>
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeController {

    private final LoopIterationService     loopIterationService;
    private final DiscoveryService         discoveryService;
    private final CodeVersionService       codeVersionService;
    private final VersionEvolutionService  versionEvolutionService;
    private final ProjectStructureService  projectStructureService;

    // ─── Iteration CRUD ──────────────────────────────────────────────────────

    @PostMapping("/iterations")
    public ResponseEntity<LoopIterationSummary> createIteration(
            @RequestBody(required = false) Map<String, String> body) {
        String name = (body != null) ? body.get("name") : null;
        String codeVersionId = (body != null) ? body.get("codeVersionId") : null;
        LoopIterationSummary summary = loopIterationService.create(name, codeVersionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @GetMapping("/iterations")
    public ResponseEntity<List<LoopIterationSummary>> listIterations() {
        return ResponseEntity.ok(loopIterationService.listAll());
    }

    @GetMapping("/iterations/{id}")
    public ResponseEntity<LoopIterationDetail> getIteration(@PathVariable String id) {
        return loopIterationService.getDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/iterations/{id}/plan")
    public ResponseEntity<?> linkPlan(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String planId = body.get("planId");
        if (planId == null || planId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "planId required"));
        }
        try {
            return ResponseEntity.ok(loopIterationService.linkPlan(id, planId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/iterations/{id}/execute")
    public ResponseEntity<?> linkExecute(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String executeRefactorId = body.get("executeRefactorId");
        if (executeRefactorId == null || executeRefactorId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "executeRefactorId required"));
        }
        try {
            return ResponseEntity.ok(loopIterationService.linkExecute(id, executeRefactorId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/iterations/seed")
    public ResponseEntity<Map<String, Object>> seed() {
        int count = loopIterationService.seed();
        return ResponseEntity.ok(Map.of("created", count, "message",
                count > 0 ? "Seeded " + count + " iteration(s) from orphaned phase records"
                          : "No orphaned records found — all phases already have iterations"));
    }

    // ─── Code Version endpoints ───────────────────────────────────────────────

    @GetMapping("/versions")
    public ResponseEntity<List<CodeVersionSummary>> listVersions() {
        return ResponseEntity.ok(codeVersionService.listAll());
    }

    /** Evolution tree: every version with its producing operation and quality attribute scores. */
    @GetMapping("/versions/tree")
    public ResponseEntity<VersionEvolutionService.VersionTree> getVersionTree() {
        return ResponseEntity.ok(versionEvolutionService.buildTree());
    }

    @GetMapping("/versions/{id}")
    public ResponseEntity<CodeVersionDetail> getVersion(@PathVariable String id) {
        return codeVersionService.getDetail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/versions/{id}/iterations")
    public ResponseEntity<List<LoopIterationSummary>> getVersionIterations(@PathVariable String id) {
        return codeVersionService.getDetail(id)
                .map(detail -> ResponseEntity.ok(detail.getIterations()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/versions/{id}/graph")
    public ResponseEntity<?> triggerVersionDiscovery(@PathVariable String id) {
        log.info("[knowledge] Triggering discovery for codeVersionId={}", id);
        try {
            DiscoveryGraph graph = discoveryService.discoverForVersion(id);
            return ResponseEntity.ok(graph);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("[knowledge] Version discovery failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * The architecture of this version as static analysis sees it, grouped by aggregate: entities
     * with their composition tree, the P1 rules each aggregate enforces, and the edges to other
     * aggregates split into structural, event and saga coupling.
     *
     * <p>Deterministic and always available, unlike {@code /versions/{id}/graph}, which is produced
     * by a Claude run and exists only where someone triggered one. Each call re-reads the source tree
     * for the version, so callers should cache rather than poll.
     */
    @GetMapping("/versions/{id}/structure")
    public ResponseEntity<ProjectStructure> getVersionStructure(@PathVariable String id) {
        return projectStructureService.forVersion(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/versions/{id}/graph")
    public ResponseEntity<?> getVersionDiscovery(@PathVariable String id) {
        return discoveryService.getLatestForVersion(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
