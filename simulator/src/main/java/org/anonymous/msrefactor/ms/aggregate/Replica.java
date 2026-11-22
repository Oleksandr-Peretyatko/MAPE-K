package org.anonymous.msrefactor.ms.aggregate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity class as a local copy of another aggregate's state.
 *
 * <h3>Why it is declared and not detected</h3>
 *
 * <p>A replica used to be recognised by its shape — a field named {@code <something>AggregateId} whose
 * prefix could be resolved to a service. Both halves of that guess fail in practice.
 *
 * <p>The prefix often names a <em>role</em> rather than the aggregate:
 * {@code TournamentCreator.creatorAggregateId} and {@code TournamentParticipant.participantAggregateId}
 * both point at {@code User}, and the only way the resolver reached that answer was its last-resort
 * step, "the declaring file imports exactly one foreign service". And the scan cannot tell a field
 * declaration from a method call, so {@code CourseExecutionStudent} — a copy of {@code User} — was
 * reported as a replica of {@code tournament} because its {@code equals} calls
 * {@code getParticipantAggregateId()} on a {@code TournamentParticipant}.
 *
 * <p>Declaring it removes both failure modes, and gives the absence a meaning: a class without this
 * annotation is a domain entity of its own aggregate, not a class the analysis failed to classify.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>The values are <b>aggregate root simple names</b> ({@code "User"}), not service names. Roots
 *       survive a service rename, and the analysis already maps a root to its service.</li>
 *   <li>List every root whose state the class copies, the primary one first. {@code QuestionTopic}
 *       carries a topic and, denormalised, the course that topic belongs to.</li>
 *   <li>A refactoring that creates such a class adds the annotation; one that moves it carries the
 *       annotation along; one that dissolves the boundary — a merge that makes the copied aggregate a
 *       local member — removes it, because the class stops being a replica.</li>
 * </ul>
 *
 * <p>{@code SOURCE} retention: the only reader is static analysis over the source tree.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Replica {

    /** Simple names of the aggregate roots this class holds a copy of, primary first. */
    String[] value();
}
