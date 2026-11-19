package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes the relocation of a single entity class from one aggregate to another.
 * Part of a {@link RefactorBlueprint}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityMove {

    /** Entity class name being relocated, e.g. {@code "TournamentParticipant"}. */
    private String entityName;

    /** Aggregate the entity currently belongs to, e.g. {@code "Tournament"}. */
    private String fromAggregate;

    /** Proposed destination aggregate, e.g. {@code "TournamentParticipation"}. */
    private String toAggregate;

    /** Human-readable justification for the move. */
    private String rationale;
}
