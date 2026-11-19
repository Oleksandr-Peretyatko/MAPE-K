package org.anonymous.msrefactor.rt.analyze.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.analyze.service.OperationQaScoringService;

import java.util.Map;

/**
 * REST API for per-project operation→QA score matrix.
 *
 * <pre>
 * GET  /config/operation-scores           — merged scores (project overrides + defaults)
 * PUT  /config/operation-scores           — save project-specific overrides
 * POST /config/operation-scores/reset     — remove project overrides, revert to defaults
 * GET  /config/operation-scores/defaults  — read-only global defaults
 * </pre>
 */
@RestController
@RequestMapping("/config/operation-scores")
@RequiredArgsConstructor
public class OperationQaScoringController {

    private static final String PROJECT_ID = "1";

    private final OperationQaScoringService scoringService;

    @GetMapping
    public ResponseEntity<Map<String, Map<String, Integer>>> getScores() {
        return ResponseEntity.ok(scoringService.getScoresForProject(PROJECT_ID));
    }

    @PutMapping
    public ResponseEntity<?> saveScores(@RequestBody Map<String, Map<String, Integer>> scores) {
        try {
            scoringService.saveProjectScores(PROJECT_ID, scores);
            return ResponseEntity.ok(scoringService.getScoresForProject(PROJECT_ID));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Map<String, Integer>>> reset() {
        scoringService.resetProjectScores(PROJECT_ID);
        return ResponseEntity.ok(scoringService.getScoresForProject(PROJECT_ID));
    }
}
