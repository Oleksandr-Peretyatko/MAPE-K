package org.anonymous.msrefactor.rt.analyze.generator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.analyze.selector.AggregatePartitionAnalyzer;
import org.anonymous.msrefactor.rt.analyze.selector.InvariantImpactAnalyzer;
import org.anonymous.msrefactor.rt.analyze.selector.OwnershipSelector;
import org.anonymous.msrefactor.rt.analyze.selector.ReplicaCandidateAnalyzer;
import org.anonymous.msrefactor.rt.analyze.selector.ValidationPlanBuilder;
import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which merges Analyze is allowed to propose.
 *
 * <p>Coupling alone proposed {@code tournament + user} and {@code question + topic}: the first has no
 * ownership path at all, the second is N:M. Both reach the execution skill only to be refused there,
 * after a plan has been written for them. The gate moves that verdict to where the candidate is born,
 * and — because a merge's feasibility is structural — also proposes the pairs no load test happened
 * to heat up.
 *
 * <p>The fixture is the reference application, transcribed: {@code @Replica} declarations, the
 * {@code @OneToOne} members of each root, the POST paths, the handled commands.
 */
class MergeCandidateOwnershipTest {

    private CandidateGenerator generator;
    private StructuralEvidence structural;

    @BeforeEach
    void setUp() {
        generator = new CandidateGenerator(
                new AggregatePartitionAnalyzer(), new ReplicaCandidateAnalyzer(),
                new InvariantImpactAnalyzer(), new ValidationPlanBuilder());
        ReflectionTestUtils.setField(generator, "ownershipSelector", new OwnershipSelector());
        structural = quizzes();
    }

    // ─── Fixture ─────────────────────────────────────────────────────────────────

    private static ServiceStaticMetrics svc(String name, String root,
                                            Map<String, List<String>> singleValued,
                                            Map<String, List<String>> replicas,
                                            List<String> posts, List<String> commands,
                                            Map<String, Integer> externalRelations) {
        return ServiceStaticMetrics.builder()
                .serviceName(name)
                .entityCount(4)
                .rootNames(List.of(root))
                .singleValuedMembers(singleValued)
                .replicaEntities(replicas)
                .postEndpoints(posts)
                .handledCommands(commands.stream()
                        .map(c -> CommandInfo.builder().name(c).build()).toList())
                .externalRelations(externalRelations)
                .build();
    }

