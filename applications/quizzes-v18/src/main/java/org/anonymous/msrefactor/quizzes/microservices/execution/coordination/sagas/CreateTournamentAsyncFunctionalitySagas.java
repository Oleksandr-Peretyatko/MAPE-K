package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.course.FindQuestionsByTopicIdsCommand;
import org.anonymous.msrefactor.quizzes.commands.course.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class CreateTournamentAsyncFunctionalitySagas extends WorkflowFunctionality {
    private CourseExecutionDto courseExecutionDto;
    private UserDto userDto;
    private HashSet<TopicDto> topicDtos = new HashSet<>();
    private QuizDto quizDto;
    private TournamentDto tournamentDto;
    private List<QuestionDto> questionDtos;

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
            // execution reads are in-process now; the async hop to execution-service is gone
            this.setUserDto(executionService.getStudentByExecutionIdAndUserId(executionId, userId, unitOfWork));
        });

        SagaStep getCourseExecutionAsyncStep = new SagaStep("getCourseExecutionAsyncStep", () -> {
            // the semantic lock the remote read took on the execution is kept in-process
            // (Execution states are checked)
            unitOfWorkService.registerSagaState(executionId, CourseExecutionSagaState.READ_COURSE, unitOfWork);
            this.setCourseExecutionDto(executionService.getCourseExecutionById(executionId, unitOfWork));
        });

        SagaStep getTopicsAsyncStep = new SagaStep("getTopicsAsyncStep", () -> {
            // re-pointed to course (topics are members of the Course aggregate now); the
            // READ_TOPIC lock was dropped — Topic's states were unchecked — so the
            // command is sent bare
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
                    new FindQuestionsByTopicIdsCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), topicsId);
            this.questionDtosFuture = commandGateway.sendAsync(findQuestionsByTopicIdsCommand)
                    .thenApply(CreateTournamentAsyncFunctionalitySagas::castQuestionDtos);
        });

        SagaStep generateQuizStep = new SagaStep("generateQuizStep", () -> {
            this.questionDtos = this.questionDtosFuture.join();

            QuizDto quizDto = new QuizDto();
            quizDto.setAvailableDate(tournamentDto.getStartTime());
            quizDto.setConclusionDate(tournamentDto.getEndTime());
            quizDto.setResultsDate(tournamentDto.getEndTime());

            QuizDto quizResultDto = executionService.generateQuiz(executionId, quizDto, this.questionDtos,
                    tournamentDto.getNumberOfQuestions(), unitOfWork);
            this.setQuizDto(quizResultDto);
            }, new ArrayList<>(Arrays.asList(getCourseExecutionAsyncStep, findQuestionsByTopicIdsAsyncStep)));

        generateQuizStep.registerCompensation(() -> {
            if (this.getQuizDto() != null) {
                executionService.removeQuiz(this.getQuizDto().getAggregateId(), unitOfWork);
            }
        }, unitOfWork);

        SagaStep createTournamentStep = new SagaStep("createTournamentStep", () -> {
            this.setTopicsDtos(new HashSet<>(this.topicDtosFuture.join()));

            TournamentDto tournamentResultDto = executionService.createTournament(executionId, tournamentDto,
                    this.getUserDto(), this.getTopicsDtos(), this.getQuizDto(), unitOfWork);
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
