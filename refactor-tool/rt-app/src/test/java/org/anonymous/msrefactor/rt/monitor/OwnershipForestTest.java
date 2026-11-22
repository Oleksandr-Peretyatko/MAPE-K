package org.anonymous.msrefactor.rt.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ownership verdicts of the reference application, one assertion per row of
 * {@code quizzes-composition.md} §3.
 *
 * <p>The fixture is transcribed from the real classes: the {@code @Replica} declarations, the
 * {@code @OneToOne} members of each root, the controllers' POST paths and the commands each service
 * handles. If the application changes shape, this test is where the drift shows.
 */
class OwnershipForestTest {

    // ─── The eight roots, as the collector would report them ─────────────────────

    private static final OwnershipForest.Unit COURSE = new OwnershipForest.Unit(
            "course", "Course",
            List.of(),
            Map.of(),
            List.of(),
            List.of("GetCourseByIdCommand", "DeleteCourseCommand", "UpdateCourseQuestionCountCommand"));

    private static final OwnershipForest.Unit USER = new OwnershipForest.Unit(
            "user", "User",
            List.of(),
            Map.of(),
            List.of("/users/create", "/users/{userAggregateId}/activate", "/users/{userAggregateId}/delete"),
            List.of("CreateUserCommand", "GetUserByIdCommand", "GetStudentsCommand"));

    private static final OwnershipForest.Unit TOPIC = new OwnershipForest.Unit(
            "topic", "Topic",
            List.of("TopicCourse"),
            Map.of("Course", List.of("TopicCourse")),
            List.of("/courses/{courseAggregateId}/create", "/topics/update", "/topics/{topicAggregateId}/delete"),
            List.of("CreateTopicCommand", "FindTopicsByCourseIdCommand", "GetTopicByIdCommand"));

    private static final OwnershipForest.Unit QUESTION = new OwnershipForest.Unit(
            "question", "Question",
            List.of("QuestionCourse"),                              // Set<QuestionTopic> is not one
            Map.of("Course", List.of("QuestionCourse", "QuestionTopic"),
                   "Topic", List.of("QuestionTopic")),
            List.of("/courses/{courseAggregateId}/questions/create", "/questions/update",
                    "/questions/{questionAggregateId}/updateTopics"),
            List.of("CreateQuestionCommand", "FindQuestionsByCourseAggregateIdCommand",
                    "FindQuestionsByTopicIdsCommand", "GetQuestionByIdCommand"));

    private static final OwnershipForest.Unit EXECUTION = new OwnershipForest.Unit(
            "execution", "Execution",
            List.of("CourseExecutionCourse"),                       // Set<CourseExecutionStudent> is not one
            Map.of("Course", List.of("CourseExecutionCourse"),
                   "User", List.of("CourseExecutionStudent")),
            List.of("/executions/create", "/executions/{executionAggregateId}/delete",
                    "/executions/{executionAggregateId}/students/{userAggregateId}/update/name"),
            List.of("CreateCourseExecutionCommand", "GetCourseExecutionsByUserIdCommand",
                    "GetStudentByExecutionIdAndUserIdCommand", "GetAllCourseExecutionsCommand"));

    private static final OwnershipForest.Unit QUIZ = new OwnershipForest.Unit(
            "quiz", "Quiz",
            List.of("QuizCourseExecution"),                         // Set<QuizQuestion> is not one
            Map.of("Execution", List.of("QuizCourseExecution"),
                   "Question", List.of("QuizQuestion")),
            List.of("/executions/{courseExecutionId}", "/quizzes/update"),
            List.of("CreateQuizCommand", "GetQuizByIdCommand", "GetAvailableQuizzesCommand"));

    private static final OwnershipForest.Unit ANSWER = new OwnershipForest.Unit(
            "answer", "QuizAnswer",
            List.of("AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz"),
            Map.of("Execution", List.of("AnswerCourseExecution"),
                   "User", List.of("AnswerStudent"),
                   "Quiz", List.of("AnsweredQuiz"),
                   "Question", List.of("AnsweredQuiz", "QuestionAnswer")),
            List.of("/quizzes/{quizAggregateId}/answer"),
            List.of("StartQuizCommand", "GetQuizAnswerByQuizIdAndUserIdCommand",
                    "AnswerQuestionCommand"));

