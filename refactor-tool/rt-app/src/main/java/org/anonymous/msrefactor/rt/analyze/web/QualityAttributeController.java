package org.anonymous.msrefactor.rt.analyze.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeEntity;
import org.anonymous.msrefactor.rt.analyze.service.QualityAttributeService;

import java.util.List;
import java.util.Map;

/**
 * REST API for the global quality attribute catalog.
 * QAs are transversal to projects — all projects share the same catalog.
 *
 * <pre>
 * GET    /config/quality-attributes          — list active QAs
 * POST   /config/quality-attributes          — create new QA
 * PUT    /config/quality-attributes/{id}     — update name/description/active
 * DELETE /config/quality-attributes/{id}     — soft-delete (active=false)
 * </pre>
 */
@RestController
@RequestMapping("/config/quality-attributes")
@RequiredArgsConstructor
public class QualityAttributeController {

    private final QualityAttributeService qaService;

    @GetMapping
    public ResponseEntity<List<QualityAttributeEntity>> listActive() {
        return ResponseEntity.ok(qaService.listActive());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String description = body.getOrDefault("description", "");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("name is required");
        }
        try {
            return ResponseEntity.ok(qaService.create(name, description));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String description = (String) body.get("description");
            Boolean active = body.containsKey("active") ? (Boolean) body.get("active") : null;
            return ResponseEntity.ok(qaService.update(id, name, description, active));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable String id) {
        qaService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
