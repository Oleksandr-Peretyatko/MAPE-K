package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes how a single invariant method changes under a proposed refactoring.
 * Part of a {@link RefactorBlueprint}.
 *
 * <p>Change types:
 * <ul>
 *   <li>{@code STAYS} — invariant remains in the same aggregate</li>
 *   <li>{@code MOVES} — invariant relocates to a different aggregate</li>
 *   <li>{@code BECOMES_INTRA} — previously event-driven (inter) enforcement becomes synchronous (intra)</li>
 *   <li>{@code BECOMES_INTER} — previously synchronous enforcement becomes event-driven</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvariantChange {

    /** Invariant method name, e.g. {@code "invariantCreatorEnrolledInCourseExecution"}. */
    private String invariantName;

    /** One of: {@code STAYS}, {@code MOVES}, {@code BECOMES_INTRA}, {@code BECOMES_INTER}. */
    private String changeType;

    /** Current location (aggregate name or enforcement mode). */
    private String fromLocation;

    /** Proposed location (aggregate name or enforcement mode). */
    private String toLocation;

    /** Human-readable justification for the change. */
    private String rationale;
}
