package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.question.FindQuestionsByTopicIdsCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.GenerateQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.RemoveQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.course.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/*
 * Merge execution <- tournament: the execution reads and the tournament creation are
 * in-process (the futures stay so the step shape is unchanged); topic, question and quiz
 * steps keep their remote async fan-out.
 */
public class CreateTournamentAsyncFunctionalitySagas extends WorkflowFunctionality {
    private CourseExecutionDto courseExecutionDto;
    private UserDto userDto;
    private HashSet<TopicDto> topicDtos = new HashSet<>();
    private QuizDto quizDto;
    private TournamentDto tournamentDto;
    private List<QuestionDto> questionDtos;

    private CompletableFuture<CourseExecutionDto> courseExecutionDtoFuture;
    private CompletableFuture<UserDto> userDtoFuture;
    private CompletableFuture<Set<TopicDto>> topicDtosFuture;
    private CompletableFuture<List<QuestionDto>> questionDtosFuture;

    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private final CommandGateway commandGateway;

    public CreateTournamentAsyncFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                                   Integer userId, Integer executionId, List<Integer> topicsId,
                                                   TournamentDto tournamentDto, SagaUnitOfWork unitOfWork,
                                                   CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userId, executionId, topicsId, tournamentDto, unitOfWork);
    }

    public void buildWorkflow(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCreatorAsyncStep = new SagaStep("getCreatorAsyncStep", () -> {
            this.userDtoFuture = CompletableFuture.completedFuture(
                    executionService.getStudentByExecutionIdAndUserId(executionId, userId, unitOfWork));
        });

        SagaStep getCourseExecutionAsyncStep = new SagaStep("getCourseExecutionAsyncStep", () -> {
            unitOfWorkService.registerSagaState(executionId, CourseExecutionSagaState.READ_COURSE, unitOfWork);
            this.courseExecutionDtoFuture = CompletableFuture.completedFuture(
                    executionService.getCourseExecutionById(executionId, unitOfWork));
        });

        SagaStep getTopicsAsyncStep = new SagaStep("getTopicsAsyncStep", () -> {
            List<CompletableFuture<TopicDto>> topicFutures = topicsId.stream()
                    .map(topicId -> {
                        GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork,
                                ServiceMapping.COURSE.getServiceName(), topicId);
                        return commandGateway.sendAsync(getTopicByIdCommand).thenApply(dto -> (TopicDto) dto);
                    })
                    .collect(Collectors.toList());

            this.topicDtosFuture = CompletableFuture.allOf(topicFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> topicFutures.stream().map(CompletableFuture::join).collect(Collectors.toSet()));
    });

    SagaStep findQuestionsByTopicIdsAsyncStep = new SagaStep("findQuestionsByTopicIdsAsyncStep", () -> {
            FindQuestionsByTopicIdsCommand findQuestionsByTopicIdsCommand =
                    new FindQuestionsByTopicIdsCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), topicsId);
            this.questionDtosFuture = commandGateway.sendAsync(findQuestionsByTopicIdsCommand)
                    .thenApply(CreateTournamentAsyncFunctionalitySagas::castQuestionDtos);
        });

        SagaStep generateQuizStep = new SagaStep("generateQuizStep", () -> {
            this.setCourseExecutionDto(this.courseExecutionDtoFuture.join());
            this.questionDtos = this.questionDtosFuture.join();

            QuizDto quizDto = new QuizDto();
            quizDto.setAvailableDate(tournamentDto.getStartTime());
            quizDto.setConclusionDate(tournamentDto.getEndTime());
            quizDto.setResultsDate(tournamentDto.getEndTime());

            GenerateQuizCommand generateQuizCommand = new GenerateQuizCommand(unitOfWork,
                    ServiceMapping.QUIZ.getServiceName(), this.courseExecutionDto, quizDto, this.questionDtos,
                    tournamentDto.getNumberOfQuestions());
            QuizDto quizResultDto = (QuizDto) commandGateway.send(generateQuizCommand);
            this.setQuizDto(quizResultDto);
            }, new ArrayList<>(Arrays.asList(getCourseExecutionAsyncStep, findQuestionsByTopicIdsAsyncStep)));

        generateQuizStep.registerCompensation(() -> {
            if (this.getQuizDto() != null) {
                RemoveQuizCommand removeQuizCommand = new RemoveQuizCommand(unitOfWork,
                        ServiceMapping.QUIZ.getServiceName(), this.getQuizDto().getAggregateId());
                commandGateway.send(removeQuizCommand);
            }
        }, unitOfWork);

        SagaStep createTournamentStep = new SagaStep("createTournamentStep", () -> {
            this.setUserDto(this.userDtoFuture.join());
            this.setTopicsDtos(new HashSet<>(this.topicDtosFuture.join()));

            TournamentDto tournamentResultDto = executionService.createTournament(tournamentDto, this.getUserDto(),
                    this.getCourseExecutionDto(), this.getTopicsDtos(), this.getQuizDto(), unitOfWork);
            this.setTournamentDto(tournamentResultDto);
        }, new ArrayList<>(Arrays.asList(generateQuizStep, getCreatorAsyncStep, getTopicsAsyncStep)));

        this.workflow.addStep(getCreatorAsyncStep);
        this.workflow.addStep(getCourseExecutionAsyncStep);
        this.workflow.addStep(getTopicsAsyncStep);
        this.workflow.addStep(findQuestionsByTopicIdsAsyncStep);
        this.workflow.addStep(generateQuizStep);
        this.workflow.addStep(createTournamentStep);
    }

    public void setCourseExecutionDto(CourseExecutionDto courseExecutionDto) {
        this.courseExecutionDto = courseExecutionDto;
    }

    public CourseExecutionDto getCourseExecutionDto() {
        return courseExecutionDto;
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }

    public UserDto getUserDto() {
        return userDto;
    }

    public Set<TopicDto> getTopicsDtos() {
        return new HashSet<>(this.topicDtos);
    }

    public void setTopicsDtos(HashSet<TopicDto> topicDtos) {
        this.topicDtos = topicDtos;
    }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public void setQuizDto(QuizDto quizDto) {
        this.quizDto = quizDto;
    }

    public TournamentDto getTournamentDto() {
        return this.tournamentDto;
    }

    public void setTournamentDto(TournamentDto tournamentDto) {
        this.tournamentDto = tournamentDto;
    }

    @SuppressWarnings("unchecked")
    private static List<QuestionDto> castQuestionDtos(Object dto) {
        return (List<QuestionDto>) dto;
    }
}
