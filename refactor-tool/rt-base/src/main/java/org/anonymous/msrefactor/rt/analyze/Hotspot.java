package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


/**
 * A detected architectural hotspot — a localised problem area that may warrant refactoring.
 *
 * <p>Each hotspot is produced by a rule in {@code HotspotDetector} and carries sufficient
 * evidence for the {@code CandidateGenerator} to map it to one or more candidate operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Hotspot {

    /** The category of structural or runtime problem detected. */
    private HotspotType type;

    /** The quality attribute this hotspot primarily violates. */
    private QualityAttribute qualityAttribute;

    /** Service names (or workflow names) implicated in this hotspot. */
    private List<String> affectedServices;

    /** Human-readable description of the specific evidence that triggered this hotspot. */
    private String evidence;

    /**
     * Normalised severity in the range [0.0, 1.0].
     * Computed from the raw metric value relative to the heuristic threshold.
     */
    private double severity;

    /**
     * Entity class names within the hotspot service(s) that are the root cause of this hotspot.
     * Populated by EntityAttributionService (step 4.2). Empty for hotspot types where
     * entity-level attribution is not applicable (e.g., HIGH_LATENCY).
     */
    private List<String> entityLevelCauses;
}
