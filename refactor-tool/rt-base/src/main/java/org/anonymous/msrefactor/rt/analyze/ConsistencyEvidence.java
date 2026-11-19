package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Consistency evidence derived from span error tags and SAGA compensation rates.
 * Populated by {@code EvidenceExtractor.extractConsistency()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsistencyEvidence {

    /** Number of spans tagged as invariant breaks in the latest snapshot. */
    private int violationCount;

    /** SAGA workflows whose compensation rate exceeds the high-compensation threshold. */
    private List<String> highCompensationWorkflows;

    /** Average compensation rate across all observed SAGA workflows. 0.0 if no workflows. */
    private double avgCompensationRate;

    /** True when any consistency signal (violations or high compensation) is present. */
    private boolean hasConsistencySignals;
}
