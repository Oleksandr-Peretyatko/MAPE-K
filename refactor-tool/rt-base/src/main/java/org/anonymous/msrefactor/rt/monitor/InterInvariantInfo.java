package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One consistency rule an aggregate maintains across an aggregate boundary, together with the
 * subscriptions that keep it true and the state it ranges over.
 *
 * <h3>Why this is not the same thing as an event subscription</h3>
 *
 * <p>{@link ServiceStaticMetrics#getEventSubscriptions()} already records which event types a
 * service consumes and from whom. That is the transport, not the rule. Three subscriptions —
 * disenroll, anonymise, rename — maintain <em>one</em> rule ("the student still exists and its
 * cached copy matches"), and nothing in that map says so. The grouping is written in the code: each
 * method called from {@code getEventSubscriptions()} registers the subscriptions of exactly one
 * rule.
 *
 * <h3>Why an aggregate can look rule-less without this</h3>
 *
 * <p>{@code QuizAnswer.verifyInvariants()} is empty and every rule it enforces is cross-aggregate.
 * Collected through {@link InvariantInfo} alone it reports zero invariants, which reads as "nothing
 * constrains this aggregate" when the truth is the opposite.
 *
 * <h3>The two ends of the rule are different aggregates</h3>
 *
 * <p>{@link SubscriptionRef#publisherService} is where the events come from — the subscription
 * anchor. {@link #memberEntities} is the local replica state the rule constrains. They frequently
 * disagree: {@code QuizAnswer}'s student rules are anchored on the <em>course execution</em>, which
 * publishes the events, while the rule itself is about the <em>user</em>. Recording only the
 * publisher loses the {@code answer → user} dependency entirely, so both are kept.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterInvariantInfo {

    public enum Kind {
        /** A method called from {@code getEventSubscriptions()} — whatever it is named. */
        METHOD,
        /** A subscription registered directly in {@code getEventSubscriptions()}, with no method. */
        INLINE,
        /**
         * Declared in a header block and enforced synchronously by a service method that claims it
         * with {@code @ServiceGuard} — see {@link #enforcedIn}.
         *
         * <p>A rule that must refuse an operation cannot be held by subscription, because that is
         * eventual and eventual cannot block. It is still cross-aggregate, and still real; it simply
         * lives one layer down. Without the annotation these were indistinguishable from rules nobody
         * implemented.
         */
        SERVICE_GUARD,
        /**
         * Named in a header block and claimed by nothing — no {@code @InterInvariant}, no
         * {@code @ServiceGuard}.
         *
         * <p>Some are real and enforced by construction: {@code COURSE_EXISTS} holds because course
         * publishes no events, and the header says so. Others are aspirational. The collector cannot
         * tell which, so it reports the claim and its {@link #note}, and carries no footprint,
         * subscriptions or dependencies for it. Treat these as documentation, not as verified rules.
         */
        DECLARED
    }

    /**
     * The rule's name: the delegate method name with its {@code interInvariant} prefix removed, so
     * {@code interInvariantStudentExists} reads {@code StudentExists}. A delegate that does not
     * follow the prefix convention keeps its name as written — the prefix is stripped when present,
     * never required.
     */
    private String name;

    /** Simple name of the class the rule is written in. */
    private String declaredIn;

    private Kind kind;

    /**
     * The rule names from the aggregate's {@code INTER-INVARIANTS} header that this method claims,
     * read from its {@code @InterInvariant} annotation.
     *
     * <p>This is the link between the specification and the code, and it is read, never guessed. The
     * two do not line up by name — {@code USER_EXISTS} is implemented by
     * {@code interInvariantStudentExists} — and one method can claim two rules, which no name-based
     * matching can express at all.
     *
     * <p>Empty when the method carries no annotation. That is a gap in the source, not in the
     * analysis: the rules it maintains stay in the header as {@link Kind#DECLARED}, and the collector
     * logs it.
     */
    private List<String> declaredNames;

    /**
     * For a {@link Kind#DECLARED} or {@link Kind#SERVICE_GUARD} rule, whatever the header wrote after
     * its name — usually the reason there is no subscription, e.g. {@code (course doesn't send
     * events)}. Null otherwise.
     */
    private String note;

    /**
     * For a {@link Kind#SERVICE_GUARD} rule, where it is enforced:
     * {@code "QuizAnswerService.startQuiz"}.
     *
     * <p>Need not belong to the declaring aggregate's service. {@code UNIQUE_QUIZ_ANSWER_PER_STUDENT}
     * is declared by {@code Quiz} and guarded in {@code QuizAnswerService}, because that is where the
     * answer is created.
     */
    private String enforcedIn;

    /**
     * Member entity classes of this aggregate the rule ranges over.
     *
     * <p>Collected from two places, because neither alone is enough. The delegate body names the
     * entity when it iterates a collection ({@code for (QuestionTopic t : questionTopics)}) or
     * passes a member ({@code new QuizSubscribesDeleteCourseExecution(getQuizCourseExecution())}),
     * but names nothing at all when it passes the root ({@code new …UpdateStudentName(this)}) — and
     * that is the shape of every {@code QuizAnswer} rule. For those, the accessors the subscription
     * class itself calls on the aggregate are what identify the state.
     */
    private List<String> memberEntities;

    /**
     * Scalar fields of the aggregate root the rule reads. Same meaning as
     * {@link InvariantInfo#getRootScalars()}: a rule that touches one names state that does not move
     * in a split.
     */
    private List<String> rootScalars;

    /** Whether the rule reads the aggregate's own {@code state} outside the ACTIVE guard. */
    private boolean readsState;

    /**
     * Whether registration is wrapped in {@code if (getState() == ACTIVE)}.
     *
     * <p>False means the aggregate keeps subscribing after deletion, which is either deliberate or a
     * bug; either way it is worth seeing.
     */
    private boolean guardedByActiveState;

    /**
     * Whether the rule ranges over a collection (∀) rather than a single reference.
     *
     * <p>An iterated rule registers one subscription <em>per element</em>, so its cost grows with
     * the aggregate; a single-reference rule registers a fixed number.
     */
    private boolean iterated;

    /**
     * Whether registration is itself conditional beyond the ACTIVE guard — {@code
     * interInvariantQuizAnswersExist} only subscribes for participants that have actually started
     * answering. The rule holds vacuously for the rest.
     */
    private boolean conditional;

    /** The subscriptions that maintain this rule, in declaration order. */
    private List<SubscriptionRef> subscriptions;

    /**
     * Whether a subscription actually maintains this rule, as opposed to a service guard enforcing it
     * or nothing claiming it at all.
     *
     * <p>The one question every consumer asks: only these have transport a refactoring has to
     * re-home, so only these cost anything in a cut. Kept here so a new {@link Kind} needs no change
     * at the call sites.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBackedBySubscription() {
        return kind == Kind.METHOD || kind == Kind.INLINE;
    }

    /**
     * Every service this rule depends on: the publishers of its subscriptions, plus the services
     * whose data the local replicas in {@link #memberEntities} copy.
     *
     * <p>The union is the answer to "which aggregates does this rule span", which neither half gives
     * on its own. It is a <b>superset</b>: the replica half comes from
     * {@link ServiceStaticMetrics#getCrossServiceEntities()}, which records every foreign service a
     * class mentions, so a replica that happens to reference a second service contributes it too.
     * Use {@link SubscriptionRef#publisherService} when only the event source is wanted.
     */
    private List<String> dependsOnServices;

    /** One {@code EventSubscription} subclass registered by the rule. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SubscriptionRef {

        /** The {@code EventSubscription} subclass, e.g. {@code QuizAnswerSubscribesInvalidateQuiz}. */
        private String subscriptionClass;

        /** The event type it subscribes to, e.g. {@code InvalidateQuizEvent}. */
        private String eventType;

        /** The service that publishes the event; null when the anchor prefix did not resolve. */
        private String publisherService;

        /** That service's aggregate root, when it has exactly one; null otherwise. */
        private String publisherAggregate;
    }
}