    private static final OwnershipForest.Unit TOURNAMENT = new OwnershipForest.Unit(
            "tournament", "Tournament",
            List.of("TournamentCreator", "TournamentCourseExecution", "TournamentQuiz"),
            Map.of("Execution", List.of("TournamentCourseExecution"),
                   "User", List.of("TournamentCreator", "TournamentParticipant"),
                   "Quiz", List.of("TournamentQuiz"),
                   "Topic", List.of("TournamentTopic"),
                   "QuizAnswer", List.of("TournamentParticipantQuizAnswer")),
            List.of("/executions/{executionId}/tournaments/create", "/tournaments/update",
                    "/tournaments/{tournamentAggregateId}/join"),
            List.of("CreateTournamentCommand", "GetTournamentsByCourseExecutionIdCommand",
                    "AddParticipantCommand", "SolveQuizCommand"));

    private static final List<OwnershipForest.Unit> QUIZZES =
            List.of(COURSE, USER, TOPIC, QUESTION, EXECUTION, QUIZ, ANSWER, TOURNAMENT);

    private OwnershipForest.Edge ownerOf(String root) {
        return OwnershipForest.owners(QUIZZES).get(root);
    }

    // ─── Verdicts ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the five ownership edges, with their signal counts")
    void theFiveOwners() {
        assertThat(ownerOf("Topic")).satisfies(e -> {
            assertThat(e.verdict()).isEqualTo(OwnershipForest.Verdict.OWNED);
            assertThat(e.ownerRoot()).isEqualTo("Course");
            assertThat(e.signals().count()).isEqualTo(3);
            assertThat(e.signals().viaMember()).isEqualTo("TopicCourse");
        });
        assertThat(ownerOf("Question")).satisfies(e -> {
            assertThat(e.ownerRoot()).isEqualTo("Course");
            assertThat(e.signals().count()).isEqualTo(3);
        });
        assertThat(ownerOf("Tournament")).satisfies(e -> {
            assertThat(e.ownerRoot()).isEqualTo("Execution");
            assertThat(e.ownerService()).isEqualTo("execution");
            assertThat(e.signals().count()).isEqualTo(3);
        });
        assertThat(ownerOf("QuizAnswer")).satisfies(e -> {
            assertThat(e.ownerRoot()).isEqualTo("Quiz");
            assertThat(e.signals().count()).isEqualTo(3);
        });
        assertThat(ownerOf("Quiz")).satisfies(e -> {
            assertThat(e.verdict()).isEqualTo(OwnershipForest.Verdict.OWNED);
            assertThat(e.ownerRoot()).isEqualTo("Execution");
            assertThat(e.signals().count()).isEqualTo(2);      // O1 + O2, no parent-scoped query
        });
    }

    @Test
    @DisplayName("QuizAnswer resolves to Quiz over its two rival owners")
    void multipleCandidatesResolveBySignalCount() {
        OwnershipForest.Edge e = ownerOf("QuizAnswer");

        assertThat(e.rivalRoots()).contains("Quiz", "User", "Execution");
        assertThat(e.ownerRoot()).isEqualTo("Quiz");
    }

    @Test
    @DisplayName("a dual @Replica does not make the second root an owner")
    void dualReplicaDoesNotOwn() {
        // AnsweredQuiz is @Replica({"Quiz", "Question"}), so Question is an O1 candidate of QuizAnswer
        assertThat(OwnershipForest.o1(
                new OwnershipForest.Unit("question", "Question", List.of(), Map.of(), List.of(), List.of()),
                ANSWER)).isTrue();
        // …and loses on signals: no creation scope and no parent-scoped query.
        assertThat(ownerOf("QuizAnswer").ownerRoot()).isEqualTo("Quiz");
    }

    @Test
    @DisplayName("Execution is ambiguous: the link exists, the rest of the rule fails")
    void executionIsAmbiguous() {
        OwnershipForest.Edge e = ownerOf("Execution");

        assertThat(e.verdict()).isEqualTo(OwnershipForest.Verdict.AMBIGUOUS);
        assertThat(e.ownerRoot()).isNull();
        assertThat(e.rivalRoots()).containsExactly("Course");
    }

