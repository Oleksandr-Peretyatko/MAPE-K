package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collector used to find invariants by matching the method name against {@code invariant*}. That
 * is a convention of two aggregates: it finds 16 rules in the reference application and misses four
 * more that are written differently. And a name carries no footprint, so nothing downstream could
 * tell which entities a rule reads — the substring test that stood in for it
 * ({@code "invariantuniqueparticipant".contains("tournamentparticipant")}) is always false.
 *
 * <p>These fixtures are transcribed from the real aggregates. Each one is here because it breaks a
 * different naive extractor.
 */
class InvariantCaptureTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    private List<InvariantInfo> extract(String className, String source, String... entities) {
        return collector.extractInvariants(Map.of(className, source), Set.of(entities));
    }

    private InvariantInfo named(List<InvariantInfo> all, String name) {
        return all.stream().filter(i -> i.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("not captured: " + name
                        + " — captured " + all.stream().map(InvariantInfo::getName).toList()));
    }

    // ─── Recognition ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a delegate that is not named invariant* is still an invariant")
    void capturesDelegateWithNonConventionalName() {
        // Execution's real shape. The old regex saw nothing here.
        List<InvariantInfo> found = extract("Execution", """
                public class Execution extends Aggregate {
                    private String acronym;
                    private Set<CourseExecutionStudent> students = new HashSet<>();

                    public boolean removedNoStudents() {
                        if (getState() == AggregateState.DELETED) {
                            return getStudents().size() == 0;
                        }
                        return true;
                    }

                    public void verifyInvariants() {
                        if (!(removedNoStudents())) {
                            throw new QuizzesException(QuizzesErrorMessage.INVARIANT_BREAK, getAggregateId());
                        }
                    }
                }
                """, "Execution", "CourseExecutionStudent");

        assertThat(found).extracting(InvariantInfo::getName).containsExactly("removedNoStudents");
        assertThat(named(found, "removedNoStudents").getKind()).isEqualTo(InvariantInfo.Kind.METHOD);
    }

    @Test
    @DisplayName("an inline check is named after the error constant it throws")
    void capturesInlineCheckByErrorConstant() {
        // Course has no invariant method at all — the rule is an if inside verifyInvariants.
        List<InvariantInfo> found = extract("Course", """
                public class Course extends Aggregate {
                    private int courseExecutionCount;
                    private int courseQuestionCount;

                    public void verifyInvariants() {
                        if (courseExecutionCount == 0 && courseQuestionCount > 0) {
                            throw new QuizzesException(
                                QuizzesErrorMessage.CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT, getAggregateId());
                        }
                    }
                }
                """, "Course");

        InvariantInfo inv = named(found, "CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT");
        assertThat(inv.getKind()).isEqualTo(InvariantInfo.Kind.INLINE);
        assertThat(inv.getRootScalars())
                .containsExactlyInAnyOrder("courseExecutionCount", "courseQuestionCount");
    }

    @Test
    @DisplayName("INVARIANT_BREAK is not a rule — it is the generic companion of a delegate")
    void doesNotCaptureGenericErrorConstantAsARule() {
        List<InvariantInfo> found = extract("User", """
                public class User extends Aggregate {
                    private boolean active;
                    public boolean deletedState() {
                        if (getState() == AggregateState.DELETED) { return !isActive(); }
                        return true;
                    }
                    public void verifyInvariants() {
                        if (!(deletedState())) {
                            throw new QuizzesException(INVARIANT_BREAK, getAggregateId());
                        }
                    }
                }
                """, "User");

        assertThat(found).extracting(InvariantInfo::getName).containsExactly("deletedState");
    }

    // ─── Footprint ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a collection reached only through its accessor is still captured")
    void capturesMemberReachedViaAccessor() {
        // removedNoStudents never names the field or the type: getStudents() is the only handle.
        List<InvariantInfo> found = extract("Execution", """
                public class Execution extends Aggregate {
                    private Set<CourseExecutionStudent> students = new HashSet<>();
                    public boolean removedNoStudents() {
                        if (getState() == AggregateState.DELETED) { return getStudents().size() == 0; }
                        return true;
                    }
                    public void verifyInvariants() { if (!removedNoStudents()) { throw new E(X); } }
                }
                """, "Execution", "CourseExecutionStudent");

        InvariantInfo inv = named(found, "removedNoStudents");
        assertThat(inv.getMemberEntities()).containsExactly("CourseExecutionStudent");
        assertThat(inv.isReadsState()).isTrue();
    }

    @Test
    @DisplayName("a member named only by a type reference is captured")
    void capturesMemberNamedOnlyByType() {
        List<InvariantInfo> found = extract("Tournament", """
                public class Tournament extends Aggregate {
                    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();
                    public boolean invariantUniqueParticipant() {
                        return this.tournamentParticipants.size() == this.tournamentParticipants.stream()
                                .map(TournamentParticipant::getParticipantAggregateId)
                                .distinct().count();
                    }
                    public void verifyInvariants() { if (!invariantUniqueParticipant()) { throw new E(X); } }
                }
                """, "Tournament", "TournamentParticipant");

        InvariantInfo inv = named(found, "invariantUniqueParticipant");
        assertThat(inv.getMemberEntities()).containsExactly("TournamentParticipant");
        assertThat(inv.getRootScalars()).isEmpty();
    }

    @Test
    @DisplayName("a root scalar read alongside a member is what makes a rule straddle a cut")
    void separatesRootScalarsFromMembers() {
        List<InvariantInfo> found = extract("Tournament", """
                public class Tournament extends Aggregate {
                    private LocalDateTime startTime;
                    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();
                    public boolean invariantParticipantsEnrolledBeforeStarTime() {
                        for (TournamentParticipant p : this.tournamentParticipants) {
                            if (p.getEnrollTime().isAfter(this.startTime)) { return false; }
                        }
                        return true;
                    }
                    public void verifyInvariants() {
                        if (!invariantParticipantsEnrolledBeforeStarTime()) { throw new E(X); }
                    }
                }
                """, "Tournament", "TournamentParticipant");

        InvariantInfo inv = named(found, "invariantParticipantsEnrolledBeforeStarTime");
        assertThat(inv.getMemberEntities()).containsExactly("TournamentParticipant");
        assertThat(inv.getRootScalars()).contains("startTime");
    }

    @Test
    @DisplayName("a nested member reached through the parent's accessor is captured")
    void capturesNestedMemberThroughTraversal() {
        // AnswerBeforeStart reaches ParticipantQuizAnswer only via t.getParticipantAnswer(), and
        // `participantAnswer` is declared on TournamentParticipant — NOT on the root. Resolving
        // field names against the root alone drops the nested member and reports a footprint that is
        // short by one entity, which is exactly the kind of miss that makes a cut look cheaper than
        // it is. The two classes are therefore given separately, as they are on disk.
        List<InvariantInfo> found = collector.extractInvariants(Map.of(
                "Tournament", """
                public class Tournament extends Aggregate {
                    private LocalDateTime startTime;
                    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();
                    public boolean invariantAnswerBeforeStart() {
                        if (DateHandler.now().isBefore(this.startTime)) {
                            for (TournamentParticipant t : this.tournamentParticipants) {
                                if (t.getParticipantAnswer().getQuizAnswerAggregateId() != null) { return false; }
                            }
                        }
                        return true;
                    }
                    public void verifyInvariants() { if (!invariantAnswerBeforeStart()) { throw new E(X); } }
                }
                """,
                "TournamentParticipant", """
                public class TournamentParticipant {
                    private TournamentParticipantQuizAnswer participantAnswer;
                }
                """),
                Set.of("Tournament", "TournamentParticipant", "TournamentParticipantQuizAnswer"));

        assertThat(named(found, "invariantAnswerBeforeStart").getMemberEntities())
                .containsExactlyInAnyOrder("TournamentParticipant", "TournamentParticipantQuizAnswer");
    }

    @Test
    @DisplayName("a member's own state is not the aggregate's state")
    void doesNotReportMemberStateAsRootState() {
        // invariantQuizQuestionSequenceConsecutive filters on qq.getState(). That is the member's
        // flag, so the rule needs nothing from the root and travels with QuizQuestion. Treating any
        // getState() as the root's would pin it to the core.
        List<InvariantInfo> found = extract("Quiz", """
                public class Quiz extends Aggregate {
                    private Set<QuizQuestion> quizQuestions = new HashSet<>();
                    private boolean invariantQuizQuestionSequenceConsecutive() {
                        List<Integer> sequences = quizQuestions.stream()
                                .filter(qq -> qq.getState() == Aggregate.AggregateState.ACTIVE)
                                .map(QuizQuestion::getSequence).sorted().toList();
                        return sequences.isEmpty();
                    }
                    public void verifyInvariants() {
                        if (getState() == AggregateState.ACTIVE) {
                            if (!invariantQuizQuestionSequenceConsecutive()) { throw new E(X); }
                        }
                    }
                }
                """, "Quiz", "QuizQuestion");

        InvariantInfo inv = named(found, "invariantQuizQuestionSequenceConsecutive");
        assertThat(inv.getMemberEntities()).containsExactly("QuizQuestion");
        assertThat(inv.isReadsState()).isFalse();
        assertThat(inv.getRootScalars()).isEmpty();
    }

    @Test
    @DisplayName("the two collections of CancelledFieldsAreFinal — the one the header comment omits")
    void capturesBothCollectionsDespiteMisleadingComment() {
        // The comment above this rule lists only topics; the body also reads participants. Two
        // independent analyses in this project classified it from the comment and got it wrong.
        List<InvariantInfo> found = extract("Tournament", """
                public class Tournament extends Aggregate {
                    private LocalDateTime startTime;
                    private Set<TournamentTopic> tournamentTopics = new HashSet<>();
                    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();
                    /*
                     * IS_CANCELED
                     * prev.cancelled => final this.startTime && final this.tournamentTopics
                     */
                    private boolean invariantCancelledFieldsAreFinal() {
                        Tournament prev = (Tournament) getPrev();
                        if (prev != null && prev.isCancelled()) {
                            return Objects.equals(this.startTime, prev.getStartTime())
                                && this.tournamentTopics.stream().map(TournamentTopic::getTopicAggregateId)
                                       .collect(Collectors.toSet()).equals(prev.getTournamentTopics())
                                && this.tournamentParticipants.stream()
                                       .map(TournamentParticipant::getParticipantAggregateId)
                                       .collect(Collectors.toSet()).equals(prev.getTournamentParticipants());
                        }
                        return true;
                    }
                    public void verifyInvariants() { if (!invariantCancelledFieldsAreFinal()) { throw new E(X); } }
                }
                """, "Tournament", "TournamentTopic", "TournamentParticipant");

        InvariantInfo inv = named(found, "invariantCancelledFieldsAreFinal");
        assertThat(inv.getMemberEntities())
                .containsExactlyInAnyOrder("TournamentTopic", "TournamentParticipant");
        assertThat(inv.isReadsPrev())
                .as("getPrev() means the rule cannot span a boundary at all")
                .isTrue();
    }

    // ─── Structure ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a body with nested blocks is read whole, not truncated at the first brace")
    void readsNestedBodiesWhole() {
        String body = collector.methodBody("""
                public boolean check() {
                    if (a) {
                        for (X x : xs) { return false; }
                    }
                    return true;
                }
                """, "check");

        assertThat(body).contains("return true;");
    }

    @Test
    @DisplayName("Saga/Causal variants do not duplicate the rules they inherit")
    void ignoresClassesOutsideTheEntitySet() {
        String source = """
                public class SagaTournament extends Tournament {
                    public void verifyInvariants() { if (!invariantX()) { throw new E(Y); } }
                    public boolean invariantX() { return true; }
                }
                """;
        // SagaTournament is scanned for content but is not an entity, so it must contribute nothing.
        assertThat(collector.extractInvariants(Map.of("SagaTournament", source), Set.of("Tournament")))
                .isEmpty();
    }
}
