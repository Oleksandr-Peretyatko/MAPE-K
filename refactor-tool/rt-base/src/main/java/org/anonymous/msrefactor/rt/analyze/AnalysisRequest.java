package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Input for the Analyze phase.
 *
 * <p>If {@code snapshotId} is null the service uses the latest persisted snapshot.
 * {@code qualityPriorities} drives candidate scoring: the first element has the highest weight.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisRequest {

    /**
     * ID of the snapshot to analyse. Null means "use the latest snapshot".
     */
    private String snapshotId;

    /**
     * Optional: ID of the LoopIteration this analysis belongs to.
     * When provided, the analysis result is automatically linked to the iteration.
     */
    private String iterationId;

    /**
     * Ordered list of quality attributes by priority (highest first).
     * Defaults to [PERFORMANCE, CONSISTENCY, MAINTAINABILITY] when empty or null.
     */
    private List<QualityAttribute> qualityPriorities;
}
