package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command surface: what an aggregate accepts, and which functionality sends it.
 *
 * <p>It is the synchronous seam, and for {@code Course} it is the only API there is — its controller
 * and its {@code Functionalities} class are both empty, so create, delete and both counters exist
 * solely as commands another aggregate's saga sends. Counting workflow classes reports that aggregate
 * as having no behaviour at all.
 */
class CommandSurfaceTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    /** CourseCommandHandler's real shape: a switch over command types, each delegating once. */
    private static final String COURSE_HANDLER = """
            @Component
            public class CourseCommandHandler extends CommandHandler {

                @Override
                public Object handleDomainCommand(Command command) {
                    return switch (command) {
                        case GetCourseByIdCommand cmd -> handleGetCourseById(cmd);
                        case DeleteCourseCommand cmd -> handleDeleteCourse(cmd);
                        default -> {
                            logger.warning("Unknown command type: " + command.getClass().getName());
                            yield null;
                        }
                    };
                }

                private Object handleGetCourseById(GetCourseByIdCommand command) {
                    logger.info("Getting course by ID: " + command.getAggregateId());
                    return courseService.getCourseById(command.getAggregateId(), command.getUnitOfWork());
                }

                private Object handleDeleteCourse(DeleteCourseCommand command) {
                    logger.info("Deleting course: " + command.getCourseAggregateId());
                    courseService.deleteCourse(command.getCourseAggregateId(), command.getUnitOfWork());
                    return null;
                }
            }
            """;

    @Test
    @DisplayName("a command carries the whole chain: case, handler method, service method")
    void readsTheFullChain() {
        List<CommandInfo> commands = collector.handledCommands(Map.of("CourseCommandHandler", COURSE_HANDLER));

        assertThat(commands).extracting(CommandInfo::getName)
                .containsExactly("GetCourseByIdCommand", "DeleteCourseCommand");
        assertThat(commands.get(0).getHandlerMethod()).isEqualTo("CourseCommandHandler.handleGetCourseById");
        // The logger.info line above it must not be mistaken for the delegation.
        assertThat(commands.get(0).getServiceMethod()).isEqualTo("CourseService.getCourseById");
        // A void delegation is found as readily as a returned one.
        assertThat(commands.get(1).getServiceMethod()).isEqualTo("CourseService.deleteCourse");
    }

    @Test
    @DisplayName("the default arm is not a command")
    void ignoresTheDefaultArm() {
        assertThat(collector.handledCommands(Map.of("CourseCommandHandler", COURSE_HANDLER)))
                .noneMatch(c -> c.getName().toLowerCase().contains("default"));
    }

    // ─── The sending side ───────────────────────────────────────────────────────

    /** The map argument is the coupling side, which these cases do not assert on. */
    private List<FunctionalityInfo.SentCommand> sentBy(String content, String ownerService) {
        return collector.captureCommands(content, "SomeFunctionalitySagas", ownerService,
                Set.of("answer", "course", "quiz", "question"), new LinkedHashMap<>());
    }

    @Test
    @DisplayName("a command a functionality sends to its own service is still a call site")
    void keepsSelfAddressedCommands() {
        // Not inter-service coupling, so the M2-facing map leaves it out — but a refactoring has to
        // re-point or collapse this construction site like any other.
        List<FunctionalityInfo.SentCommand> sent = sentBy("""
                SagaStep step = new SagaStep("removeStep", () -> {
                    RemoveQuizAnswerCommand command = new RemoveQuizAnswerCommand(unitOfWork,
                            ServiceMapping.ANSWER.getServiceName(), answerAggregateId);
                    SagaCommand sagaCommand = new SagaCommand(command);
                    sagaCommand.setSemanticLock(QuizAnswerSagaState.IN_REMOVE);
                    commandGateway.send(sagaCommand);
                });
                """, "answer");

        assertThat(sent).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("RemoveQuizAnswerCommand");
            assertThat(c.getTargetService()).isEqualTo("answer");
            assertThat(c.getAccess()).isEqualTo("WRITE");
        });
    }

    @Test
    @DisplayName("a compensation call is recorded as a site, with no access")
    void recordsCompensationsWithoutAccess() {
        // It says nothing about how the entity is used, but it is a construction site all the same.
        List<FunctionalityInfo.SentCommand> sent = sentBy("""
                getCourseStep.registerCompensation(() -> {
                    Command command = new GetCourseByIdCommand(unitOfWork,
                            ServiceMapping.COURSE.getServiceName(), courseAggregateId);
                    SagaCommand sagaCommand = new SagaCommand(command);
                    sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                    commandGateway.send(sagaCommand);
                });
                """, "question");

        assertThat(sent).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("GetCourseByIdCommand");
            assertThat(c.getAccess()).isNull();
        });
    }

    @Test
    @DisplayName("the read/write split follows the semantic lock")
    void classifiesAccessFromTheLock() {
        List<FunctionalityInfo.SentCommand> sent = sentBy("""
                SagaStep get = new SagaStep("getCourseStep", () -> {
                    GetCourseByIdCommand c1 = new GetCourseByIdCommand(unitOfWork,
                            ServiceMapping.COURSE.getServiceName(), courseAggregateId);
                    SagaCommand s1 = new SagaCommand(c1);
                    s1.setSemanticLock(CourseSagaState.READ_COURSE);
                    commandGateway.send(s1);
                });
                SagaStep update = new SagaStep("updateStep", () -> {
                    UpdateCourseQuestionCountCommand c2 = new UpdateCourseQuestionCountCommand(unitOfWork,
                            ServiceMapping.COURSE.getServiceName(), courseAggregateId);
                    SagaCommand s2 = new SagaCommand(c2);
                    s2.setSemanticLock(CourseSagaState.IN_UPDATE_QUESTION_COUNT);
                    commandGateway.send(s2);
                });
                """, "question");

        assertThat(sent).extracting(FunctionalityInfo.SentCommand::getAccess)
                .containsExactly("READ", "WRITE");
    }
}
