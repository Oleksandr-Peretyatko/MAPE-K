package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collector reported only intra-invariants, so an aggregate whose consistency is entirely
 * cross-aggregate came back with an empty rule list. {@code QuizAnswer} is exactly that: its
 * {@code verifyInvariants()} is empty and all five of its subscriptions maintain rules about the
 * user, the quiz and the course execution.
 *
 * <p>The grouping is recoverable because {@code getEventSubscriptions()} is a choke point: each
 * method it delegates to registers the subscriptions of one rule. These fixtures are transcribed
 * from the real aggregates; each is here because it breaks a different naive extractor.
 */
class InterInvariantCaptureTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    /**
     * The production signature takes the replica map as entity -> services and an application-wide
     * index of @ServiceGuard claims. These fixtures name one service per entity and, unless the test
     * is about a guard, no guards at all.
     */
    private List<InterInvariantInfo> extract(
            Map<String, String> contents, Set<String> entities,
            Map<String, StaticAnalysisCollector.SubscriptionScan> subscriptions,
            Map<String, String> rootByService, Map<String, String> replicaOwners) {
        return extract(contents, entities, subscriptions, rootByService, replicaOwners, Map.of());
    }

    private List<InterInvariantInfo> extract(
            Map<String, String> contents, Set<String> entities,
            Map<String, StaticAnalysisCollector.SubscriptionScan> subscriptions,
            Map<String, String> rootByService, Map<String, String> replicaOwners,
            Map<String, String> serviceGuards) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        replicaOwners.forEach((entity, service) -> owners.put(entity, List.of(service)));
        return collector.extractInterInvariants(
                contents, entities, subscriptions, rootByService, owners, serviceGuards);
    }

    // ─── Fixtures ────────────────────────────────────────────────────────────────

    /** The three subscription classes QuizAnswer's student rule registers, as Pass 3 indexes them. */
    private static final Map<String, StaticAnalysisCollector.SubscriptionScan> ANSWER_SUBSCRIPTIONS = Map.of(
            "QuizAnswerSubscribesRemoveCourseExecution",
            new StaticAnalysisCollector.SubscriptionScan("DeleteCourseExecutionEvent", "execution",
                    List.of("courseExecutionAggregateId", "courseExecutionVersion", "simpleName")),
            "QuizAnswerSubscribesInvalidateQuiz",
            new StaticAnalysisCollector.SubscriptionScan("InvalidateQuizEvent", "quiz",
                    List.of("quiz", "quizAggregateId", "quizVersion", "simpleName")),
            // Anchored on the course execution — which publishes the event — while the rule itself
            // is about the student. This is why the publisher alone is not the rule's subject.
            "QuizAnswerSubscribesUpdateStudentName",
            new StaticAnalysisCollector.SubscriptionScan("UpdateStudentNameEvent", "execution",
                    List.of("answerCourseExecution", "courseExecutionAggregateId", "student",
                            "studentAggregateId", "simpleName")),
            "QuizAnswerSubscribesAnonymizeStudent",
            new StaticAnalysisCollector.SubscriptionScan("AnonymizeStudentEvent", "execution",
                    List.of("answerCourseExecution", "courseExecutionAggregateId", "student",
                            "studentAggregateId", "simpleName")),
            "QuizAnswerSubscribesUnerollStudentFromCourseExecution",
            new StaticAnalysisCollector.SubscriptionScan("DisenrollStudentFromCourseExecutionEvent", "execution",
                    List.of("answerCourseExecution", "courseExecutionAggregateId", "student",
                            "studentAggregateId", "simpleName")));

    private static final Map<String, String> ANSWER_REPLICA_OWNERS = Map.of(
            "AnswerCourseExecution", "execution",
            "AnswerStudent", "user",
            "AnsweredQuiz", "quiz");

    private static final String QUIZ_ANSWER = """
            public abstract class QuizAnswer extends Aggregate {
                private LocalDateTime creationDate;
                private boolean completed;
                private AnswerCourseExecution answerCourseExecution;
                private AnswerStudent student;
                private AnsweredQuiz quiz;
                private List<QuestionAnswer> questionAnswers = new ArrayList<>();

                @Override
                public void verifyInvariants() {
                }

                @Override
                public Set<EventSubscription> getEventSubscriptions() {
                    Set<EventSubscription> eventSubscriptions = new HashSet<>();
                    if (getState() == ACTIVE) {
                        interInvariantCourseExecutionExists(eventSubscriptions);
                        interInvariantQuizExists(eventSubscriptions);
                        interInvariantStudentExists(eventSubscriptions);
                    }
                    return eventSubscriptions;
                }

                @InterInvariant("COURSE_EXECUTION_EXISTS")
                private void interInvariantCourseExecutionExists(Set<EventSubscription> eventSubscriptions) {
                    eventSubscriptions.add(new QuizAnswerSubscribesRemoveCourseExecution(this.getAnswerCourseExecution()));
                }

                @InterInvariant({ "QUIZ_EXISTS", "QUESTION_EXISTS" })
                private void interInvariantQuizExists(Set<EventSubscription> eventSubscriptions) {
                    eventSubscriptions.add(new QuizAnswerSubscribesInvalidateQuiz(this));
                }

                @InterInvariant("USER_EXISTS")
                private void interInvariantStudentExists(Set<EventSubscription> eventSubscriptions) {
                    eventSubscriptions.add(new QuizAnswerSubscribesUnerollStudentFromCourseExecution(this));
                    eventSubscriptions.add(new QuizAnswerSubscribesAnonymizeStudent(this));
                    eventSubscriptions.add(new QuizAnswerSubscribesUpdateStudentName(this));
                }
            }
            """;

    /** QuizAnswer's real INTER-INVARIANTS header: five rules, four of them claimed by a method. */
    private static final String QUIZ_ANSWER_HEADER = """
            /*
                INTRA-INVARIANTS:
                    FINAL_ANSWER_DATE
                INTER-INVARIANTS:
                    USER_EXISTS
                    QUIZ_EXISTS
                    QUESTION_EXISTS
                    COURSE_EXECUTION_EXISTS
                    COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION (verified at the service and the quiz doesnt change execution)
             */
            """;

    private List<InterInvariantInfo> extractAnswerWithHeader() {
        return extract(
                Map.of("QuizAnswer", QUIZ_ANSWER_HEADER + QUIZ_ANSWER),
                Set.of("QuizAnswer", "AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz",
                        "QuestionAnswer"),
                ANSWER_SUBSCRIPTIONS,
                Map.of("execution", "Execution", "quiz", "Quiz", "user", "User"),
                ANSWER_REPLICA_OWNERS);
    }

    private List<InterInvariantInfo> extractAnswer() {
        return extract(
                Map.of("QuizAnswer", QUIZ_ANSWER),
                Set.of("QuizAnswer", "AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz",
                        "QuestionAnswer"),
                ANSWER_SUBSCRIPTIONS,
                Map.of("execution", "Execution", "quiz", "Quiz", "user", "User"),
                ANSWER_REPLICA_OWNERS);
    }

    private InterInvariantInfo named(List<InterInvariantInfo> all, String name) {
        return all.stream().filter(i -> i.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("not captured: " + name
                        + " — captured " + all.stream().map(InterInvariantInfo::getName).toList()));
    }

    // ─── Recognition ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an aggregate with an empty verifyInvariants() still reports its rules")
    void capturesRulesOfAnAggregateWithNoIntraInvariants() {
        assertThat(extractAnswer())
                .extracting(InterInvariantInfo::getName)
                .containsExactlyInAnyOrder("CourseExecutionExists", "QuizExists", "StudentExists");
    }

    @Test
    @DisplayName("the interInvariant prefix is stripped, and a delegate without it keeps its name")
    void stripsThePrefixWhenPresentOnly() {
        List<InterInvariantInfo> found = extract(
                Map.of("Execution", """
                        public class Execution extends Aggregate {
                            private Set<CourseExecutionStudent> students = new HashSet<>();

                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                usersStillExist(eventSubscriptions);
                                return eventSubscriptions;
                            }

                            private void usersStillExist(Set<EventSubscription> eventSubscriptions) {
                                for (CourseExecutionStudent student : this.students) {
                                    eventSubscriptions.add(new CourseExecutionSubscribesRemoveUser(student));
                                }
                            }
                        }
                        """),
                Set.of("Execution", "CourseExecutionStudent"),
                Map.of("CourseExecutionSubscribesRemoveUser",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteUserEvent", "user", List.of())),
                Map.of("user", "User"), Map.of("CourseExecutionStudent", "user"));

        assertThat(found).extracting(InterInvariantInfo::getName).containsExactly("usersStillExist");
    }

    @Test
    @DisplayName("an aggregate that subscribes to nothing reports an empty list, not null")
    void capturesNothingWhenThereAreNoSubscriptions() {
        List<InterInvariantInfo> found = extract(
                Map.of("User", """
                        public class User extends Aggregate {
                            private String name;

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                return new HashSet<>();
                            }
                        }
                        """),
                Set.of("User"), Map.of(), Map.of(), Map.of());

        assertThat(found).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("a Saga/Causal variant does not double the rules it inherits")
    void ignoresNonEntityVariants() {
        // SagaQuizAnswer is in contentsByClass for cross-service reference scanning but is not an
        // entity; processing it too would report every rule twice.
        List<InterInvariantInfo> found = extract(
                Map.of("QuizAnswer", QUIZ_ANSWER, "SagaQuizAnswer", QUIZ_ANSWER),
                Set.of("QuizAnswer", "AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz"),
                ANSWER_SUBSCRIPTIONS, Map.of(), ANSWER_REPLICA_OWNERS);

        assertThat(found).hasSize(3);
    }

    // ─── Grouping ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("three subscriptions that maintain one rule are reported as one rule")
    void groupsSubscriptionsByTheRuleTheyMaintain() {
        InterInvariantInfo studentExists = named(extractAnswer(), "StudentExists");

        assertThat(studentExists.getSubscriptions())
                .extracting(InterInvariantInfo.SubscriptionRef::getEventType)
                .containsExactlyInAnyOrder("DisenrollStudentFromCourseExecutionEvent",
                        "AnonymizeStudentEvent", "UpdateStudentNameEvent");
    }

    @Test
    @DisplayName("the subscription names its publisher aggregate, not only the service")
    void namesThePublisherAggregate() {
        InterInvariantInfo quizExists = named(extractAnswer(), "QuizExists");

        assertThat(quizExists.getSubscriptions()).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.getSubscriptionClass()).isEqualTo("QuizAnswerSubscribesInvalidateQuiz");
                    assertThat(ref.getPublisherService()).isEqualTo("quiz");
                    assertThat(ref.getPublisherAggregate()).isEqualTo("Quiz");
                });
    }

    // ─── Footprint: the state the rule ranges over ───────────────────────────────

    @Test
    @DisplayName("a rule that passes `this` still names the state it constrains")
    void recoversTheFootprintFromTheSubscriptionClass() {
        // `new QuizAnswerSubscribesUpdateStudentName(this)` names no member at all. The accessors the
        // subscription calls on the aggregate are the only evidence left, and without them this rule
        // looks like it reads nothing.
        InterInvariantInfo studentExists = named(extractAnswer(), "StudentExists");

        assertThat(studentExists.getMemberEntities())
                .contains("AnswerStudent", "AnswerCourseExecution");
    }

    @Test
    @DisplayName("a rule that passes a member names it from the delegate body")
    void readsTheFootprintFromTheDelegateBody() {
        InterInvariantInfo courseExecutionExists = named(extractAnswer(), "CourseExecutionExists");

        assertThat(courseExecutionExists.getMemberEntities()).containsExactly("AnswerCourseExecution");
    }

    @Test
    @DisplayName("an accessor on a wrapper resolves to the member it names")
    void recoversTheFootprintWhenTheAccessorIsNotAFieldName() {
        // TournamentSubscribesDisenrollStudentFromCourseExecution keeps a TournamentDto and reads
        // getCreator()/getParticipants(); the fields are tournamentCreator/tournamentParticipants.
        // Resolving the accessor as a field finds nothing, and the rule named CreatorExists then
        // reports only the course execution it is anchored on.
        List<InterInvariantInfo> found = extract(
                Map.of("Tournament", """
                        public class Tournament extends Aggregate {
                            private TournamentCreator tournamentCreator;
                            private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();
                            private TournamentCourseExecution tournamentCourseExecution;

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                if (this.getState() == AggregateState.ACTIVE) {
                                    interInvariantCreatorExists(eventSubscriptions);
                                }
                                return eventSubscriptions;
                            }

                            private void interInvariantCreatorExists(Set<EventSubscription> eventSubscriptions) {
                                eventSubscriptions.add(new TournamentSubscribesAnonymizeStudent(this));
                            }
                        }
                        """),
                Set.of("Tournament", "TournamentCreator", "TournamentParticipant",
                        "TournamentParticipantQuizAnswer", "TournamentCourseExecution"),
                Map.of("TournamentSubscribesAnonymizeStudent",
                        new StaticAnalysisCollector.SubscriptionScan("AnonymizeStudentEvent", "execution",
                                List.of("tournamentCourseExecution", "courseExecutionAggregateId",
                                        "creator", "participants", "aggregateId", "simpleName"))),
                Map.of("execution", "Execution"),
                Map.of("TournamentCreator", "user", "TournamentParticipant", "user",
                        "TournamentCourseExecution", "execution"));

        InterInvariantInfo creatorExists = named(found, "CreatorExists");
        assertThat(creatorExists.getMemberEntities())
                .contains("TournamentCreator", "TournamentParticipant", "TournamentCourseExecution");
        assertThat(creatorExists.getDependsOnServices()).contains("user");
    }

    @Test
    @DisplayName("an ambiguous accessor resolves to nothing rather than guessing")
    void doesNotGuessAnAmbiguousAccessor() {
        List<InterInvariantInfo> found = extract(
                Map.of("Tournament", """
                        public class Tournament extends Aggregate {
                            private TournamentCourseExecution tournamentCourseExecution;

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                interInvariantQuizExists(eventSubscriptions);
                                return eventSubscriptions;
                            }

                            private void interInvariantQuizExists(Set<EventSubscription> eventSubscriptions) {
                                eventSubscriptions.add(new TournamentSubscribesInvalidateQuiz(this));
                            }
                        }
                        """),
                // Two entities end in "Quiz": nothing about getQuiz() says which.
                Set.of("Tournament", "TournamentQuiz", "ParticipantQuiz", "TournamentCourseExecution"),
                Map.of("TournamentSubscribesInvalidateQuiz",
                        new StaticAnalysisCollector.SubscriptionScan("InvalidateQuizEvent", "quiz",
                                List.of("quiz", "quizAggregateId"))),
                Map.of("quiz", "Quiz"), Map.of());

        assertThat(named(found, "QuizExists").getMemberEntities()).isEmpty();
    }

    @Test
    @DisplayName("dependsOnServices unions the publisher with the service the replica copies")
    void unionsPublisherAndReplicaOwner() {
        // Every subscription of this rule is published by `execution`; the rule is about `user`.
        // Recording only the publisher loses answer → user entirely.
        InterInvariantInfo studentExists = named(extractAnswer(), "StudentExists");

        assertThat(studentExists.getDependsOnServices()).containsExactlyInAnyOrder("execution", "user");
    }

    // ─── Shape of the rule ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a rule that iterates a collection is marked iterated")
    void marksIteratedRules() {
        List<InterInvariantInfo> found = extract(
                Map.of("Question", """
                        public class Question extends Aggregate {
                            private QuestionCourse questionCourse;
                            private Set<QuestionTopic> questionTopics = new HashSet<>();

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                if (getState() == ACTIVE) {
                                    interInvariantTopicsExist(eventSubscriptions);
                                }
                                return eventSubscriptions;
                            }

                            private void interInvariantTopicsExist(Set<EventSubscription> eventSubscriptions) {
                                for (QuestionTopic topic : this.questionTopics) {
                                    eventSubscriptions.add(new QuestionSubscribesDeleteTopic(topic));
                                    eventSubscriptions.add(new QuestionSubscribesUpdateTopic(topic));
                                }
                            }
                        }
                        """),
                Set.of("Question", "QuestionCourse", "QuestionTopic"),
                Map.of("QuestionSubscribesDeleteTopic",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteTopicEvent", "topic", List.of()),
                        "QuestionSubscribesUpdateTopic",
                        new StaticAnalysisCollector.SubscriptionScan("UpdateTopicEvent", "topic", List.of())),
                Map.of("topic", "Topic"), Map.of("QuestionTopic", "topic"));

        InterInvariantInfo topicsExist = named(found, "TopicsExist");
        assertThat(topicsExist.isIterated()).isTrue();
        assertThat(topicsExist.isConditional()).isFalse();
        assertThat(topicsExist.getMemberEntities()).containsExactly("QuestionTopic");
        assertThat(topicsExist.getSubscriptions()).hasSize(2);
    }

    @Test
    @DisplayName("a rule that subscribes only for some elements is marked conditional")
    void marksConditionalRules() {
        List<InterInvariantInfo> found = extract(
                Map.of("Tournament", """
                        public class Tournament extends Aggregate {
                            private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                if (this.getState() == AggregateState.ACTIVE) {
                                    interInvariantQuizAnswersExist(eventSubscriptions);
                                }
                                return eventSubscriptions;
                            }

                            private void interInvariantQuizAnswersExist(Set<EventSubscription> eventSubscriptions) {
                                for (TournamentParticipant tournamentParticipant : this.tournamentParticipants) {
                                    if (tournamentParticipant.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                                        eventSubscriptions.add(new TournamentSubscribesAnswerQuestion(tournamentParticipant));
                                    }
                                }
                            }
                        }
                        """,
                        "TournamentParticipant", """
                        public class TournamentParticipant {
                            private TournamentParticipantQuizAnswer participantAnswer;
                        }
                        """),
                Set.of("Tournament", "TournamentParticipant", "TournamentParticipantQuizAnswer"),
                Map.of("TournamentSubscribesAnswerQuestion",
                        new StaticAnalysisCollector.SubscriptionScan("QuizAnswerQuestionAnswerEvent",
                                "answer", List.of())),
                Map.of("answer", "QuizAnswer"), Map.of("TournamentParticipantQuizAnswer", "answer"));

        InterInvariantInfo rule = named(found, "QuizAnswersExist");
        assertThat(rule.isConditional()).isTrue();
        assertThat(rule.isIterated()).isTrue();
        // The nested member is reached through its parent's accessor, so resolving against the root's
        // own fields alone would drop it.
        assertThat(rule.getMemberEntities())
                .contains("TournamentParticipant", "TournamentParticipantQuizAnswer");
    }

    @Test
    @DisplayName("the ACTIVE guard is recorded, and its absence is too")
    void recordsTheActiveGuard() {
        assertThat(extractAnswer()).allMatch(InterInvariantInfo::isGuardedByActiveState);

        List<InterInvariantInfo> unguarded = extract(
                Map.of("Quiz", """
                        public class Quiz extends Aggregate {
                            private QuizCourseExecution quizCourseExecution;

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                interInvariantCourseExecutionExists(eventSubscriptions);
                                return eventSubscriptions;
                            }

                            private void interInvariantCourseExecutionExists(Set<EventSubscription> eventSubscriptions) {
                                eventSubscriptions.add(new QuizSubscribesDeleteCourseExecution(this.getQuizCourseExecution()));
                            }
                        }
                        """),
                Set.of("Quiz", "QuizCourseExecution"),
                Map.of("QuizSubscribesDeleteCourseExecution",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteCourseExecutionEvent",
                                "execution", List.of())),
                Map.of("execution", "Execution"), Map.of("QuizCourseExecution", "execution"));

        assertThat(named(unguarded, "CourseExecutionExists").isGuardedByActiveState()).isFalse();
    }

    @Test
    @DisplayName("a subscription registered with no method of its own is still a rule")
    void capturesInlineRegistration() {
        List<InterInvariantInfo> found = extract(
                Map.of("Quiz", """
                        public class Quiz extends Aggregate {
                            private QuizCourseExecution quizCourseExecution;

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                eventSubscriptions.add(new QuizSubscribesDeleteCourseExecution(this.getQuizCourseExecution()));
                                return eventSubscriptions;
                            }
                        }
                        """),
                Set.of("Quiz", "QuizCourseExecution"),
                Map.of("QuizSubscribesDeleteCourseExecution",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteCourseExecutionEvent",
                                "execution", List.of())),
                Map.of("execution", "Execution"), Map.of("QuizCourseExecution", "execution"));

        assertThat(found).singleElement().satisfies(rule -> {
            assertThat(rule.getKind()).isEqualTo(InterInvariantInfo.Kind.INLINE);
            assertThat(rule.getName()).isEqualTo("QuizSubscribesDeleteCourseExecution");
            assertThat(rule.getMemberEntities()).containsExactly("QuizCourseExecution");
        });
    }

    // ─── Rules the header claims but no subscription backs ───────────────────────

    @Test
    @DisplayName("a header rule with no subscription is reported as declared, with its reason")
    void reportsDeclaredRulesThatNoSubscriptionBacks() {
        // COURSE_EXISTS is real and the header says why it needs no subscription. Dropping it would
        // report the aggregate as depending on less than it does.
        List<InterInvariantInfo> found = extract(
                Map.of("Question", """
                        /*
                            INTRA-INVARIANTS:
                                TOPIC_BELONGS_TO_QUESTION_COURSE
                            INTER-INVARIANTS:
                                TOPICS_EXIST
                                COURSE_EXISTS (course does not send events)
                         */
                        public class Question extends Aggregate {
                            private Set<QuestionTopic> questionTopics = new HashSet<>();

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                interInvariantTopicsExist(eventSubscriptions);
                                return eventSubscriptions;
                            }

                            @InterInvariant("TOPICS_EXIST")
                            private void interInvariantTopicsExist(Set<EventSubscription> eventSubscriptions) {
                                for (QuestionTopic topic : this.questionTopics) {
                                    eventSubscriptions.add(new QuestionSubscribesDeleteTopic(topic));
                                }
                            }
                        }
                        """),
                Set.of("Question", "QuestionTopic"),
                Map.of("QuestionSubscribesDeleteTopic",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteTopicEvent", "topic", List.of())),
                Map.of("topic", "Topic"), Map.of());

        // TOPICS_EXIST is claimed by the delegate, so it is not repeated as declared.
        assertThat(found).extracting(InterInvariantInfo::getName)
                .containsExactly("TopicsExist", "COURSE_EXISTS");

        InterInvariantInfo declared = named(found, "COURSE_EXISTS");
        assertThat(declared.getKind()).isEqualTo(InterInvariantInfo.Kind.DECLARED);
        assertThat(declared.getNote()).isEqualTo("(course does not send events)");
        assertThat(declared.getSubscriptions()).isEmpty();
        assertThat(declared.getMemberEntities()).isEmpty();
    }

    // ─── The rule ↔ method link ─────────────────────────────────────────────────

    @Test
    @DisplayName("the annotation links a rule to a method whose name shares nothing with it")
    void linksRulesWhoseNamesDoNotMatch() {
        // USER_EXISTS in the header, interInvariantStudentExists in the code, because the local
        // replica of that user is a student. No comparison of the two names can succeed, and the
        // rule would be reported as unimplemented while three subscriptions maintain it.
        InterInvariantInfo studentExists = named(extractAnswerWithHeader(), "StudentExists");

        assertThat(studentExists.getDeclaredNames()).containsExactly("USER_EXISTS");
        assertThat(extractAnswerWithHeader())
                .noneMatch(r -> r.getName().equals("USER_EXISTS"));
    }

    @Test
    @DisplayName("one method can claim two rules — a name could only ever carry one")
    void oneMethodCanMaintainTwoRules() {
        // interInvariantQuizExists also upholds QUESTION_EXISTS: a deleted question makes the quiz
        // publish the invalidation event this subscribes to.
        List<InterInvariantInfo> found = extractAnswerWithHeader();

        assertThat(named(found, "QuizExists").getDeclaredNames())
                .containsExactly("QUIZ_EXISTS", "QUESTION_EXISTS");
        assertThat(found).noneMatch(r -> r.getName().equals("QUESTION_EXISTS"));
    }

    @Test
    @DisplayName("only the rule no method claims stays declared")
    void leavesOnlyTheGenuinelyUnbackedRule() {
        // The real QuizAnswer: five rules in the header, four claimed by three methods. The fifth is
        // a service-layer guard, and the header says so.
        assertThat(extractAnswerWithHeader())
                .filteredOn(r -> r.getKind() == InterInvariantInfo.Kind.DECLARED)
                .extracting(InterInvariantInfo::getName)
                .containsExactly("COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION");
    }

    @Test
    @DisplayName("a method with no annotation claims nothing, and its rules stay declared")
    void anUnannotatedMethodClaimsNothing() {
        // The honest report when a refactoring drops the annotation: the subscription is still found,
        // but nothing links it to the header, so the rule reads as unbacked. Guessing here is what
        // the annotation exists to stop.
        List<InterInvariantInfo> found = extract(
                Map.of("Execution", """
                        /*
                            INTER-INVARIANTS
                                USER_EXISTS
                         */
                        public class Execution extends Aggregate {
                            private Set<CourseExecutionStudent> students = new HashSet<>();

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                interInvariantUsersExist(eventSubscriptions);
                                return eventSubscriptions;
                            }

                            private void interInvariantUsersExist(Set<EventSubscription> eventSubscriptions) {
                                for (CourseExecutionStudent student : this.students) {
                                    eventSubscriptions.add(new CourseExecutionSubscribesRemoveUser(student));
                                }
                            }
                        }
                        """),
                Set.of("Execution", "CourseExecutionStudent"),
                Map.of("CourseExecutionSubscribesRemoveUser",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteUserEvent", "user", List.of())),
                Map.of("user", "User"), Map.of("CourseExecutionStudent", "user"));

        assertThat(named(found, "UsersExist").getDeclaredNames()).isEmpty();
        assertThat(named(found, "USER_EXISTS").getKind()).isEqualTo(InterInvariantInfo.Kind.DECLARED);
    }

    // ─── Rules enforced in the service layer ────────────────────────────────────

    @Test
    @DisplayName("a rule a @ServiceGuard claims is enforced, not missing")
    void aGuardedRuleIsNotReportedAsUnimplemented() {
        // COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION cannot be held by subscription: it must refuse
        // the operation, and eventual cannot refuse. Reporting it as unbacked said a guard eleven
        // lines long did not exist.
        List<InterInvariantInfo> found = extract(
                Map.of("QuizAnswer", QUIZ_ANSWER_HEADER + QUIZ_ANSWER),
                Set.of("QuizAnswer", "AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz"),
                ANSWER_SUBSCRIPTIONS,
                Map.of("execution", "Execution", "quiz", "Quiz", "user", "User"),
                ANSWER_REPLICA_OWNERS,
                Map.of("COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION", "QuizAnswerService.startQuiz"));

        InterInvariantInfo guarded = named(found, "COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION");
        assertThat(guarded.getKind()).isEqualTo(InterInvariantInfo.Kind.SERVICE_GUARD);
        assertThat(guarded.getEnforcedIn()).isEqualTo("QuizAnswerService.startQuiz");
        assertThat(guarded.isBackedBySubscription())
                .as("a guard has no subscription for a split to re-home")
                .isFalse();
        assertThat(found).noneMatch(r -> r.getKind() == InterInvariantInfo.Kind.DECLARED);
    }

    @Test
    @DisplayName("the guard may live in another service than the aggregate that declares the rule")
    void guardsAreMatchedAcrossServices() {
        // Quiz declares UNIQUE_QUIZ_ANSWER_PER_STUDENT; QuizAnswerService guards it, because that is
        // where the answer is created. An index scoped per service would never find it.
        List<InterInvariantInfo> found = extract(
                Map.of("Quiz", """
                        /*
                            INTER-INVARIANTS
                                UNIQUE_QUIZ_ANSWER_PER_STUDENT (Layer 2 guard in QuizAnswerService.startQuiz)
                         */
                        public class Quiz extends Aggregate {
                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                return new HashSet<>();
                            }
                        }
                        """),
                Set.of("Quiz"), Map.of(), Map.of(), Map.of(),
                Map.of("UNIQUE_QUIZ_ANSWER_PER_STUDENT", "QuizAnswerService.startQuiz"));

        InterInvariantInfo guarded = named(found, "UNIQUE_QUIZ_ANSWER_PER_STUDENT");
        assertThat(guarded.getKind()).isEqualTo(InterInvariantInfo.Kind.SERVICE_GUARD);
        assertThat(guarded.getNote()).isEqualTo("(Layer 2 guard in QuizAnswerService.startQuiz)");
    }

    @Test
    @DisplayName("the SERVICE-LAYER GUARDS block is read too, not just INTER-INVARIANTS")
    void readsTheServiceGuardHeaderBlock() {
        // Execution declares NO_DUPLICATE_COURSE_EXECUTION in its own block. Reading only the
        // INTER-INVARIANTS one left the @ServiceGuard pointing at a rule the collector never saw.
        List<InterInvariantInfo> found = extract(
                Map.of("Execution", """
                        /*
                            INTRA-INVARIANTS
                                REMOVE_NO_STUDENTS
                            SERVICE-LAYER GUARDS (Layer 2)
                                NO_DUPLICATE_COURSE_EXECUTION (enforced in ExecutionService.createCourseExecution)
                            INTER-INVARIANTS
                                COURSE_EXISTS (course doesn't send events)
                         */
                        public class Execution extends Aggregate {
                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                return new HashSet<>();
                            }
                        }
                        """),
                Set.of("Execution"), Map.of(), Map.of(), Map.of(),
                Map.of("NO_DUPLICATE_COURSE_EXECUTION", "ExecutionService.createCourseExecution"));

        assertThat(named(found, "NO_DUPLICATE_COURSE_EXECUTION").getKind())
                .isEqualTo(InterInvariantInfo.Kind.SERVICE_GUARD);
        // Still unbacked, and correctly so: course publishes no events and there is no guard either.
        assertThat(named(found, "COURSE_EXISTS").getKind())
                .isEqualTo(InterInvariantInfo.Kind.DECLARED);
    }

    @Test
    @DisplayName("a @ServiceGuard is read off the annotated method, name and all")
    void readsGuardClaimsFromSource() {
        Map<String, String> claims = collector.serviceGuardClaims(Map.of("QuizAnswerService", """
                @Service
                public class QuizAnswerService {
                    @ServiceGuard({ "COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION", "UNIQUE_QUIZ_ANSWER_PER_STUDENT" })
                    @Transactional(isolation = Isolation.SERIALIZABLE)
                    public QuizAnswerDto startQuiz(Integer quizAggregateId, UnitOfWork unitOfWork) {
                    }

                    @Transactional(isolation = Isolation.SERIALIZABLE)
                    public void concludeQuiz(Integer quizAggregateId, UnitOfWork unitOfWork) {
                    }
                }
                """));

        // Both rules, one method, and the @Transactional between annotation and signature does not
        // hide it.
        assertThat(claims).containsExactly(
                Map.entry("COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION", "QuizAnswerService.startQuiz"),
                Map.entry("UNIQUE_QUIZ_ANSWER_PER_STUDENT", "QuizAnswerService.startQuiz"));
    }

    @Test
    @DisplayName("an aggregate with no header block reports only what it registers")
    void toleratesAMissingHeader() {
        assertThat(extractAnswer()).allMatch(r -> r.getKind() == InterInvariantInfo.Kind.METHOD);
    }

    @Test
    @DisplayName("a constructor call that is not an indexed subscription is not a subscription")
    void ignoresUnrelatedConstructorCalls() {
        List<InterInvariantInfo> found = extract(
                Map.of("Quiz", """
                        public class Quiz extends Aggregate {
                            private QuizCourseExecution quizCourseExecution;

                            @Override
                            public Set<EventSubscription> getEventSubscriptions() {
                                Set<EventSubscription> eventSubscriptions = new HashSet<>();
                                interInvariantCourseExecutionExists(eventSubscriptions);
                                return eventSubscriptions;
                            }

                            private void interInvariantCourseExecutionExists(Set<EventSubscription> eventSubscriptions) {
                                QuizCourseExecutionDto dto = new QuizCourseExecutionDto(this.getQuizCourseExecution());
                                eventSubscriptions.add(new QuizSubscribesDeleteCourseExecution(dto));
                            }
                        }
                        """),
                Set.of("Quiz", "QuizCourseExecution"),
                Map.of("QuizSubscribesDeleteCourseExecution",
                        new StaticAnalysisCollector.SubscriptionScan("DeleteCourseExecutionEvent",
                                "execution", List.of())),
                Map.of("execution", "Execution"), Map.of());

        assertThat(named(found, "CourseExecutionExists").getSubscriptions())
                .extracting(InterInvariantInfo.SubscriptionRef::getSubscriptionClass)
                .containsExactly("QuizSubscribesDeleteCourseExecution");
    }
}
