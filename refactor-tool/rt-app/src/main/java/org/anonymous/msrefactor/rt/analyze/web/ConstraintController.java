package org.anonymous.msrefactor.rt.analyze.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.analyze.persistence.RefactorConstraintEntity;
import org.anonymous.msrefactor.rt.analyze.service.ConstraintService;
import org.anonymous.msrefactor.rt.analyze.web.dto.ConstraintContext;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.collector.StaticAnalysisCollector;
import org.anonymous.msrefactor.rt.monitor.service.MonitorService;

import java.util.List;

/**
 * REST API for architect-defined refactoring constraints.
 *
 * <pre>
 * GET    /config/constraints          — list all rules
 * POST   /config/constraints          — create a new rule
 * DELETE /config/constraints/{id}     — remove a rule
 * GET    /config/constraints/context  — static-analysis context for rule authoring
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/config/constraints")
@RequiredArgsConstructor
public class ConstraintController {

    private final ConstraintService         constraintService;
    private final MonitorService            monitorService;
    private final StaticAnalysisCollector   staticAnalysisCollector;

    @GetMapping
    public ResponseEntity<List<RefactorConstraintEntity>> listAll() {
        return ResponseEntity.ok(constraintService.findAll());
    }

    @PostMapping
    public ResponseEntity<RefactorConstraintEntity> create(
            @RequestBody RefactorConstraintEntity constraint) {
        return ResponseEntity.ok(constraintService.create(constraint));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        constraintService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns a static-analysis context (services, entity names, intra/inter relations)
     * for use by the rule-authoring UI.
     *
     * <p>Reads the latest persisted {@link MonitorSnapshot}. If none exists yet,
     * falls back to a direct static-only scan (no Jaeger/Locust) so the UI always
     * receives real codebase data on first use.
     */
    @GetMapping("/context")
    public ResponseEntity<ConstraintContext> context() {
        MonitorSnapshot snapshot = monitorService.getLatest().orElseGet(() -> {
            log.info("ConstraintController /context: no snapshot found — running static-only scan");
            List<VersionAnalysisResult> versions = staticAnalysisCollector.collectAll();
            return MonitorSnapshot.builder()
                    .staticVersions(versions)
                    .build();
        });
        ConstraintContext ctx = constraintService.buildContext(snapshot);
        log.debug("ConstraintController /context: {} services in context", ctx.services().size());
        return ResponseEntity.ok(ctx);
    }
}
