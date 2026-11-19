package org.anonymous.msrefactor.rt.analyze.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.analyze.AnalysisRequest;
import org.anonymous.msrefactor.rt.analyze.AnalysisResult;
import org.anonymous.msrefactor.rt.analyze.service.AnalyzeService;

import org.springframework.http.HttpHeaders;
import java.util.List;

/**
 * REST controller for the Analyze phase of the MAPE-K loop.
 *
 * <h3>Endpoints</h3>
 * <pre>
 * POST /analyze/run                    Run analysis on a snapshot; body: AnalysisRequest
 * GET  /analyze/priorities             Quality priorities used by the last analysis
 * GET  /analyze/{analysisId}/export    Stored analysis as a JSON download
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/analyze")
@RequiredArgsConstructor
public class AnalyzeController {

    private final AnalyzeService analyzeService;

    /**
     * Runs the full Analyze pipeline on the requested snapshot.
     *
     * @param request analysis parameters — {@code snapshotId} null → latest snapshot;
     *                {@code qualityPriorities} null/empty → the project default configured
     *                via {@code PUT /config/analysis-config} (factory default
     *                [PERFORMANCE, CONSISTENCY, MAINTAINABILITY])
     * @return 200 {@link AnalysisResult}, or 404 if no matching snapshot
     */
    @PostMapping("/run")
    public ResponseEntity<AnalysisResult> run(@RequestBody AnalysisRequest request) {
        return analyzeService.analyze(request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns the quality priorities used in the last analysis for the given iteration,
     * or globally last-used priorities when no iterationId is provided.
     *
     * @param iterationId optional iteration UUID
     * @return 200 ordered list of QA names; the configured project default when no analysis exists
     */
    @GetMapping("/priorities")
    public ResponseEntity<List<String>> getLastPriorities(
            @RequestParam(required = false) String iterationId) {
        return ResponseEntity.ok(
                analyzeService.getLastPriorities(iterationId).orElse(List.of()));
    }

    /**
     * Downloads a previously persisted analysis result as a JSON file.
     */
    @GetMapping("/{analysisId}/export")
    public ResponseEntity<?> exportAnalysis(@PathVariable String analysisId) {
        return analyzeService.getById(analysisId)
                .map(result -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"analysis-" + analysisId + ".json\"")
                        .body((Object) result))
                .orElse(ResponseEntity.notFound().build());
    }
}