    @Test
    @DisplayName("the two roots own nothing and are owned by nothing")
    void rootsHaveNoOwner() {
        assertThat(ownerOf("Course").verdict()).isEqualTo(OwnershipForest.Verdict.NONE);
        assertThat(ownerOf("User").verdict()).isEqualTo(OwnershipForest.Verdict.NONE);
    }

    @Test
    @DisplayName("a collection member is never an ownership candidate")
    void collectionMembersAreNotCandidates() {
        // Topic is a candidate owner of Question only through QuestionTopic, which is a Set.
        assertThat(OwnershipForest.o1(TOPIC, QUESTION)).isFalse();
        // The same class as a single-valued member would be one.
        OwnershipForest.Unit questionHoldingTopicOneToOne = new OwnershipForest.Unit(
                "question", "Question", List.of("QuestionTopic"),
                Map.of("Topic", List.of("QuestionTopic")), List.of(), List.of());
        assertThat(OwnershipForest.o1(TOPIC, questionHoldingTopicOneToOne)).isTrue();
    }

    // ─── Signals in isolation ────────────────────────────────────────────────────

    @Test
    @DisplayName("a parent-scoped query names the parent immediately before Id")
    void o3MatchesTheParentOnly() {
        assertThat(OwnershipForest.o3(EXECUTION, TOURNAMENT)).isTrue();     // …ByCourseExecutionId
        assertThat(OwnershipForest.o3(COURSE, TOURNAMENT)).isFalse();       // the same name is not Course's
        assertThat(OwnershipForest.o3(COURSE, QUESTION)).isTrue();          // …ByCourseAggregateId
        assertThat(OwnershipForest.o3(COURSE, EXECUTION)).isFalse();        // queried by user, not by course
    }

    @Test
    @DisplayName("creation scope is read from the path variable's name")
    void o2ReadsThePathVariable() {
        assertThat(OwnershipForest.o2(COURSE, TOPIC)).isTrue();             // /courses/{courseAggregateId}/create
        assertThat(OwnershipForest.o2(EXECUTION, QUIZ)).isTrue();           // /executions/{courseExecutionId}
        assertThat(OwnershipForest.o2(COURSE, EXECUTION)).isFalse();        // /executions/create carries no id
        assertThat(OwnershipForest.o2(USER, TOURNAMENT)).isFalse();
    }

    // ─── Chains ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the chain starts with the aggregate itself and follows O1")
    void ownerChains() {
        assertThat(OwnershipForest.ownerChain("Execution", QUIZZES)).containsExactly("Execution", "Course");
        assertThat(OwnershipForest.ownerChain("Topic", QUIZZES)).containsExactly("Topic", "Course");
        assertThat(OwnershipForest.ownerChain("Course", QUIZZES)).containsExactly("Course");
        assertThat(OwnershipForest.ownerChain("QuizAnswer", QUIZZES))
                .containsExactly("QuizAnswer", "Quiz", "Execution", "Course");
    }

    @Test
    @DisplayName("siblings meet at their common owner")
    void lowestCommonAncestors() {
        assertThat(OwnershipForest.lowestCommonAncestor("Question", "Topic", QUIZZES)).contains("Course");
        assertThat(OwnershipForest.lowestCommonAncestor("Tournament", "QuizAnswer", QUIZZES))
                .contains("Execution");
        // The degenerate shape: the ancestor is one of the operands.
        assertThat(OwnershipForest.lowestCommonAncestor("Course", "Execution", QUIZZES)).contains("Course");
        assertThat(OwnershipForest.lowestCommonAncestor("User", "Topic", QUIZZES)).isEmpty();
    }

    // ─── Missing data ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a snapshot with no cardinality at all is unanswerable, not empty")
    void snapshotWithoutTheField() {
        List<OwnershipForest.Unit> old = List.of(
                new OwnershipForest.Unit("topic", "Topic", null, null, List.of(), List.of()),
                new OwnershipForest.Unit("course", "Course", null, null, List.of(), List.of()));

        assertThat(OwnershipForest.hasOwnershipData(old)).isFalse();
        assertThat(OwnershipForest.owners(old).get("Topic").verdict())
                .isEqualTo(OwnershipForest.Verdict.NO_DATA);
    }
}
