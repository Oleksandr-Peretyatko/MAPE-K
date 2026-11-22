package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.analyze.InvariantImpact;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification of invariants across a proposed cut.
 *
 * <p>The analyzer used to decide this with {@code invariantName.contains(entityName)}, and the
 * previous version of this test was built to fit it: entities called {@code Student} and invariants
 * called {@code verifyStudentIsEnrolled}, chosen — as its own javadoc admitted — so the substring
 * would match. It passed on those names and found nothing on the real ones, where
 * {@code invariantUniqueParticipant} does not contain {@code TournamentParticipant}. Every rule in
 * the reference application was reported as staying in the core, including the ones guarding
 * entities that had just moved out.
 *
 * <p>These fixtures use the real footprint instead: what each invariant actually reads.
 */
class InvariantImpactAnalyzerTest {

    private InvariantImpactAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new InvariantImpactAnalyzer();
    }

    /** No inter-invariants: the production overload takes them as its 4th argument. */
    private InvariantImpact analyzeForSplit(List<String> core, List<String> satellite,
                                            List<InvariantInfo> invariants,
                                            List<DiscoveryGraph.DiscoveredInvariant> graphInvariants) {
        return analyzer.analyzeForSplit(core, satellite, invariants, List.of(), graphInvariants);
    }

    private InvariantInfo inv(String name, List<String> members, List<String> rootScalars) {
        return InvariantInfo.builder()
                .name(name).declaredIn("Tournament").kind(InvariantInfo.Kind.METHOD)
                .memberEntities(members).rootScalars(rootScalars)
                .build();
    }

    @Test
    @DisplayName("an invariant that reads only satellite entities travels with them")
    void movesWithEntity_whenItReadsOnlyTheSatellite() {
        InvariantImpact impact = analyzeForSplit(
                List.of("TournamentTopic", "TournamentQuiz"),
                List.of("TournamentParticipant"),
                List.of(
                        inv("invariantUniqueParticipant", List.of("TournamentParticipant"), List.of()),
                        inv("invariantMustHaveOneTopic", List.of("TournamentTopic"), List.of())),
                null);

        assertThat(impact.getMovesWithEntity()).containsExactly("invariantUniqueParticipant");
        assertThat(impact.getStaysLocal()).containsExactly("invariantMustHaveOneTopic");
        assertThat(impact.getBecomesCrossBoundary()).isEmpty();
    }

    @Test
    @DisplayName("reading a root scalar puts a rule on both sides — the root stays in the core")
    void becomesCrossBoundary_whenItAlsoReadsARootScalar() {
        // The real invariantParticipantsEnrolledBeforeStarTime: one collection and one root scalar.
        // Counting only entities would classify this as moving with the participants, and the
        // satellite would end up unable to evaluate it.
        InvariantImpact impact = analyzeForSplit(
                List.of("TournamentTopic"),
                List.of("TournamentParticipant"),
                List.of(inv("invariantParticipantsEnrolledBeforeStarTime",
                        List.of("TournamentParticipant"), List.of("startTime"))),
                null);

        assertThat(impact.getBecomesCrossBoundary())
                .containsExactly("invariantParticipantsEnrolledBeforeStarTime");
        assertThat(impact.getMovesWithEntity()).isEmpty();
    }

    @Test
    @DisplayName("reading entities on both sides is cross-boundary")
    void becomesCrossBoundary_whenItReadsBothSides() {
        InvariantImpact impact = analyzeForSplit(
                List.of("TournamentCreator"),
                List.of("TournamentParticipant"),
                List.of(
                        inv("invariantCreatorParticipantConsistency",
                                List.of("TournamentCreator", "TournamentParticipant"), List.of()),
                        inv("invariantCreatorIsNotAnonymous", List.of("TournamentCreator"), List.of())),
                null);

        assertThat(impact.getBecomesCrossBoundary())
                .containsExactly("invariantCreatorParticipantConsistency");
        assertThat(impact.getStaysLocal()).containsExactly("invariantCreatorIsNotAnonymous");
    }

    @Test
    @DisplayName("a getPrev() rule cannot span a cut — it needs a redesign, not a re-homing")
    void requiresSaga_whenItComparesAgainstThePreviousVersion() {
        // After a split each side has its own version chain, so there is no single prev to compare
        // against. Reporting this as a plain cross-boundary rule understates what it costs.
        InvariantInfo temporal = InvariantInfo.builder()
                .name("invariantCancelledFieldsAreFinal").declaredIn("Tournament")
                .kind(InvariantInfo.Kind.METHOD)
                .memberEntities(List.of("TournamentTopic", "TournamentParticipant"))
                .rootScalars(List.of("startTime"))
                .readsPrev(true)
                .build();

        InvariantImpact impact = analyzeForSplit(
                List.of("TournamentTopic"), List.of("TournamentParticipant"),
                List.of(temporal), null);

        assertThat(impact.getRequiresSagaOrCompensation())
                .containsExactly("invariantCancelledFieldsAreFinal");
        assertThat(impact.getBecomesCrossBoundary()).isEmpty();
    }

    @Test
    @DisplayName("no invariant data is not the same as no invariants")
    void emptyInvariantsProduceEmptyImpact() {
        InvariantImpact impact = analyzeForSplit(
                List.of("OtherEntity"), List.of("SomeEntity"), List.of(), null);

        assertThat(impact.getStaysLocal()).isEmpty();
        assertThat(impact.getMovesWithEntity()).isEmpty();
        assertThat(impact.getBecomesCrossBoundary()).isEmpty();
        assertThat(impact.getBlockingWarnings()).isEmpty();
    }

    @Test
    @DisplayName("a majority of straddling rules raises a warning — it does not block anything")
    void warnsButDoesNotBlock_whenMajorityBecomesCrossBoundary() {
        InvariantImpact impact = analyzeForSplit(
                List.of("Alpha", "Beta", "Gamma"),
                List.of("Entity"),
                List.of(
                        inv("ruleOne",   List.of("Entity", "Alpha"), List.of()),
                        inv("ruleTwo",   List.of("Entity", "Beta"),  List.of()),
                        inv("ruleThree", List.of("Entity", "Gamma"), List.of())),
                null);

        assertThat(impact.getBecomesCrossBoundary()).hasSize(3);
        assertThat(impact.getBlockingWarnings()).isNotEmpty();
    }

    // ─── Cross-aggregate rules ──────────────────────────────────────────────────

    private InterInvariantInfo inter(String name, List<String> members) {
        return InterInvariantInfo.builder()
                .name(name).declaredIn("Tournament").kind(InterInvariantInfo.Kind.METHOD)
                .memberEntities(members).rootScalars(List.of())
                .subscriptions(List.of()).dependsOnServices(List.of())
                .build();
    }

    @Test
    @DisplayName("a cross-aggregate rule whose footprint moves takes its subscriptions along")
    void subscriptionsMoveWithTheirFootprint() {
        InvariantImpact impact = analyzer.analyzeForSplit(
                List.of("TournamentCourseExecution"),
                List.of("TournamentQuiz"),
                List.of(),
                List.of(inter("QuizExists", List.of("TournamentQuiz")),
                        inter("CourseExecutionExists", List.of("TournamentCourseExecution"))),
                null);

        assertThat(impact.getSubscriptionsMoveToSatellite()).containsExactly("QuizExists");
        assertThat(impact.getSubscriptionsNeedAnchorReplica()).isEmpty();
        // Already eventual — a cut never turns one of these into a cross-boundary rule.
        assertThat(impact.getBecomesCrossBoundary()).isEmpty();
    }

    @Test
    @DisplayName("a rule whose anchor stays behind needs that anchor replicated first")
    void anchorLeftInTheCoreNeedsAReplica() {
        InvariantImpact impact = analyzer.analyzeForSplit(
                List.of("TournamentCourseExecution"),
                List.of("TournamentCreator"),
                List.of(),
                // Anchored on the course execution, which stays; ranges over the creator, which goes.
                List.of(inter("CreatorExists",
                        List.of("TournamentCourseExecution", "TournamentCreator"))),
                null);

        assertThat(impact.getSubscriptionsNeedAnchorReplica()).containsExactly("CreatorExists");
        assertThat(impact.getSubscriptionsMoveToSatellite()).isEmpty();
    }

    @Test
    @DisplayName("an aggregate with no verifyInvariants body still reports its cross-aggregate rules")
    void classifiesInterInvariantsWithNoIntraInvariantsAtAll() {
        // QuizAnswer's shape: an empty verifyInvariants() and three rules held by subscription. The
        // early return on an empty intra list used to drop everything.
        InvariantImpact impact = analyzer.analyzeForSplit(
                List.of("AnswerCourseExecution"),
                List.of("AnsweredQuiz"),
                List.of(),
                List.of(inter("QuizExists", List.of("AnsweredQuiz"))),
                null);

        assertThat(impact.getSubscriptionsMoveToSatellite()).containsExactly("QuizExists");
    }

    @Test
    @DisplayName("a rule the collector could not tie to a subscription is not classified")
    void ignoresDeclaredOnlyRules() {
        InvariantImpact impact = analyzer.analyzeForSplit(
                List.of("TournamentCourseExecution"),
                List.of("TournamentQuiz"),
                List.of(),
                List.of(InterInvariantInfo.builder()
                        .name("QUIZ_COURSE_EXECUTION").declaredIn("Tournament")
                        .kind(InterInvariantInfo.Kind.DECLARED)
                        .memberEntities(List.of("TournamentQuiz")).rootScalars(List.of())
                        .build()),
                null);

        assertThat(impact.getSubscriptionsMoveToSatellite()).isEmpty();
        assertThat(impact.getSubscriptionsNeedAnchorReplica()).isEmpty();
    }
}
