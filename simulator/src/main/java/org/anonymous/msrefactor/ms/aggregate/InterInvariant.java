package org.anonymous.msrefactor.ms.aggregate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the cross-aggregate rule (or rules) a method of {@code getEventSubscriptions()} maintains.
 *
 * <h3>Why the link has to be written down</h3>
 *
 * <p>An aggregate lists the rules it upholds in its {@code INTER-INVARIANTS} header and implements
 * them as private methods that register subscriptions. Nothing connects the two, and the names do
 * not line up: {@code USER_EXISTS} is implemented by {@code interInvariantStudentExists}, because
 * the local replica is called a student. Worse, one method can maintain two rules —
 * {@code interInvariantQuizExists} also upholds {@code QUESTION_EXISTS}, since a deleted question
 * makes the quiz publish its invalidation event.
 *
 * <p>Recovering that mapping by guessing at names is unreliable in both directions: it reports rules
 * as unimplemented when they are, and it cannot see a method that carries two. This annotation makes
 * the mapping part of the code, so it can be read literally rather than interpreted.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>Every method called from {@code getEventSubscriptions()} carries one.</li>
 *   <li>Its values are the rule names <b>exactly as spelled</b> in the aggregate's
 *       {@code INTER-INVARIANTS} header — that header stays the specification.</li>
 *   <li>A rule in the header that no annotation claims is a rule no subscription maintains. That is
 *       a legitimate state ({@code COURSE_EXISTS} holds because course publishes no events) and is
 *       reported as such, so the absence must be truthful rather than an oversight.</li>
 * </ul>
 *
 * <p>Refactoring operations that move or regenerate these methods carry the annotation with them;
 * dropping it does not break the build, it silently turns a maintained rule into an unclaimed one.
 *
 * <p>{@code SOURCE} retention: the only reader is static analysis over the source tree, and nothing
 * at runtime should branch on a documentation link.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface InterInvariant {

    /** Rule names from the aggregate's {@code INTER-INVARIANTS} header. */
    String[] value();
}
