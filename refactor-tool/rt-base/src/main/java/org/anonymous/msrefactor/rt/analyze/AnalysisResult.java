package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.anonymous.msrefactor.rt.quality.QualityAttributeScore;

import java.time.Instant;
import java.util.List;

/**
 * Full output of the Analyze phase for a single MonitorSnapshot.
 *
 * <p>This object is intentionally kept tentative: the {@link #tentativeRanking} and
 * {@link #candidateOperations} are suggestions for the Plan phase, not execution directives.
 * The {@link #analyzePlanBoundary} field makes this explicit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    /** Persisted ID assigned when this result is saved to the analysis_result table. */
    private String analysisId;

    /** ID of the snapshot that was analysed. */
    private String snapshotId;

    /** Timestamp when this analysis was produced. */
    private Instant analyzedAt;

    /** Quality priorities used for scoring (highest first). */
    private List<QualityAttribute> qualityPriorities;

    /** Structural M1/M2 evidence extracted from static analysis. */
    private StructuralEvidence structuralEvidence;

    /** Runtime latency/throughput evidence extracted from Jaeger and SAGA metrics. */
    private RuntimeEvidence runtimeEvidence;

    /** Consistency violation and compensation evidence. */
    private ConsistencyEvidence consistencyEvidence;

    /** Cross-version structural trends (requires ≥2 versions in snapshot). */
    private CrossVersionClues crossVersionClues;

    /** Detected hotspots ordered by severity (highest first). */
    private List<Hotspot> hotspots;

    /** Candidate operations ordered by rank (best first), as produced by {@code SimpleRankingStrategy}. */
    private List<CandidateOperation> candidateOperations;

    /**
     * Frozen snapshot of all architect constraints that were active when this analysis ran.
     * Stored here so per-iteration exports remain accurate even after live constraints change.
     */
    private List<ConstraintSnapshot> activeConstraints;

    /** Operation types in rank order — a compact summary of {@link #candidateOperations}. */
    private List<OperationType> tentativeRanking;

    /**
     * Textual boundary statement making explicit what this phase does NOT decide.
     * Always set to a fixed string explaining that Plan owns final selection.
     */
    private String analyzePlanBoundary;

    /** One-sentence summary of the top-ranked candidate and why it was chosen. */
    private String finalRecommendation;

    /** Snapshot of the AnalysisConfig that was active when this result was produced. */
    private AnalysisConfig analysisConfig;

    /** Quality attribute scores computed during this analysis. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<QualityAttributeScore> qualityScores;
}
