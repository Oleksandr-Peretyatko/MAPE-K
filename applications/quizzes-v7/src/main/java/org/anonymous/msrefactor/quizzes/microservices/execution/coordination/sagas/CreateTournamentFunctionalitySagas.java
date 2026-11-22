package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.execution.CreateTournamentCommand;
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
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.*;
import java.util.stream.Collectors;

public class CreateTournamentFunctionalitySagas extends WorkflowFunctionality {
    private CourseExecutionDto courseExecutionDto;
    private UserDto userDto;
    private HashSet<TopicDto> topicDtos = new HashSet<>();
    private QuizDto quizDto;
    private TournamentDto tournamentDto;
    private List<QuestionDto> questionDtos;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public CreateTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                              Integer userId, Integer executionId, List<Integer> topicsId, TournamentDto tournamentDto,
                                              SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userId, executionId, topicsId, tournamentDto, unitOfWork);
    }

    public void buildWorkflow(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseExecutionStep = new SagaStep("getCourseExecutionStep", () -> {
            // by making this call locks regarding the course execution are guaranteed
            GetCourseExecutionByIdCommand getCourseExecutionByIdCommand = new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionId);
            SagaCommand sagaCommand = new SagaCommand(getCourseExecutionByIdCommand);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE);
            CourseExecutionDto courseExecutionDto = (CourseExecutionDto) commandGateway.send(sagaCommand);
            this.setCourseExecutionDto(courseExecutionDto);
        });

        SagaStep getCreatorStep = new SagaStep("getCreatorStep", () -> {
            // by making this call locks regarding the role of the creator are guaranteed
            // by making this call the invariants regarding the course execution and the
            // role of the creator are guaranteed
            GetStudentByExecutionIdAndUserIdCommand getStudentByExecutionIdAndUserIdCommand = new GetStudentByExecutionIdAndUserIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionId, userId);
            UserDto creatorDto = (UserDto) commandGateway.send(getStudentByExecutionIdAndUserIdCommand);
            this.setUserDto(creatorDto);
        }, new ArrayList<>(Arrays.asList(getCourseExecutionStep)));

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> { // TODO EACH TOPIC IN A SEPARATE STEP??
            topicsId.forEach(topicId -> {
                GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicId);
                SagaCommand sagaCommand = new SagaCommand(getTopicByIdCommand);
                sagaCommand.setSemanticLock(TopicSagaState.READ_TOPIC);
                TopicDto topic = (TopicDto) commandGateway.send(sagaCommand);
                this.addTopicDto(topic);
            });
        });

        SagaStep findQuestionsByTopicIdsStep = new SagaStep("findQuestionsByTopicIdsStep", () -> {
            FindQuestionsByTopicIdsCommand findQuestionsByTopicIdsCommand = new FindQuestionsByTopicIdsCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), topicsId);
            this.questionDtos = (List<QuestionDto>) commandGateway.send(findQuestionsByTopicIdsCommand);
        }, new ArrayList<>(Arrays.asList(getTopicsStep)));

        SagaStep getCourseExecutionById = new SagaStep("getCourseExecutionById", () -> {
            GetCourseExecutionByIdCommand getCourseExecutionByIdCommand = new GetCourseExecutionByIdCommand(unitOfWork,  ServiceMapping.EXECUTION.getServiceName(), executionId);
            this.courseExecutionDto = (CourseExecutionDto) commandGateway.send(getCourseExecutionByIdCommand);
        }, new ArrayList<>(Arrays.asList(findQuestionsByTopicIdsStep)));

        SagaStep generateQuizStep = new SagaStep("generateQuizStep", () -> {
            QuizDto quizDto = new QuizDto();
            quizDto.setAvailableDate(tournamentDto.getStartTime());
            quizDto.setConclusionDate(tournamentDto.getEndTime());
            quizDto.setResultsDate(tournamentDto.getEndTime());
            GenerateQuizCommand generateQuizCommand = new GenerateQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), courseExecutionDto, quizDto, questionDtos, tournamentDto.getNumberOfQuestions());
            QuizDto quizResultDto = (QuizDto) commandGateway.send(generateQuizCommand);
            this.setQuizDto(quizResultDto);
        }, new ArrayList<>(Arrays.asList(findQuestionsByTopicIdsStep, getCourseExecutionById)));

        generateQuizStep.registerCompensation(() -> {
            if (this.getQuizDto() != null) {
                RemoveQuizCommand removeQuizCommand = new RemoveQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), this.getQuizDto().getAggregateId());
                commandGateway.send(removeQuizCommand);
            }
        }, unitOfWork);

        SagaStep createTournamentStep = new SagaStep("createTournamentStep", () -> {
            CreateTournamentCommand createTournamentCommand = new CreateTournamentCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentDto, this.getUserDto(), this.getCourseExecutionDto(), this.getTopicsDtos(), this.getQuizDto());
            TournamentDto tournamentResultDto = (TournamentDto) commandGateway.send(createTournamentCommand);
            this.setTournamentDto(tournamentResultDto);
        }, new ArrayList<>(Arrays.asList(getCreatorStep, getCourseExecutionStep, getTopicsStep, generateQuizStep)));

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

        this.workflow.addStep(getCreatorStep);
        this.workflow.addStep(getCourseExecutionStep);
        this.workflow.addStep(getTopicsStep);
        this.workflow.addStep(findQuestionsByTopicIdsStep);
        this.workflow.addStep(getCourseExecutionById);
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
        return topicDtos.stream()
                .map(TopicDto -> (TopicDto) TopicDto)
                .collect(Collectors.toSet());
    }

    public void setTopicsDtos(HashSet<TopicDto> topicDtos) {
        this.topicDtos = topicDtos;
    }

    public void addTopicDto(TopicDto topicDto) {
        this.topicDtos.add(topicDto);
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

}