    private static StructuralEvidence quizzes() {
        Map<String, ServiceStaticMetrics> perService = new LinkedHashMap<>();
        perService.put("course", svc("course", "Course", Map.of(), Map.of(), List.of(),
                List.of("GetCourseByIdCommand"), Map.of()));
        perService.put("user", svc("user", "User", Map.of(), Map.of(),
                List.of("/users/create", "/users/{userAggregateId}/delete"),
                List.of("GetUserByIdCommand"), Map.of()));
        perService.put("topic", svc("topic", "Topic",
                Map.of("Topic", List.of("TopicCourse")),
                Map.of("Course", List.of("TopicCourse")),
                List.of("/courses/{courseAggregateId}/create"),
                List.of("FindTopicsByCourseIdCommand", "GetTopicByIdCommand"),
                Map.of("course", 2)));
        perService.put("question", svc("question", "Question",
                Map.of("Question", List.of("QuestionCourse")),
                Map.of("Course", List.of("QuestionCourse", "QuestionTopic"),
                       "Topic", List.of("QuestionTopic")),
                List.of("/courses/{courseAggregateId}/questions/create"),
                List.of("FindQuestionsByCourseAggregateIdCommand", "FindQuestionsByTopicIdsCommand"),
                Map.of("course", 2, "topic", 3)));
        perService.put("execution", svc("execution", "Execution",
                Map.of("Execution", List.of("CourseExecutionCourse")),
                Map.of("Course", List.of("CourseExecutionCourse"), "User", List.of("CourseExecutionStudent")),
                List.of("/executions/create", "/executions/{executionAggregateId}/delete"),
                List.of("GetCourseExecutionsByUserIdCommand"),
                // course is execution's strongest partner by σ, so it is the pair coupling proposes —
                // distinct values on purpose: ties in couplingPartnersDesc resolve unpredictably.
                Map.of("course", 5, "user", 2)));
        perService.put("quiz", svc("quiz", "Quiz",
                Map.of("Quiz", List.of("QuizCourseExecution")),
                Map.of("Execution", List.of("QuizCourseExecution"), "Question", List.of("QuizQuestion")),
                List.of("/executions/{courseExecutionId}"),
                List.of("GetQuizByIdCommand"),
                Map.of("execution", 2, "question", 2)));
        perService.put("answer", svc("answer", "QuizAnswer",
                Map.of("QuizAnswer", List.of("AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz")),
                Map.of("Execution", List.of("AnswerCourseExecution"), "User", List.of("AnswerStudent"),
                       "Quiz", List.of("AnsweredQuiz"), "Question", List.of("AnsweredQuiz")),
                List.of("/quizzes/{quizAggregateId}/answer"),
                List.of("GetQuizAnswerByQuizIdAndUserIdCommand"),
                Map.of("quiz", 2, "user", 2)));
        perService.put("tournament", svc("tournament", "Tournament",
                Map.of("Tournament", List.of("TournamentCreator", "TournamentCourseExecution", "TournamentQuiz")),
                Map.of("Execution", List.of("TournamentCourseExecution"),
                       "User", List.of("TournamentCreator", "TournamentParticipant"),
                       "Quiz", List.of("TournamentQuiz"), "Topic", List.of("TournamentTopic")),
                List.of("/executions/{executionId}/tournaments/create"),
                List.of("GetTournamentsByCourseExecutionIdCommand"),
                Map.of("user", 3, "execution", 2, "topic", 2)));

        return StructuralEvidence.builder()
                .m1(0.12).m2(0.65).serviceCount(perService.size())
                .perService(perService)
                .highCouplingPairs(List.of())
                .lowCohesionServices(List.of())
                .highEntityCountServices(List.of())
                .build();
    }

    private static Hotspot coupling(String service) {
        return Hotspot.builder()
                .type(HotspotType.TIGHT_COUPLING)
                .qualityAttribute(QualityAttribute.PERFORMANCE)
                .affectedServices(List.of(service))
                .evidence("σ above threshold")
                .severity(0.8)
                .build();
    }

    private List<CandidateOperation> merges(List<Hotspot> hotspots) {
        return generator.generate(hotspots, structural, null, null, AnalysisConfig.defaults(), null)
                .stream().filter(c -> c.getOperation() == OperationType.MERGE_AGGREGATES).toList();
    }

    private static boolean pair(CandidateOperation c, String target, String secondary) {
        return target.equals(c.getTargetService()) && secondary.equals(c.getSecondaryTarget());
    }

    // ─── What gets proposed ──────────────────────────────────────────────────────

