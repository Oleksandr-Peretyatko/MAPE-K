package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One consistency rule enforced inside a single aggregate (a P1 intra-invariant), together with the
 * state it reads.
 *
 * <h3>Why the footprint is part of the record</h3>
 *
 * <p>Knowing that an invariant exists is not enough to decide anything. Every question the Analyze
 * phase asks about invariants — can this aggregate be split, which rules would straddle the cut, what
 * does the cut cost — needs to know <em>what the rule reads</em>. The previous representation was a
 * bare method name, and the only way to relate a name to an entity was substring matching, which
 * finds nothing: {@code "invariantuniqueparticipant"} does not contain
 * {@code "tournamentparticipant"}.
 *
 * <h3>Not every invariant has a name</h3>
 *
 * <p>The {@code invariant*} prefix is a convention of two aggregates, not a rule of the codebase.
 * {@code Execution} delegates to {@code removedNoStudents()}, {@code User} to {@code deletedState()},
 * and {@code Course} and {@code Question} check inline with no method at all. Those are recorded with
 * {@link Kind#INLINE} and named after the error constant they throw, which is the domain rule's own
 * name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvariantInfo {

    public enum Kind {
        /** A method called from {@code verifyInvariants()} — whatever it is named. */
        METHOD,
        /** A check written directly inside {@code verifyInvariants()}, with no method of its own. */
        INLINE
    }

    /** The method name, or — for {@link Kind#INLINE} — the error constant the check throws. */
    private String name;

    /** Simple name of the class the rule is written in. */
    private String declaredIn;

    private Kind kind;

    /**
     * Member entity classes of this aggregate that the rule reads.
     *
     * <p>Collected from three access styles, all of which occur: the field
     * ({@code this.tournamentParticipants}), an accessor ({@code getStudents()} — the only way
     * {@code removedNoStudents} reaches its collection), and a type reference
     * ({@code TournamentParticipant::getParticipantAggregateId} — the only way
     * {@code invariantUniqueParticipant} names anything).
     */
    private List<String> memberEntities;

    /**
     * Scalar fields of the aggregate root that the rule reads.
     *
     * <p>Kept separate from {@link #memberEntities} because the root never moves in a split. A rule
     * that reads one member and one root scalar looks like it travels with the member and does not:
     * it straddles the cut. {@code invariantParticipantsEnrolledBeforeStarTime} is exactly that.
     */
    private List<String> rootScalars;

    /**
     * Whether the rule calls {@code getPrev()} to compare against the aggregate's previous version.
     *
     * <p>Such a rule <b>cannot</b> span a boundary — after a split each side has its own version
     * chain, so there is no single {@code prev} to compare against. It is not expensive to split; it
     * is impossible, and the only resolution is to duplicate it on both sides.
     */
    private boolean readsPrev;

    /** Whether the rule reads the aggregate's {@code state} — which lives on the root. */
    private boolean readsState;
}
