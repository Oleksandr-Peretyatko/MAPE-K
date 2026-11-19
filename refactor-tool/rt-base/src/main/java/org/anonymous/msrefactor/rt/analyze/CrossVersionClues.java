package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cross-version structural trend clues derived from comparing M1/M2 values across versions.
 * Requires at least two versions in the snapshot; otherwise trends are {@code INSUFFICIENT_DATA}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CrossVersionClues {

    /** Ordered list of version names analysed (e.g., ["v1", "v2", "v3"]). */
    private List<String> versionsAnalyzed;

    /**
     * M1 (cohesion) trend from first to last version.
     * One of: {@code IMPROVING}, {@code DEGRADING}, {@code STABLE}, {@code INSUFFICIENT_DATA}.
     */
    private String m1Trend;

    /**
     * M2 (coupling) trend from first to last version.
     * One of: {@code IMPROVING} (coupling decreasing), {@code DEGRADING} (increasing),
     * {@code STABLE}, {@code INSUFFICIENT_DATA}.
     */
    private String m2Trend;

    /** Human-readable notes about the cross-version comparison. */
    private List<String> notes;
}
