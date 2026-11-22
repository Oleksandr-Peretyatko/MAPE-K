package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A rejected or lower-ranked alternative to the selected refactoring operation.
 * Included in {@link CandidateOperation#getAlternatives()} to give architects visibility
 * into why the chosen candidate was preferred.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlternativeCandidate {

    /** The OperationType name (e.g., "CREATE_REPLICA", "REMOVE_REPLICA"). */
    private String operation;

    /** The entity, service, or operation subject that was considered. */
    private String target;

    /** Computed score for this alternative (same scale as the selected candidate). */
    private double score;

    /** Explanation of why this alternative ranked lower or was rejected. */
    private String reasonRejectedOrRankedLower;
}
