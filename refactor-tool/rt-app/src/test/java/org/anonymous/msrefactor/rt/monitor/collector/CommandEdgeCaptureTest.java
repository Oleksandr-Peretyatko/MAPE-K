package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether one service's call to another is a read or a write is the fact {@code CreateReplica} turns
 * on, and nothing else the collector produces can express it — {@code readWriteRatio} is a service's
 * own global traffic mix, not a property of an edge.
 *
 * <p>The application already marks it twice over: the saga step's semantic lock ({@code READ_*} vs
 * {@code IN_*}) and the command's verb. The lock is authoritative because the verb lies —
 * {@code GetAndOrCreateCourseRemoteCommand} reads <i>and</i> writes.
 */
class CommandEdgeCaptureTest {

    private static final Set<String> SERVICES =
            Set.of("quiz", "execution", "question", "course", "tournament", "answer");

    private StaticAnalysisCollector collector;
    private Map<String, Map<String, StaticAnalysisCollector.CommandUsage>> out;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
        out = new LinkedHashMap<>();
    }

    private StaticAnalysisCollector.CommandUsage edge(String owner, String target) {
        return out.getOrDefault(owner, Map.of()).get(target);
    }

    private void capture(String content) {
        collector.captureCommands(content, "CreateQuizFunctionalitySagas", "quiz", SERVICES, out);
    }

    // ─── The lock decides ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a READ_*-locked step is a read of the target")
    void readLockedStepIsARead() {
        capture("""
                SagaStep getCourseExecutionStep = new SagaStep("getCourseExecutionStep", () -> {
                    GetCourseExecutionByIdCommand cmd = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionId);
                    SagaCommand sagaCommand = new SagaCommand(cmd);
                    sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
                    this.courseExecutionDto = (CourseExecutionDto) commandGateway.send(sagaCommand);
                });
                """);

        assertThat(edge("quiz", "execution").reads()).containsExactly("GetCourseExecutionByIdCommand");
        assertThat(edge("quiz", "execution").writes()).isEmpty();
    }

    @Test
    @DisplayName("an IN_*-locked step is a write of the target")
    void writeLockedStepIsAWrite() {
        capture("""
                SagaStep updateStep = new SagaStep("updateStep", () -> {
                    UpdateStudentNameCommand cmd = new UpdateStudentNameCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), id, name);
                    SagaCommand sagaCommand = new SagaCommand(cmd);
                    sagaCommand.setSemanticLock(CourseExecutionSagaState.IN_UPDATE_NAME);
                    commandGateway.send(sagaCommand);
                });
                """);

        assertThat(edge("quiz", "execution").writes()).containsExactly("UpdateStudentNameCommand");
        assertThat(edge("quiz", "execution").reads()).isEmpty();
    }

    @Test
    @DisplayName("the lock beats the verb — GetAndOrCreate… under IN_* is a write")
    void lockOverridesTheVerb() {
        // The naming convention's one real violator. Classifying by verb alone would call this a
        // read and let an ownership violation through as a valid replica candidate.
        capture("""
                SagaStep step = new SagaStep("step", () -> {
                    GetAndOrCreateCourseRemoteCommand cmd = new GetAndOrCreateCourseRemoteCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), dto);
                    SagaCommand sagaCommand = new SagaCommand(cmd);
                    sagaCommand.setSemanticLock(CourseSagaState.IN_CREATE_COURSE);
                    commandGateway.send(sagaCommand);
                });
                """);

        assertThat(edge("quiz", "course").writes()).containsExactly("GetAndOrCreateCourseRemoteCommand");
        assertThat(edge("quiz", "course").reads()).isEmpty();
    }

    @Test
    @DisplayName("a NOT_IN_SAGA compensation is neither a read nor a write")
    void compensationIsIgnored() {
        // A compensation releases a lock; it says nothing about how the entity is used, and the bare
        // `Command` class it constructs names no entity to replicate.
        capture("""
                getCourseExecutionStep.registerCompensation(() -> {
                    Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionId);
                    SagaCommand sagaCommand = new SagaCommand(command);
                    sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                    commandGateway.send(sagaCommand);
                });
                """);

        assertThat(out).as("the bare Command class is not a named command").isEmpty();
    }

    // ─── No semantic lock ───────────────────────────────────────────────────────

    @Test
    @DisplayName("without a lock the verb decides")
    void noLockFallsBackToTheVerb() {
        capture("""
                GetCourseExecutionByIdCommand c1 = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), id);
                this.dto = (CourseExecutionDto) commandGateway.send(c1);
                CreateQuizCommand c2 = new CreateQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), dto);
                commandGateway.send(c2);
                QuestionDto q = (QuestionDto) commandGateway.send(new GetQuestionByIdCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), qid));
                """);

        assertThat(edge("quiz", "execution").reads()).containsExactly("GetCourseExecutionByIdCommand");
        // Inline construction inside commandGateway.send(...) is captured too.
        assertThat(edge("quiz", "question").reads()).containsExactly("GetQuestionByIdCommand");
        // A command to the owning service itself is not an edge.
        assertThat(edge("quiz", "quiz")).isNull();
    }

    // ─── Attribution ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a lock binds to the command it follows, not to a later one")
    void lockBindsToItsOwnCommand() {
        capture("""
                GetQuizByIdCommand a = new GetQuizByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), id);
                sagaCommand.setSemanticLock(SomeState.READ_X);
                UpdateThingCommand b = new UpdateThingCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), id);
                sagaCommand.setSemanticLock(SomeState.IN_UPDATE);
                """);

        assertThat(edge("quiz", "execution").reads()).containsExactly("GetQuizByIdCommand");
        assertThat(edge("quiz", "question").writes()).containsExactly("UpdateThingCommand");
    }

    @Test
    @DisplayName("classification is directly assertable")
    void classify() {
        assertThat(collector.classifyCommand("GetXCommand", "READ_X")).isTrue();
        assertThat(collector.classifyCommand("GetXCommand", "IN_UPDATE")).isFalse();
        assertThat(collector.classifyCommand("GetXCommand", "NOT_IN_SAGA")).isNull();
        assertThat(collector.classifyCommand("GetXCommand", null)).isTrue();
        assertThat(collector.classifyCommand("FindXCommand", null)).isTrue();
        assertThat(collector.classifyCommand("UpdateXCommand", null)).isFalse();
    }
}
