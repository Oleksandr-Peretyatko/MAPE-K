package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetCourseExecutionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetStudentByExecutionIdAndUserIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.FindQuestionsByTopicIdsCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GenerateQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.topic.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournament.CreateTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.CreateTournamentSatelliteCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.RemoveTournamentSatelliteCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.CreateTournamentSatelliteSatelliteCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.RemoveTournamentSatelliteSatelliteCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas.states.TopicSagaState;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
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

    private CompletableFuture<CourseExecutionDto> courseExecutionDtoFuture;
    private CompletableFuture<UserDto> userDtoFuture;
    private CompletableFuture<Set<TopicDto>> topicDtosFuture;
    private CompletableFuture<List<QuestionDto>> questionDtosFuture;

    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public CreateTournamentAsyncFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                   Integer userId, Integer executionId, List<Integer> topicsId,
                                                   TournamentDto tournamentDto, SagaUnitOfWork unitOfWork,
                                                   CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userId, executionId, topicsId, tournamentDto, unitOfWork);
    }

    public void buildWorkflow(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCreatorAsyncStep = new SagaStep("getCreatorAsyncStep", () -> {
            GetStudentByExecutionIdAndUserIdCommand getStudentByExecutionIdAndUserIdCommand =
                    new GetStudentByExecutionIdAndUserIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(),
                            executionId, userId);
            this.userDtoFuture = commandGateway.sendAsync(getStudentByExecutionIdAndUserIdCommand)
                    .thenApply(dto -> (UserDto) dto);
        });

        SagaStep getCourseExecutionAsyncStep = new SagaStep("getCourseExecutionAsyncStep", () -> {
            GetCourseExecutionByIdCommand getCourseExecutionByIdCommand =
                    new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(),
                            executionId);
            SagaCommand getCourseExecutionSagaCommand = new SagaCommand(getCourseExecutionByIdCommand);
            getCourseExecutionSagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
            this.courseExecutionDtoFuture = commandGateway.sendAsync(getCourseExecutionSagaCommand)
                    .thenApply(dto -> (CourseExecutionDto) dto);
        });

        SagaStep getTopicsAsyncStep = new SagaStep("getTopicsAsyncStep", () -> {
            List<CompletableFuture<TopicDto>> topicFutures = topicsId.stream()
                    .map(topicId -> {
                        GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork,
                                ServiceMapping.TOPIC.getServiceName(), topicId);
                        SagaCommand getTopicSagaCommand = new SagaCommand(getTopicByIdCommand);
                        getTopicSagaCommand.setSemanticLock(TopicSagaState.READ_TOPIC);
                        return commandGateway.sendAsync(getTopicSagaCommand).thenApply(dto -> (TopicDto) dto);
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
            this.setCourseExecutionDto(this.courseExecutionDtoFuture.join());
            this.questionDtos = this.questionDtosFuture.join();

            QuizDto quizDto = new QuizDto();
            quizDto.setAvailableDate(tournamentDto.getStartTime());
            quizDto.setConclusionDate(tournamentDto.getEndTime());
            quizDto.setResultsDate(tournamentDto.getEndTime());

            GenerateQuizCommand generateQuizCommand = new GenerateQuizCommand(unitOfWork,
                    ServiceMapping.EXECUTION.getServiceName(), this.courseExecutionDto, quizDto, this.questionDtos,
                    tournamentDto.getNumberOfQuestions());
            QuizDto quizResultDto = (QuizDto) commandGateway.send(generateQuizCommand);
            this.setQuizDto(quizResultDto);
            }, new ArrayList<>(Arrays.asList(getCourseExecutionAsyncStep, findQuestionsByTopicIdsAsyncStep)));

        generateQuizStep.registerCompensation(() -> {
            if (this.getQuizDto() != null) {
                RemoveQuizCommand removeQuizCommand = new RemoveQuizCommand(unitOfWork,
                        ServiceMapping.EXECUTION.getServiceName(), this.getQuizDto().getAggregateId());
                commandGateway.send(removeQuizCommand);
            }
        }, unitOfWork);

        SagaStep createTournamentStep = new SagaStep("createTournamentStep", () -> {
            this.setUserDto(this.userDtoFuture.join());
            this.setTopicsDtos(new HashSet<>(this.topicDtosFuture.join()));

            CreateTournamentCommand createTournamentCommand = new CreateTournamentCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT.getServiceName(), tournamentDto, this.getUserDto(),
                    this.getCourseExecutionDto(), this.getTopicsDtos(), this.getQuizDto());
            TournamentDto tournamentResultDto = (TournamentDto) commandGateway.send(createTournamentCommand);
            this.setTournamentDto(tournamentResultDto);
        }, new ArrayList<>(Arrays.asList(generateQuizStep, getCreatorAsyncStep, getTopicsAsyncStep)));

        SagaStep createTournamentSatelliteStep = new SagaStep("createTournamentSatelliteStep", () -> {
            CreateTournamentSatelliteCommand createTournamentSatelliteCommand = new CreateTournamentSatelliteCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(),
                    this.getTournamentDto().getAggregateId(), this.getUserDto(),
                    this.getCourseExecutionDto().getAggregateId(), this.getCourseExecutionDto().getVersion(),
                    this.getTournamentDto().getStartTime(), this.getTournamentDto().isCancelled());
            commandGateway.send(createTournamentSatelliteCommand);
        }, new ArrayList<>(Arrays.asList(createTournamentStep)));

        createTournamentSatelliteStep.registerCompensation(() -> {
            if (this.getTournamentDto() != null) {
                RemoveTournamentSatelliteCommand removeTournamentSatelliteCommand = new RemoveTournamentSatelliteCommand(
                        unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(),
                        this.getTournamentDto().getAggregateId());
                commandGateway.send(removeTournamentSatelliteCommand);
            }
        }, unitOfWork);

        SagaStep createTournamentSatelliteSatelliteStep = new SagaStep("createTournamentSatelliteSatelliteStep", () -> {
            CreateTournamentSatelliteSatelliteCommand createTournamentSatelliteSatelliteCommand = new CreateTournamentSatelliteSatelliteCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(),
                    this.getTournamentDto().getAggregateId(), this.getUserDto(),
                    this.getCourseExecutionDto().getAggregateId(), this.getCourseExecutionDto().getVersion(),
                    this.getTournamentDto().getStartTime(), this.getTournamentDto().isCancelled());
            commandGateway.send(createTournamentSatelliteSatelliteCommand);
        }, new ArrayList<>(Arrays.asList(createTournamentSatelliteStep)));

        createTournamentSatelliteSatelliteStep.registerCompensation(() -> {
            if (this.getTournamentDto() != null) {
                RemoveTournamentSatelliteSatelliteCommand removeTournamentSatelliteSatelliteCommand = new RemoveTournamentSatelliteSatelliteCommand(
                        unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(),
                        this.getTournamentDto().getAggregateId());
                commandGateway.send(removeTournamentSatelliteSatelliteCommand);
            }
        }, unitOfWork);

        this.workflow.addStep(getCreatorAsyncStep);
        this.workflow.addStep(getCourseExecutionAsyncStep);
        this.workflow.addStep(getTopicsAsyncStep);
        this.workflow.addStep(findQuestionsByTopicIdsAsyncStep);
        this.workflow.addStep(generateQuizStep);
        this.workflow.addStep(createTournamentStep);
        this.workflow.addStep(createTournamentSatelliteStep);
        this.workflow.addStep(createTournamentSatelliteSatelliteStep);
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