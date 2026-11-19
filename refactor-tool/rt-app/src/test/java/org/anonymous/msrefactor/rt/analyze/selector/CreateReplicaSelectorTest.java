package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.monitor.InterServiceDependency;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A replica is warranted when one service needs another service's entity — behaviour unchanged,
 * consistency traded for locality. These tests pin the three parts of that sentence, each of which
 * the selector used to guess:
 *
 * <ul>
 *   <li>does the consumer reach the source at all (read commands)?</li>
 *   <li>is the reach read-only (no write commands)?</li>
 *   <li>which entity (named by the read commands, validated against the source)?</li>
 * </ul>
 */
class CreateReplicaSelectorTest {

    private ReplicaCandidateAnalyzer analyzer;
    private ConsistencyEvidence consistency;

    @BeforeEach
    void setUp() {
        analyzer = new ReplicaCandidateAnalyzer();
        consistency = ConsistencyEvidence.builder().avgCompensationRate(0.1).build();
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────

    private InterServiceDependency edge(String target, List<String> reads, List<String> writes) {
        return InterServiceDependency.builder()
                .targetService(target)
                .structuralRefs(1)
                .functionalityNames(List.of("SolveQuizFunctionalitySagas"))
                .readCommands(reads)
                .writeCommands(writes)
                .totalWeight(2)
                .build();
    }

    /** A consumer with one edge to `source`. */
    private ServiceStaticMetrics consumer(String name, String source, InterServiceDependency dep) {
        Map<String, InterServiceDependency> deps = new LinkedHashMap<>();
        deps.put(source, dep);
        return ServiceStaticMetrics.builder()
                .serviceName(name)
                .entityNames(List.of("Tournament", "TournamentQuiz"))
                .invariants(List.of())
                .crossServiceEntities(Map.of(source, List.of("TournamentQuiz")))
                .interServiceDependencies(deps)
                .entityCount(2)
                .build();
    }

    private ServiceStaticMetrics source(String name, List<String> entities, List<String> roots) {
        return ServiceStaticMetrics.builder()
                .serviceName(name)
                .entityNames(entities)
                .rootNames(roots)
                .invariants(List.of())
                .entityCount(entities.size())
                .build();
    }

    private List<ReplicaCandidateAnalyzer.ReplicaOpportunity> find(
            ServiceStaticMetrics consumer, ServiceStaticMetrics... sources) {
        Map<String, ServiceStaticMetrics> all = new LinkedHashMap<>();
        all.put(consumer.getServiceName(), consumer);
        for (ServiceStaticMetrics s : sources) all.put(s.getServiceName(), s);
        return analyzer.findOpportunities(consumer.getServiceName(), consumer, all, null, consistency, 3);
    }

    // ─── The three gates ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a read-only reach yields one opportunity, and the command names the entity")
    void readOnlyReachYieldsAnOpportunity() {
        ServiceStaticMetrics tournament = consumer("tournament", "quiz",
                edge("quiz", List.of("GetQuizByIdCommand"), null));

        List<ReplicaCandidateAnalyzer.ReplicaOpportunity> found =
                find(tournament, source("quiz", List.of("Quiz", "QuizQuestion"), List.of("Quiz")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).sourceService()).isEqualTo("quiz");
        assertThat(found.get(0).entity()).isEqualTo("Quiz");
        assertThat(found.get(0).readCommands()).containsExactly("GetQuizByIdCommand");
    }

    @Test
    @DisplayName("one write on the edge suppresses the candidate entirely and silently")
    void aWriteSuppressesTheCandidate() {
        // A replica cannot help here: the write still has to reach the owner, so a local copy would
        // contradict it. That is an ownership problem, and Execute refuses it as case 3 every time.
        ServiceStaticMetrics tournament = consumer("tournament", "quiz",
                edge("quiz", List.of("GetQuizByIdCommand"), List.of("UpdateGeneratedQuizCommand")));

        assertThat(find(tournament, source("quiz", List.of("Quiz"), List.of("Quiz")))).isEmpty();
    }

    @Test
    @DisplayName("no read commands means no read to make local — the failure that started this")
    void noReachYieldsNothing() {
        // /create-replica answer AnswerCourseExecution --to tournament: tournament issues no read
        // command to answer, it only coordinates writes. The old selector proposed it anyway.
        ServiceStaticMetrics tournament = consumer("tournament", "answer",
                edge("answer", List.of(), List.of("StartQuizCommand", "RemoveQuizAnswerCommand")));

        assertThat(find(tournament,
                source("answer", List.of("AnswerCourseExecution", "QuizAnswer"), List.of("QuizAnswer"))))
                .isEmpty();
    }

    @Test
    @DisplayName("a snapshot with no command data yields nothing rather than a guess")
    void unknownEdgeYieldsNothing() {
        ServiceStaticMetrics tournament = consumer("tournament", "quiz", edge("quiz", null, null));

        assertThat(find(tournament, source("quiz", List.of("Quiz"), List.of("Quiz")))).isEmpty();
    }

    // ─── Which entity ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("two entities read from one source are two separate opportunities")
    void oneOpportunityPerEntity() {
        ServiceStaticMetrics quiz = consumer("quiz", "execution",
                edge("execution", List.of("GetCourseExecutionByIdCommand", "FindStudentsByIdsCommand"), null));

        List<ReplicaCandidateAnalyzer.ReplicaOpportunity> found = find(quiz,
                source("execution", List.of("Execution", "CourseExecutionStudent", "Student"),
                        List.of("Execution")));

        assertThat(found).extracting(ReplicaCandidateAnalyzer.ReplicaOpportunity::entity)
                .containsExactlyInAnyOrder("CourseExecution", "Student");
    }

    @Test
    @DisplayName("the DTO-family name is accepted when it extends a real root of the source")
    void acceptsTheDtoFamilyName() {
        // execution's root is `Execution`, but its DTO and every replica of it are named
        // `CourseExecution*` (quizzes-composition.md §2.2). GetCourseExecutionByIdCommand therefore
        // names `CourseExecution`, which is not a class — and is exactly what the skill expects as
        // the operand, because the replica is built as new XCourseExecution(courseExecutionDto).
        ServiceStaticMetrics tournament = consumer("tournament", "execution",
                edge("execution", List.of("GetCourseExecutionByIdCommand"), null));

        List<ReplicaCandidateAnalyzer.ReplicaOpportunity> found =
                find(tournament, source("execution", List.of("Execution"), List.of("Execution")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).entity())
                .as("the command's spelling is kept, not rewritten to the root")
                .isEqualTo("CourseExecution");
    }

    @Test
    @DisplayName("an entity the source does not declare is dropped, never emitted")
    void unvalidatedEntityIsDropped() {
        ServiceStaticMetrics tournament = consumer("tournament", "quiz",
                edge("quiz", List.of("GetSomethingElseByIdCommand"), null));

        assertThat(find(tournament, source("quiz", List.of("Quiz"), List.of("Quiz")))).isEmpty();
    }

    @Test
    @DisplayName("command names parse to the entity the replica is built from")
    void entityParsing() {
        assertThat(analyzer.entityFromCommand("GetCourseExecutionByIdCommand")).isEqualTo("CourseExecution");
        assertThat(analyzer.entityFromCommand("FindQuestionsByTopicIdsCommand")).isEqualTo("Question");
        assertThat(analyzer.entityFromCommand("GetTournamentByIdCommand")).isEqualTo("Tournament");
        assertThat(analyzer.entityFromCommand("FindTopicsByCourseIdCommand")).isEqualTo("Topic");
        // Not a read verb — a write command names nothing to replicate.
        assertThat(analyzer.entityFromCommand("UpdateStudentNameCommand")).isNull();
    }

    // ─── The real quizzes edges ─────────────────────────────────────────────────

    /**
     * The three edges `tournament` actually has, with the commands grepped from its coordination
     * package. This is the regression that matters: the change must filter out nonsense without
     * suppressing the one genuine opportunity.
     */
    @Test
    @DisplayName("tournament: answer and quiz are rejected, execution survives")
    void realTournamentEdges() {
        Map<String, InterServiceDependency> deps = new LinkedHashMap<>();
        // Writes only — tournament coordinates the answer, it never reads it. This is the candidate
        // that was proposed and refused as case 4, costing a full iteration.
        deps.put("answer", edge("answer", List.of(),
                List.of("RemoveQuizAnswerCommand", "StartQuizCommand")));
        // One read, but four writes: tournament generates and updates the quiz, so replicating Quiz
        // into it would be an ownership problem, not a locality one.
        deps.put("quiz", edge("quiz", List.of("GetQuizByIdCommand"),
                List.of("GenerateQuizCommand", "RemoveQuizCommand",
                        "StartTournamentQuizCommand", "UpdateGeneratedQuizCommand")));
        // Reads only — the genuine opportunity, and the replica already exists.
        deps.put("execution", edge("execution",
                List.of("GetCourseExecutionByIdCommand", "GetStudentByExecutionIdAndUserIdCommand"),
                List.of()));

        ServiceStaticMetrics tournament = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentCourseExecution", "TournamentQuiz"))
                .invariants(List.of())
                .crossServiceEntities(Map.of("execution", List.of("TournamentCourseExecution")))
                .interServiceDependencies(deps)
                .entityCount(3).build();

        List<ReplicaCandidateAnalyzer.ReplicaOpportunity> found = find(tournament,
                source("answer", List.of("AnswerCourseExecution", "QuizAnswer"), List.of("QuizAnswer")),
                source("quiz", List.of("Quiz", "QuizQuestion"), List.of("Quiz")),
                source("execution", List.of("Execution", "CourseExecutionStudent"), List.of("Execution")));

        assertThat(found).extracting(ReplicaCandidateAnalyzer.ReplicaOpportunity::sourceService)
                .containsExactly("execution");
        assertThat(found.get(0).entity()).isEqualTo("CourseExecution");
        assertThat(found.get(0).replicaAlreadyExists())
                .as("TournamentCourseExecution is already there — this is an extension")
                .isTrue();
        // GetStudentByExecutionIdAndUserIdCommand parses to `Student`, which execution does not
        // declare, so it is dropped rather than emitted as a second entity.
        assertThat(found).hasSize(1);
    }

    // ─── Scoring ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a structurally real but unmeasured path is kept, and ranked last")
    void unmeasuredPathIsClampedNotDiscarded() {
        ServiceStaticMetrics tournament = consumer("tournament", "quiz",
                edge("quiz", List.of("GetQuizByIdCommand"), null));

        // runtime == null: nothing measured this path.
        List<ReplicaCandidateAnalyzer.ReplicaOpportunity> found =
                find(tournament, source("quiz", List.of("Quiz"), List.of("Quiz")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).runtimeBacked()).isFalse();
        assertThat(found.get(0).score())
                .as("clamped below the unmeasured ceiling so it ranks last")
                .isLessThanOrEqualTo(0.35)
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("an existing replica and an existing sync event both raise feasibility")
    void feasibilityReflectsWhatAlreadyExists() {
        InterServiceDependency withEvent = InterServiceDependency.builder()
                .targetService("quiz")
                .structuralRefs(1)
                .eventTypes(List.of("InvalidateQuizEvent"))
                .readCommands(List.of("GetQuizByIdCommand"))
                .totalWeight(2)
                .build();
        Map<String, InterServiceDependency> deps = new LinkedHashMap<>();
        deps.put("quiz", withEvent);

        ServiceStaticMetrics rich = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentQuiz"))
                .invariants(List.of())
                .crossServiceEntities(Map.of("quiz", List.of("TournamentQuiz")))  // replica exists
                .interServiceDependencies(deps)
                .entityCount(2).build();

        ServiceStaticMetrics bare = consumer("tournament", "quiz",
                edge("quiz", List.of("GetQuizByIdCommand"), null));
        // Strip the existing replica so only the event differs from `rich`.
        bare.setCrossServiceEntities(Map.of());

        var quiz = source("quiz", List.of("Quiz"), List.of("Quiz"));
        double withBoth = find(rich, quiz).get(0).score();
        double withNeither = find(bare, quiz).get(0).score();

        assertThat(find(rich, quiz).get(0).syncEventAvailable()).isTrue();
        assertThat(find(rich, quiz).get(0).replicaAlreadyExists()).isTrue();
        assertThat(withBoth).isGreaterThan(withNeither);
    }
}
