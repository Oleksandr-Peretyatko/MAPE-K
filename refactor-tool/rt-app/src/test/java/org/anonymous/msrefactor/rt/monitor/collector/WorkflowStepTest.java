package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workflow read as an ordered thing rather than a bag of commands.
 *
 * <p>What makes this worth parsing at all: declaration order is not execution order. {@code
 * SagaWorkflow.planOrder()} topologically sorts the dependency DAG, so two steps with no edge
 * between them run in whichever order a {@code HashMap} iteration produces. A view that numbered
 * them 1..N and stopped would assert a sequence the engine never promised.
 */
class WorkflowStepTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    private static final Set<String> SERVICES =
            Set.of("answer", "course", "execution", "quiz", "question", "user", "topic");

    /** Parses a workflow the way Pass 4 does: one command scan, two views of it. */
    private StaticAnalysisCollector.WorkflowParse parse(String content) {
        List<FunctionalityInfo.SentCommand> sent = collector.captureCommands(
                content, "SomeFunctionalitySagas", "answer", SERVICES, new LinkedHashMap<>());
        return collector.captureSteps(content, collector.commandSites(content, SERVICES), sent);
    }

    private static FunctionalityInfo.WorkflowStep step(
            StaticAnalysisCollector.WorkflowParse parse, String name) {
        return parse.steps().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no step named " + name + " in " + parse.steps()));
    }

    // ─── The shape the application actually uses ────────────────────────────────

    /** StartQuizFunctionalitySagas, reduced to what the parser has to read. */
    private static final String START_QUIZ = """
            public void buildWorkflow(Integer quizAggregateId, SagaUnitOfWork unitOfWork) {
                this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

                SagaStep getQuizStep = new SagaStep("getQuizStep", () -> {
                    GetQuizByIdCommand getQuizByIdCommand = new GetQuizByIdCommand(unitOfWork,
                            ServiceMapping.QUIZ.getServiceName(), quizAggregateId);
                    SagaCommand sagaCommand = new SagaCommand(getQuizByIdCommand);
                    sagaCommand.setSemanticLock(QuizSagaState.READ_QUIZ);
                    QuizDto quizDto = (QuizDto) commandGateway.send(sagaCommand);
                    this.setQuizDto(quizDto);
                });

                SagaStep getUserStep = new SagaStep("getUserStep", () -> {
                    GetUserByIdCommand getUserByIdCommand = new GetUserByIdCommand(unitOfWork,
                            ServiceMapping.USER.getServiceName(), userAggregateId);
                    UserDto userDto = (UserDto) commandGateway.send(getUserByIdCommand);
                    this.setUserDto(userDto);
                });

                SagaStep startQuizStep = new SagaStep("startQuizStep", () -> {
                    StartQuizCommand startQuizCommand = new StartQuizCommand(unitOfWork,
                            ServiceMapping.ANSWER.getServiceName(), quizAggregateId);
                    commandGateway.send(startQuizCommand);
                }, new ArrayList<>(Arrays.asList(getQuizStep, getUserStep)));

                workflow.addStep(getQuizStep);
                workflow.addStep(getUserStep);
                workflow.addStep(startQuizStep);
            }
            """;

    @Test
    @DisplayName("each step keeps the commands built inside it, and only those")
    void assignsCommandsToTheirStep() {
        StaticAnalysisCollector.WorkflowParse parse = parse(START_QUIZ);

        assertThat(parse.steps()).extracting(FunctionalityInfo.WorkflowStep::getName)
                .containsExactly("getQuizStep", "getUserStep", "startQuizStep");
        assertThat(step(parse, "getQuizStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("GetQuizByIdCommand");
        assertThat(step(parse, "startQuizStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("StartQuizCommand");
        // Nothing is dropped on the floor: every site the flat list holds landed in a step.
        assertThat(parse.loose()).isEmpty();
    }

    @Test
    @DisplayName("the dependency argument becomes the order, and the waves follow from it")
    void readsTheDependencyGraph() {
        StaticAnalysisCollector.WorkflowParse parse = parse(START_QUIZ);

        assertThat(step(parse, "getQuizStep").getDependsOn()).isNull();
        assertThat(step(parse, "startQuizStep").getDependsOn())
                .containsExactly("getQuizStep", "getUserStep");
        // The two reads are unordered with respect to each other; the write is not.
        assertThat(step(parse, "getQuizStep").getWave()).isZero();
        assertThat(step(parse, "getUserStep").getWave()).isZero();
        assertThat(step(parse, "startQuizStep").getWave()).isEqualTo(1);
    }

    @Test
    @DisplayName("declaration order is reported as an index, not as the running order")
    void numbersStepsInDeclarationOrder() {
        assertThat(parse(START_QUIZ).steps())
                .extracting(FunctionalityInfo.WorkflowStep::getIndex)
                .containsExactly(1, 2, 3);
    }

    // ─── Compensation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a compensation hangs off the step whose variable it is called on")
    void attachesCompensationToItsStep() {
        // The bare Command carries no entity — it exists to release the semantic lock — so
        // COMMAND_TO_SERVICE excludes it and only this path can see it.
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                SagaStep getOldStep = new SagaStep("getOldStep", () -> {
                    GetCourseExecutionByIdCommand c = new GetCourseExecutionByIdCommand(unitOfWork,
                            ServiceMapping.EXECUTION.getServiceName(), id);
                    commandGateway.send(c);
                });

                getOldStep.registerCompensation(() -> {
                    Command command = new Command(unitOfWork,
                            ServiceMapping.EXECUTION.getServiceName(), id);
                    SagaCommand sagaCommand = new SagaCommand(command);
                    sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                    commandGateway.send(sagaCommand);
                }, unitOfWork);

                SagaStep removeStep = new SagaStep("removeStep", () -> {
                    RemoveStudentCommand r = new RemoveStudentCommand(unitOfWork,
                            ServiceMapping.EXECUTION.getServiceName(), id);
                    commandGateway.send(r);
                }, new ArrayList<>(Arrays.asList(getOldStep)));

                workflow.addStep(getOldStep);
                workflow.addStep(removeStep);
                """);

        assertThat(step(parse, "getOldStep").getCompensation())
                .extracting(FunctionalityInfo.SentCommand::getName,
                            FunctionalityInfo.SentCommand::getTargetService)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Command", "execution"));
        assertThat(step(parse, "removeStep").getCompensation()).isNull();
        // The compensation's site must not be counted as work the step does on the happy path.
        assertThat(step(parse, "getOldStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("GetCourseExecutionByIdCommand");
    }

    @Test
    @DisplayName("a typed command inside a compensation belongs to the compensation, not the body")
    void placesTypedCompensationCommands() {
        // Five sites in the application do this. They are already in the flat list with a null
        // access; partitioning by offset is what puts them on the right side of the step.
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                SagaStep startStep = new SagaStep("startStep", () -> {
                    StartQuizCommand s = new StartQuizCommand(unitOfWork,
                            ServiceMapping.ANSWER.getServiceName(), id);
                    commandGateway.send(s);
                });

                startStep.registerCompensation(() -> {
                    RemoveQuizAnswerCommand r = new RemoveQuizAnswerCommand(unitOfWork,
                            ServiceMapping.ANSWER.getServiceName(), id);
                    SagaCommand sagaCommand = new SagaCommand(r);
                    sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                    commandGateway.send(sagaCommand);
                }, unitOfWork);

                workflow.addStep(startStep);
                """);

        assertThat(step(parse, "startStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("StartQuizCommand");
        assertThat(step(parse, "startStep").getCompensation())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("RemoveQuizAnswerCommand");
    }

    // ─── What the parser is asked to report rather than hide ────────────────────

    @Test
    @DisplayName("a step never added to the workflow is flagged, not dropped")
    void reportsUnregisteredSteps() {
        // It compiles, it never runs, and nothing else in the toolchain notices.
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                SagaStep liveStep = new SagaStep("liveStep", () -> {
                    GetQuizByIdCommand c = new GetQuizByIdCommand(unitOfWork,
                            ServiceMapping.QUIZ.getServiceName(), id);
                    commandGateway.send(c);
                });

                SagaStep deadStep = new SagaStep("deadStep", () -> {
                    GetUserByIdCommand c = new GetUserByIdCommand(unitOfWork,
                            ServiceMapping.USER.getServiceName(), id);
                    commandGateway.send(c);
                });

                workflow.addStep(liveStep);
                """);

        assertThat(step(parse, "liveStep").isRegistered()).isTrue();
        assertThat(step(parse, "deadStep").isRegistered()).isFalse();
    }

    @Test
    @DisplayName("a command outside every step is surfaced, not silently lost")
    void reportsLooseCommands() {
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                public void buildWorkflow(SagaUnitOfWork unitOfWork) {
                    GetQuizByIdCommand eager = new GetQuizByIdCommand(unitOfWork,
                            ServiceMapping.QUIZ.getServiceName(), id);
                    commandGateway.send(eager);

                    SagaStep onlyStep = new SagaStep("onlyStep", () -> {
                        GetUserByIdCommand c = new GetUserByIdCommand(unitOfWork,
                                ServiceMapping.USER.getServiceName(), id);
                        commandGateway.send(c);
                    });
                    workflow.addStep(onlyStep);
                }
                """);

        assertThat(parse.loose()).extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("GetQuizByIdCommand");
        assertThat(step(parse, "onlyStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("GetUserByIdCommand");
    }

    // ─── Brace matching ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a brace inside a string literal does not end the step")
    void survivesBracesInLiterals() {
        // Left to naive scanning, the body would close at the literal and the command after it
        // would be reported as belonging to no step at all.
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                SagaStep logStep = new SagaStep("logStep", () -> {
                    logger.info("closing brace } and quote \\" inside a literal");
                    char c = '}';
                    // a } in a line comment
                    /* and a } in a block comment */
                    GetQuizByIdCommand cmd = new GetQuizByIdCommand(unitOfWork,
                            ServiceMapping.QUIZ.getServiceName(), id);
                    commandGateway.send(cmd);
                });
                workflow.addStep(logStep);
                """);

        assertThat(parse.loose()).isEmpty();
        assertThat(step(parse, "logStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("GetQuizByIdCommand");
    }

    @Test
    @DisplayName("nested blocks inside a step body do not end it early")
    void survivesNestedBlocks() {
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                SagaStep branchStep = new SagaStep("branchStep", () -> {
                    if (this.getQuizDto() != null) {
                        for (Integer id : ids) {
                            states.add(QuizSagaState.IN_UPDATE);
                        }
                    }
                    UpdateQuizCommand cmd = new UpdateQuizCommand(unitOfWork,
                            ServiceMapping.QUIZ.getServiceName(), id);
                    commandGateway.send(cmd);
                });
                workflow.addStep(branchStep);
                """);

        assertThat(step(parse, "branchStep").getCommands())
                .extracting(FunctionalityInfo.SentCommand::getName)
                .containsExactly("UpdateQuizCommand");
    }

    @Test
    @DisplayName("a chain of dependencies deepens the wave")
    void computesWaveAlongTheLongestChain() {
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                SagaStep a = new SagaStep("a", () -> {
                    GetQuizByIdCommand c = new GetQuizByIdCommand(unitOfWork,
                            ServiceMapping.QUIZ.getServiceName(), id);
                    commandGateway.send(c);
                });
                SagaStep b = new SagaStep("b", () -> {
                    GetUserByIdCommand c = new GetUserByIdCommand(unitOfWork,
                            ServiceMapping.USER.getServiceName(), id);
                    commandGateway.send(c);
                }, new ArrayList<>(Arrays.asList(a)));
                SagaStep c = new SagaStep("c", () -> {
                    GetTopicByIdCommand c2 = new GetTopicByIdCommand(unitOfWork,
                            ServiceMapping.TOPIC.getServiceName(), id);
                    commandGateway.send(c2);
                }, new ArrayList<>(Arrays.asList(a, b)));
                workflow.addStep(a); workflow.addStep(b); workflow.addStep(c);
                """);

        // c depends on both a and b: the longest chain wins, so it is wave 2, not wave 1.
        assertThat(parse.steps()).extracting(FunctionalityInfo.WorkflowStep::getWave)
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("a workflow with no step yields no steps rather than an empty scaffold")
    void handlesAbsentWorkflow() {
        StaticAnalysisCollector.WorkflowParse parse = parse("""
                public class NoWorkflow {
                    public void doThing() { logger.info("nothing to see"); }
                }
                """);
        assertThat(parse.steps()).isEmpty();
        assertThat(parse.loose()).isEmpty();
    }
}
