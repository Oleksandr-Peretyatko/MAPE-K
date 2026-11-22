package org.anonymous.msrefactor.rt.quality.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.quality.QualityScoreResult;
import org.anonymous.msrefactor.rt.quality.QualityScoreService;

/**
 * REST controller exposing quality attribute scores computed from a snapshot.
 *
 * <pre>
 * GET /quality/scores/{snapshotId}   → QualityScoreResult   (404 when not found)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/quality")
@RequiredArgsConstructor
public class QualityScoreController {

    private final QualityScoreService qualityScoreService;

    @GetMapping("/scores/{snapshotId}")
    public ResponseEntity<QualityScoreResult> getScores(@PathVariable String snapshotId) {
        return qualityScoreService.calculateQualityScores(snapshotId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
