package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.analyze.generator.CandidateGenerator;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.List;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partition selection for {@code SPLIT_AGGREGATE}.
 *
 * <p>The fixture is the real {@code Tournament}: its seven entities, its composition graph, and the
 * footprints of its twelve invariants as the collector extracts them. Using the real shape matters —
 * the previous criterion (largest group of replicas pointing at the same external service) looked
 * reasonable on invented data and, on this one, chose the most expensive cut available and one that
 * could not be executed at all.
 */
class SplitAggregatePartitionTest {

    private AggregatePartitionAnalyzer analyzer;
    private ServiceStaticMetrics smTournament;

    private static InvariantInfo inv(String name, List<String> members, List<String> rootScalars,
                                     boolean readsState, boolean readsPrev) {
        return InvariantInfo.builder()
                .name(name).declaredIn("Tournament").kind(InvariantInfo.Kind.METHOD)
                .memberEntities(members).rootScalars(rootScalars)
                .readsState(readsState).readsPrev(readsPrev)
                .build();
    }

    @BeforeEach
    void setUp() {
        analyzer = new AggregatePartitionAnalyzer();

        smTournament = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentCreator", "TournamentParticipant",
                        "TournamentParticipantQuizAnswer", "TournamentCourseExecution",
                        "TournamentTopic", "TournamentQuiz"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of(
                        "Tournament", List.of("TournamentCreator", "TournamentParticipant",
                                "TournamentCourseExecution", "TournamentTopic", "TournamentQuiz"),
                        // The level-2 child: it can never be separated from its parent.
                        "TournamentParticipant", List.of("TournamentParticipantQuizAnswer")))
                .invariants(List.of(
                        inv("invariantStartTimeBeforeEndTime", List.of(),
                                List.of("startTime", "endTime"), false, false),
                        inv("invariantUniqueParticipant", List.of("TournamentParticipant"),
                                List.of(), false, false),
                        inv("invariantParticipantsEnrolledBeforeStarTime", List.of("TournamentParticipant"),
                                List.of("startTime"), false, false),
                        inv("invariantAnswerBeforeStart",
                                List.of("TournamentParticipant", "TournamentParticipantQuizAnswer"),
                                List.of("startTime"), false, false),
                        inv("invariantMustHaveOneTopic", List.of("TournamentTopic"),
                                List.of(), false, false),
                        inv("invariantNumberOfQuestionsPositive", List.of(),
                                List.of("numberOfQuestions"), false, false),
                        inv("invariantNumberOfQuestionsAtMost30", List.of(),
                                List.of("numberOfQuestions"), false, false),
                        inv("invariantDeleteWhenNoParticipants", List.of("TournamentParticipant"),
                                List.of(), true, false),
                        inv("invariantCreatorParticipantConsistency",
                                List.of("TournamentParticipant", "TournamentCreator"),
                                List.of(), false, false),
                        inv("invariantCreatorIsNotAnonymous", List.of("TournamentCreator"),
                                List.of(), false, false),
                        inv("invariantFinalAfterStart", List.of("TournamentTopic"),
                                List.of("startTime", "endTime"), false, true),
                        inv("invariantCancelledFieldsAreFinal",
                                List.of("TournamentTopic", "TournamentParticipant"),
                                List.of("startTime", "endTime"), false, true)))
                .entityCount(7)
                .build();
    }

    /** Tournament's real cross-aggregate rules, with the footprints the collector extracts. */
    private static InterInvariantInfo inter(String name, List<String> members) {
        return InterInvariantInfo.builder()
                .name(name).declaredIn("Tournament").kind(InterInvariantInfo.Kind.METHOD)
                .memberEntities(members).rootScalars(List.of())
                .subscriptions(List.of()).dependsOnServices(List.of())
                .build();
    }

    private static final List<InterInvariantInfo> TOURNAMENT_INTER = List.of(
            inter("CourseExecutionExists", List.of("TournamentCourseExecution")),
            // Anchored on the course execution, but ranging over the creator and the participants.
            inter("CreatorExists", List.of("TournamentCourseExecution", "TournamentCreator",
                    "TournamentParticipant")),
            inter("ParticipantExists", List.of("TournamentCourseExecution", "TournamentCreator",
                    "TournamentParticipant")),
            inter("QuizAnswersExist", List.of("TournamentParticipant",
                    "TournamentParticipantQuizAnswer")),
            inter("TopicsExist", List.of("TournamentTopic")),
            inter("QuizExists", List.of("TournamentQuiz")));

    // ─── The criterion ──────────────────────────────────────────────────────────

    /** Names taken by the service itself, as the generator computes them before calling the analyzer. */
    private AggregatePartitionAnalyzer.PartitionResult partition(String service, ServiceStaticMetrics sm,
                                                                 DiscoveryGraph graph) {
        return analyzer.partition(service, sm, graph,
                SatelliteNaming.takenNames(sm == null ? null : Map.of(service, sm), graph));
    }

    @Test
    @DisplayName("the cheapest cut wins — the two entities no invariant reads")
    void picksTheCutThatSplitsNoInvariant() {
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", smTournament, null);

        assertThat(r).isNotNull();
        assertThat(r.cost()).isZero();
        assertThat(r.satelliteEntities())
                .as("no invariant of Tournament mentions the quiz or the course execution")
                .containsExactlyInAnyOrder("TournamentCourseExecution", "TournamentQuiz");
        assertThat(r.straddlingInvariants()).isEmpty();
        assertThat(r.coreEntities()).contains("Tournament", "TournamentCreator",
                "TournamentParticipant", "TournamentParticipantQuizAnswer", "TournamentTopic");
    }

    @Test
    @DisplayName("the old criterion's choice is now ranked, and ranked badly")
    void theExpensiveCutIsNotChosen() {
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", smTournament, null);

        assertThat(r.satelliteEntities())
                .as("grouping the two replicas of user was the most expensive cut available")
                .doesNotContain("TournamentCreator", "TournamentParticipant");
    }

    @Test
    @DisplayName("a parent never moves without its children")
    void satelliteIsClosedUnderComposition() {
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", smTournament, null);

        // Whatever is chosen, TournamentParticipantQuizAnswer and its parent stay on the same side.
        // The old partitioner moved the parent and left the cascade = ALL child behind.
        boolean parentInSat = r.satelliteEntities().contains("TournamentParticipant");
        boolean childInSat  = r.satelliteEntities().contains("TournamentParticipantQuizAnswer");
        assertThat(parentInSat).isEqualTo(childInSat);
    }

    // ─── Cross-aggregate rules ──────────────────────────────────────────────────

    @Test
    @DisplayName("a cut that splits no intra rule can still be the expensive one")
    void interInvariantsChangeTheChosenCut() {
        // Without them, {CourseExecution, Quiz} wins at cost 0. But taking the course execution
        // strands CreatorExists and ParticipantExists: both range over the creator and the
        // participants, which stay in the core, while their subscription anchor leaves. Splitting
        // off the quiz alone disturbs one rule instead of four.
        AggregatePartitionAnalyzer.PartitionResult r = partition("tournament",
                smTournament.toBuilder().interInvariants(TOURNAMENT_INTER).build(), null);

        assertThat(r.satelliteEntities()).containsExactly("TournamentQuiz");
        assertThat(r.cost()).isEqualTo(1);
        assertThat(r.straddlingInvariants()).isEmpty();
        assertThat(r.interInvariantsMovingToSatellite()).containsExactly("QuizExists");
        assertThat(r.interInvariantsSplittingAnchor()).isEmpty();
    }

    @Test
    @DisplayName("a rule left without its anchor is reported as needing a replica first")
    void anchorSplitIsReported() {
        // Only two units, so CreatorExists is stranded whichever way the cut goes: it reads the
        // course execution and the creator, and they end up on opposite sides.
        ServiceStaticMetrics creatorOnly = smTournament.toBuilder()
                .entityNames(List.of("Tournament", "TournamentCourseExecution", "TournamentCreator"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentCourseExecution", "TournamentCreator")))
                .invariants(List.of())
                .interInvariants(List.of(
                        inter("CourseExecutionExists", List.of("TournamentCourseExecution")),
                        inter("CreatorExists", List.of("TournamentCourseExecution", "TournamentCreator"))))
                .build();

        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", creatorOnly, null);

        // Moving the creator strands one rule; moving the course execution strands the same rule and
        // re-homes another on top.
        assertThat(r.satelliteEntities()).containsExactly("TournamentCreator");
        assertThat(r.cost()).isEqualTo(1);
        assertThat(r.interInvariantsSplittingAnchor()).containsExactly("CreatorExists");
        assertThat(r.interInvariantsMovingToSatellite()).isEmpty();
    }

    @Test
    @DisplayName("between equally disturbing cuts, the one that only re-homes wins")
    void severityBreaksTheTie() {
        // Both cuts disturb exactly one rule. Moving a subscription is scaffolding; rewriting an
        // intra rule as eventual consistency is a redesign.
        ServiceStaticMetrics twoWays = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentTopic", "TournamentQuiz"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament", List.of("TournamentTopic", "TournamentQuiz")))
                .invariants(List.of(inv("invariantTopicBeforeStart", List.of("TournamentTopic"),
                        List.of("startTime"), false, false)))
                .interInvariants(List.of(inter("QuizExists", List.of("TournamentQuiz"))))
                .entityCount(3)
                .build();

        AggregatePartitionAnalyzer.PartitionResult r = partition("tournament", twoWays, null);

        assertThat(r.cost()).isEqualTo(1);
        assertThat(r.satelliteEntities()).containsExactly("TournamentQuiz");
        assertThat(r.interInvariantsMovingToSatellite()).containsExactly("QuizExists");
    }

    @Test
    @DisplayName("a rule enforced in a service method costs nothing either")
    void serviceGuardsAreNotCharged() {
        // A guard runs synchronously inside a service method. There is no subscription for the cut
        // to re-home, so it must not price the partition — same as a DECLARED rule, different reason.
        InterInvariantInfo guarded = InterInvariantInfo.builder()
                .name("UNIQUE_QUIZ_ANSWER_PER_STUDENT").declaredIn("Tournament")
                .kind(InterInvariantInfo.Kind.SERVICE_GUARD)
                .enforcedIn("QuizAnswerService.startQuiz")
                .memberEntities(List.of("TournamentQuiz")).rootScalars(List.of())
                .subscriptions(List.of()).dependsOnServices(List.of())
                .build();

        AggregatePartitionAnalyzer.PartitionResult r = partition("tournament",
                smTournament.toBuilder().interInvariants(List.of(guarded)).build(), null);

        assertThat(r.cost()).isZero();
        assertThat(r.satelliteEntities())
                .containsExactlyInAnyOrder("TournamentCourseExecution", "TournamentQuiz");
        assertThat(r.interInvariantsMovingToSatellite()).isEmpty();
    }

    @Test
    @DisplayName("a rule the collector could not tie to a subscription costs nothing")
    void declaredRulesAreNotCharged() {
        // DECLARED is a line in the aggregate's header with no subscription behind it. Charging a
        // partition for a comment would let documentation steer the cut.
        InterInvariantInfo declared = InterInvariantInfo.builder()
                .name("QUIZ_COURSE_EXECUTION").declaredIn("Tournament")
                .kind(InterInvariantInfo.Kind.DECLARED)
                .memberEntities(List.of("TournamentQuiz")).rootScalars(List.of())
                .subscriptions(List.of()).dependsOnServices(List.of())
                .build();

        AggregatePartitionAnalyzer.PartitionResult r = partition("tournament",
                smTournament.toBuilder().interInvariants(List.of(declared)).build(), null);

        assertThat(r.cost()).isZero();
        assertThat(r.satelliteEntities())
                .containsExactlyInAnyOrder("TournamentCourseExecution", "TournamentQuiz");
        assertThat(r.interInvariantsMovingToSatellite()).isEmpty();
    }

    @Test
    @DisplayName("an aggregate with no inter-invariants is scored exactly as before")
    void absentInterInvariantsChangeNothing() {
        AggregatePartitionAnalyzer.PartitionResult withNull =
                partition("tournament", smTournament, null);
        AggregatePartitionAnalyzer.PartitionResult withEmpty = partition("tournament",
                smTournament.toBuilder().interInvariants(List.of()).build(), null);

        assertThat(withNull.satelliteEntities()).isEqualTo(withEmpty.satelliteEntities());
        assertThat(withNull.cost()).isEqualTo(withEmpty.cost()).isZero();
    }

    @Test
    @DisplayName("alternatives carry what they would cost, not how big they are")
    void alternativesReportTheirCost() {
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", smTournament, null);

        assertThat(r.alternativePartitions()).isNotEmpty();
        assertThat(r.alternativePartitions())
                .allSatisfy(a -> assertThat(a.getReasonRejectedOrRankedLower())
                        .contains("rule(s) would be disturbed by the cut"));
    }

    @Test
    @DisplayName("a rule reading a root scalar straddles even when it names one collection")
    void rootScalarMakesARuleStraddle() {
        // Only the participants subtree can move, so the winning cut must take it and pay for it.
        ServiceStaticMetrics onlyParticipants = smTournament.toBuilder()
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentTopic"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentTopic")))
                .invariants(List.of(
                        inv("invariantParticipantsEnrolledBeforeStarTime",
                                List.of("TournamentParticipant"), List.of("startTime"), false, false),
                        inv("invariantMustHaveOneTopic", List.of("TournamentTopic"),
                                List.of(), false, false)))
                .build();

        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", onlyParticipants, null);

        assertThat(r).isNotNull();
        // MustHaveOneTopic reads no root scalar, so moving the topics costs nothing.
        assertThat(r.satelliteEntities()).containsExactly("TournamentTopic");
        assertThat(r.cost()).isZero();
    }

    @Test
    @DisplayName("a getPrev() rule is reported separately — it must be duplicated, not re-homed")
    void temporalInvariantsAreCalledOut() {
        ServiceStaticMetrics onlyTopics = smTournament.toBuilder()
                .entityNames(List.of("Tournament", "TournamentTopic", "TournamentCreator"))
                .compositions(Map.of("Tournament", List.of("TournamentTopic", "TournamentCreator")))
                .invariants(List.of(
                        inv("invariantFinalAfterStart", List.of("TournamentTopic"),
                                List.of("startTime"), false, true),
                        inv("invariantCreatorIsNotAnonymous", List.of("TournamentCreator"),
                                List.of(), false, false)))
                .build();

        // Creator costs 0; Topic costs 1 and is temporal. Creator wins, and asking for the Topic cut
        // is what surfaces the temporal flag — so check it on the alternative instead.
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", onlyTopics, null);

        assertThat(r.satelliteEntities()).containsExactly("TournamentCreator");
        assertThat(r.cost()).isZero();
        assertThat(r.alternativePartitions())
                .anySatisfy(a -> assertThat(a.getReasonRejectedOrRankedLower())
                        .contains("invariantFinalAfterStart"));
    }

    // ─── Replicas: viability first, then cost ─────────────────────────────

    /**
     * {@code execution}: a root plus two units, both declared copies of another aggregate and neither
     * owning anything. Every cut would hand the satellite a cache with a service around it, which is
     * the degenerate split the skill refuses — so there is no partition to rank, not an expensive one.
     */
    @Test
    @DisplayName("a satellite of nothing but replicas is not viable — no partition at all")
    void pureReplicaSatelliteIsNotViable() {
        ServiceStaticMetrics smExecution = ServiceStaticMetrics.builder()
                .serviceName("execution")
                .entityNames(List.of("Execution", "CourseExecutionCourse", "CourseExecutionStudent"))
                .rootNames(List.of("Execution"))
                .compositions(Map.of("Execution",
                        List.of("CourseExecutionCourse", "CourseExecutionStudent")))
                .replicaEntities(Map.of("Course", List.of("CourseExecutionCourse"),
                                        "User", List.of("CourseExecutionStudent")))
                .invariants(List.of())
                .entityCount(3)
                .build();

        assertThat(partition("execution", smExecution, null))
                .as("both units are pure replicas, so no cut leaves the satellite state of its own")
                .isNull();
    }

    /**
     * The case that makes "not annotated" the wrong predicate. {@code TournamentParticipant} is
     * {@code @Replica("User")} and still carries {@code enrollTime} and owns
     * {@code TournamentParticipantQuizAnswer} — it is the satellite of the one clearly feasible split
     * in the application, so owning a subtree has to rescue it.
     */
    @Test
    @DisplayName("a replica that owns a subtree is authoritative, so its cut stays viable")
    void replicaWithItsOwnSubtreeIsAuthoritative() {
        ServiceStaticMetrics sm = smTournament.toBuilder()
                .replicaEntities(Map.of(
                        "User", List.of("TournamentCreator", "TournamentParticipant"),
                        "Execution", List.of("TournamentCourseExecution"),
                        "Quiz", List.of("TournamentQuiz"),
                        "Topic", List.of("TournamentTopic"),
                        "QuizAnswer", List.of("TournamentParticipantQuizAnswer")))
                .build();

        AggregatePartitionAnalyzer.PartitionResult r = partition("tournament", sm, null);

        assertThat(r).as("TournamentParticipant owns a child, so a cut can still be made").isNotNull();
        assertThat(r.satelliteEntities())
                .as("every other unit is a single pure replica, so the participant carries the cut")
                .contains("TournamentParticipant");
    }

    /**
     * {@code quiz}: two units of one entity each and no invariant straddling either way, so before the
     * replica term the winner was decided by the order of the compositions map. The authoritative unit
     * has to win, and repeatably.
     */
    @Test
    @DisplayName("between two cuts no rule disturbs, the one moving no replica wins")
    void movedReplicasRaiseTheCost() {
        ServiceStaticMetrics smQuiz = ServiceStaticMetrics.builder()
                .serviceName("quiz")
                .entityNames(List.of("Quiz", "QuizQuestion", "QuizCourseExecution"))
                .rootNames(List.of("Quiz"))
                .compositions(Map.of("Quiz", List.of("QuizCourseExecution", "QuizQuestion")))
                .replicaEntities(Map.of("Execution", List.of("QuizCourseExecution")))
                .invariants(List.of())
                .entityCount(3)
                .build();

        AggregatePartitionAnalyzer.PartitionResult r = partition("quiz", smQuiz, null);

        assertThat(r).isNotNull();
        assertThat(r.satelliteEntities())
                .as("QuizCourseExecution is a copy; moving it would cost a refresh quad")
                .containsExactly("QuizQuestion");
        assertThat(r.cost()).as("nothing is disturbed and no replica moves").isZero();
        assertThat(r.replicasMoved()).isEmpty();
    }

    @Test
    @DisplayName("a replica both sides read raises severity, and is named in the alternative")
    void sharedReplicaRaisesSeverity() {
        // Two units, each a replica but each owning a child, so both cuts are viable and each moves
        // one copy. Only one of them has a rule still reaching into the core.
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("svc")
                .entityNames(List.of("Root", "CopyA", "CopyAChild", "CopyB", "CopyBChild"))
                .rootNames(List.of("Root"))
                .compositions(Map.of("Root", List.of("CopyA", "CopyB"),
                        "CopyA", List.of("CopyAChild"),
                        "CopyB", List.of("CopyBChild")))
                .replicaEntities(Map.of("Other", List.of("CopyA", "CopyB")))
                .invariants(List.of(InvariantInfo.builder()
                        .name("ruleOverCopyAAndTheRoot").declaredIn("Root")
                        .kind(InvariantInfo.Kind.METHOD)
                        .memberEntities(List.of("CopyA")).rootScalars(List.of("someScalar"))
                        .readsState(false).readsPrev(false).build()))
                .entityCount(5)
                .build();

        AggregatePartitionAnalyzer.PartitionResult r = partition("svc", sm, null);

        assertThat(r).isNotNull();
        assertThat(r.satelliteEntities())
                .as("moving CopyA would split a rule the root still reads")
                .containsExactlyInAnyOrder("CopyB", "CopyBChild");
        assertThat(r.replicasMoved()).containsExactly("CopyB");
        assertThat(r.replicasShared()).as("nothing outside the satellite reads CopyB").isEmpty();
        assertThat(r.alternativePartitions())
                .anySatisfy(a -> assertThat(a.getReasonRejectedOrRankedLower())
                        .contains("replicas both sides read: CopyA"));
    }

    /**
     * Structural inviability and a constraint the architect declared are two different answers. The
     * first is {@code null}; only the second is a {@code PartitionResult} carrying {@code allBlocked},
     * which the generator turns into a blocked placeholder with a reason.
     */
    @Test
    @DisplayName("inviability is null, not a constraint block")
    void viabilityIsNotAConstraintBlock() {
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("execution")
                .entityNames(List.of("Execution", "CopyOne", "CopyTwo"))
                .rootNames(List.of("Execution"))
                .compositions(Map.of("Execution", List.of("CopyOne", "CopyTwo")))
                .replicaEntities(Map.of("Elsewhere", List.of("CopyOne", "CopyTwo")))
                .invariants(List.of())
                .entityCount(3)
                .build();

        assertThat(partition("execution", sm, null)).isNull();
    }

    @Test
    @DisplayName("declaring no replicas leaves the criterion exactly as it was")
    void withoutReplicaDataEveryUnitIsAuthoritative() {
        // The additive property the other seventeen tests rely on: smTournament declares none.
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", smTournament, null);

        assertThat(r).isNotNull();
        assertThat(r.replicasMoved()).isEmpty();
        assertThat(r.replicasShared()).isEmpty();
        assertThat(r.satelliteEntities())
                .containsExactlyInAnyOrder("TournamentCourseExecution", "TournamentQuiz");
    }

    // ─── When there is nothing to cut ───────────────────────────────────────────

    @Test
    @DisplayName("no composition data means no criterion, so no partition")
    void returnsNull_withoutCompositions() {
        assertThat(partition("service", smTournament.toBuilder()
                .compositions(Map.of()).build(), null)).isNull();
    }

    @Test
    @DisplayName("a single unit cannot be split — the core would be empty")
    void returnsNull_whenTheOnlyUnitIsEverything() {
        // topic's shape: one member, so the only satellite is the whole aggregate.
        ServiceStaticMetrics smTopic = ServiceStaticMetrics.builder()
                .serviceName("topic")
                .entityNames(List.of("Topic", "TopicCourse"))
                .rootNames(List.of("Topic"))
                .compositions(Map.of("Topic", List.of("TopicCourse")))
                .invariants(List.of())
                .entityCount(2)
                .build();

        assertThat(partition("topic", smTopic, null))
                .as("the count-based fallback used to split this by directory order")
                .isNull();
    }

    @Test
    @DisplayName("fewer than two entities is not a partition problem")
    void returnsNull_whenOnlyOneEntity() {
        assertThat(partition("course", ServiceStaticMetrics.builder()
                .entityNames(List.of("Course")).compositions(Map.of())
                .invariants(List.of()).entityCount(1).build(), null)).isNull();
    }

    // ─── No partition, no candidate ─────────────────────────────────────────────

    /**
     * The other half of {@link #returnsNull_whenOnlyOneEntity()}: a null partition must not reach the
     * output as a candidate. It used to, with no blueprint, asking the skill to split an aggregate of
     * one entity.
     */
    @Test
    void emitsNoSplitCandidate_whenAggregateCannotBePartitioned() {
        List<CandidateOperation> candidates = generateSplitCandidatesFor(
                ServiceStaticMetrics.builder()
                        .serviceName("course")
                        .entityNames(List.of("Course"))
                        .compositions(Map.of())
                        .invariants(List.of())
                        .entityCount(1)
                        .build(),
                "course");

        assertThat(candidates).isEmpty();
    }

    @Test
    void emitsSplitCandidateWithBlueprint_whenPartitionExists() {
        List<CandidateOperation> candidates =
                generateSplitCandidatesFor(smTournament, "tournament");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getBlueprint())
                .as("a candidate the skill cannot execute is worse than no candidate")
                .isNotNull();
        assertThat(candidates.get(0).getBlueprint().getAfter()).hasSize(2);
    }

    // ─── The satellite's name ───────────────────────────────────────────────────

    @Test
    @DisplayName("the satellite is <Aggr>Satellite — not a name built from its members")
    void namesTheSatelliteAfterTheAggregate() {
        AggregatePartitionAnalyzer.PartitionResult r =
                partition("tournament", smTournament, null);

        // v6 got TournamentCreatorParticipantParticipantQuizAnswerCluster: 23 of its files were past
        // Windows' path limit and went missing from a host checkout.
        assertThat(r.satelliteName()).isEqualTo("TournamentSatellite");
        assertThat(generateSplitCandidatesFor(smTournament, "tournament").get(0).getCommandSignature())
                .endsWith("--new-aggregate TournamentSatellite");
        assertThat(r.alternativePartitions())
                .as("alternatives are told apart by their members, since they would share the name")
                .allSatisfy(a -> assertThat(a.getTarget()).doesNotContain("Satellite", "Cluster"));
    }

    @Test
    @DisplayName("a satellite name already in use is numbered past, case-insensitively")
    void numbersTheSatellitePastATakenName() {
        AggregatePartitionAnalyzer.PartitionResult r = analyzer.partition("tournament", smTournament,
                null, java.util.Set.of("tournamentSatellite", "TournamentSatellite2"));

        assertThat(r.satelliteName()).isEqualTo("TournamentSatellite3");
    }

    private List<CandidateOperation> generateSplitCandidatesFor(ServiceStaticMetrics sm, String service) {
        CandidateGenerator generator = new CandidateGenerator(
                new AggregatePartitionAnalyzer(),
                new ReplicaCandidateAnalyzer(),
                new InvariantImpactAnalyzer(),
                new ValidationPlanBuilder());

        Hotspot hotspot = Hotspot.builder()
                .type(HotspotType.LOW_COHESION)
                .qualityAttribute(QualityAttribute.MAINTAINABILITY)
                .affectedServices(List.of(service))
                .severity(0.8)
                .evidence("test fixture")
                .build();

        StructuralEvidence structural = StructuralEvidence.builder()
                .perService(Map.of(service, sm))
                .serviceCount(1)
                .build();

        return generator.generate(List.of(hotspot), structural, null, null,
                        AnalysisConfig.builder().build(), null)
                .stream()
                .filter(c -> c.getOperation() == OperationType.SPLIT_AGGREGATE)
                .toList();
    }
}