    @Test
    @DisplayName("every ownership edge is proposed, owner first, with no hotspot at all")
    void enumeratesTheFiveOwnershipEdges() {
        List<CandidateOperation> unblocked = merges(List.of()).stream()
                .filter(c -> c.getBlockedBy() == null).toList();

        assertThat(unblocked)
                .extracting(CandidateOperation::getTargetService, CandidateOperation::getSecondaryTarget)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("course", "topic"),
                        org.assertj.core.groups.Tuple.tuple("course", "question"),
                        org.assertj.core.groups.Tuple.tuple("execution", "quiz"),
                        org.assertj.core.groups.Tuple.tuple("execution", "tournament"),
                        org.assertj.core.groups.Tuple.tuple("quiz", "answer"));
    }

    @Test
    @DisplayName("a hotspot on the child still names the owner as the surviving service")
    void directionIsNormalised() {
        List<CandidateOperation> fromTopic = merges(List.of(coupling("topic")));

        assertThat(fromTopic).anySatisfy(c -> {
            assertThat(pair(c, "course", "topic")).isTrue();
            assertThat(c.getBlockedBy()).isNull();
            assertThat(c.getWhyApplicable()).contains("Course owns Topic", "direction normalised");
        });
        assertThat(fromTopic).noneMatch(c -> pair(c, "topic", "course"));
    }

    // ─── What gets blocked ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the strongest partner of tournament is user, and that pair is blocked, not dropped")
    void noOwnershipPathIsBlocked() {
        List<CandidateOperation> candidates = merges(List.of(coupling("tournament")));

        assertThat(candidates).anySatisfy(c -> {
            assertThat(pair(c, "tournament", "user")).isTrue();
            assertThat(c.getBlockedBy()).contains("no ownership relation between tournament and user");
        });
        assertThat(candidates)
                .filteredOn(c -> c.getBlockedBy() == null)
                .noneMatch(c -> "user".equals(c.getSecondaryTarget()) || "user".equals(c.getTargetService()));
    }

    @Test
    @DisplayName("siblings are blocked, and carry the one merge to run next")
    void siblingsCarryTheirNextStep() {
        // question's strongest partner is topic (σ=3): neither owns the other, both hang off course.
        List<CandidateOperation> candidates = merges(List.of(coupling("question")));

        assertThat(candidates).anySatisfy(c -> {
            assertThat(pair(c, "question", "topic")).isTrue();
            assertThat(c.getBlockedBy()).contains("next step", "course<-question");
            // One hop, not the whole chain: the loop runs one operation per iteration and the rest is
            // recomputed from the snapshot this one produces.
            assertThat(c.getDependencies()).containsExactly("course<-question");
        });
        assertThat(candidates)
                .filteredOn(c -> c.getBlockedBy() == null)
                .noneMatch(c -> pair(c, "question", "topic") || pair(c, "topic", "question"));
    }

    @Test
    @DisplayName("an O1 link that fails the rest of the rule is blocked as ambiguous, not as unrelated")
    void ambiguousOwnershipSaysSo() {
        List<CandidateOperation> candidates = merges(List.of(coupling("execution")));

        assertThat(candidates).anySatisfy(c -> {
            assertThat(c.getSecondaryTarget()).isEqualTo("course");
            assertThat(c.getBlockedBy()).contains("ambiguous");
        });
    }

    @Test
    @DisplayName("a snapshot with no cardinality data blocks with that as the reason")
    void withoutOwnershipDataNothingIsProposed() {
        structural.getPerService().replaceAll((name, sm) ->
                sm.toBuilder().singleValuedMembers(null).replicaEntities(null).build());

        List<CandidateOperation> candidates = merges(List.of(coupling("tournament")));

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(c ->
                assertThat(c.getBlockedBy()).contains("re-run Monitor"));
    }

    // ─── rt.analyze.enabled-operations ───────────────────────────────────────────

    private List<CandidateOperation> generateWith(java.util.Set<OperationType> enabled) {
        org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig t =
                new org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig();
        if (enabled != null) t.setEnabledOperations(enabled);
        ReflectionTestUtils.setField(generator, "thresholds", t);
        return generator.generate(List.of(coupling("tournament")), structural, null, null,
                AnalysisConfig.defaults(), null);
    }

    @Test
    @DisplayName("by default only merge and split candidates are generated")
    void defaultEnabledOperationsAreMergeAndSplit() {
        List<CandidateOperation> candidates = generateWith(null);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).extracting(CandidateOperation::getOperation)
                .allMatch(op -> op == OperationType.MERGE_AGGREGATES || op == OperationType.SPLIT_AGGREGATE);
    }

    @Test
    @DisplayName("a disabled operation is dropped however it was generated")
    void disabledOperationIsDropped() {
        assertThat(generateWith(java.util.EnumSet.allOf(OperationType.class)))
                .extracting(CandidateOperation::getOperation).contains(OperationType.MERGE_AGGREGATES);

        assertThat(generateWith(java.util.EnumSet.of(OperationType.SPLIT_AGGREGATE)))
                .extracting(CandidateOperation::getOperation)
                .doesNotContain(OperationType.MERGE_AGGREGATES);
    }
}
