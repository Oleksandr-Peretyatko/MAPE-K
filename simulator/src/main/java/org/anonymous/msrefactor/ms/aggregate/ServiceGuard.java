package org.anonymous.msrefactor.ms.aggregate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the consistency rule (or rules) a service method enforces synchronously, before any mutation.
 *
 * <h3>Why a rule about another aggregate can live here</h3>
 *
 * <p>An aggregate sees only its own state, so a rule ranging over another one has two possible homes:
 * a subscription that reacts to that aggregate's events — eventual, and therefore unable to block —
 * or a check in the service method, which runs while the saga's remote read is still in hand. A rule
 * that must <em>refuse</em> an operation has to be the second.
 *
 * <p>{@code COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION} is exactly that: the quiz answer's course
 * execution must be the quiz's, it is checked once at creation, and neither side can change
 * afterwards, so no event could ever invalidate it.
 *
 * <h3>Why the link is written down</h3>
 *
 * <p>Without it, a rule the aggregate's header declares and no subscription maintains is
 * indistinguishable from a rule nobody implemented. The Monitor reported the one above as unbacked
 * while a guard eleven lines long enforced it.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>The values are rule names spelled <b>exactly</b> as in the declaring aggregate's header block
 *       — {@code INTER-INVARIANTS} or {@code SERVICE-LAYER GUARDS}.</li>
 *   <li>The declaring aggregate need not belong to this service.
 *       {@code UNIQUE_QUIZ_ANSWER_PER_STUDENT} is declared by {@code Quiz} and guarded in
 *       {@code QuizAnswerService}, so the names are matched across the whole application.</li>
 *   <li>One method may enforce several rules; list them all.</li>
 * </ul>
 *
 * <p>{@code SOURCE} retention: the only reader is static analysis over the source tree.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface ServiceGuard {

    /** Rule names as spelled in the declaring aggregate's header block. */
    String[] value();
}
