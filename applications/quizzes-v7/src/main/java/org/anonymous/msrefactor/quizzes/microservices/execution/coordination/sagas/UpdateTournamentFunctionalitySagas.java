package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetExecutionIdByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.FindQuestionsByTopicIdsCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.UpdateGeneratedQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.topic.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.UpdateTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.UpdateTournamentSatelliteScalarsCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.UpdateTournamentSatelliteSatelliteScalarsCommand;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.Quiz;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.*;
import java.util.stream.Collectors;

public class UpdateTournamentFunctionalitySagas extends WorkflowFunctionality {
    private Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentDto originalTournamentDto;
    private HashSet<TopicDto> topics = new HashSet<TopicDto>();
    private Tournament tournament;
    private TournamentDto newTournamentDto;
    private QuizDto quizDto;
    private Quiz quiz;
    private List<QuestionDto> questionDtos;

    public UpdateTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, TournamentDto tournamentDto, Set<Integer> topicsAggregateIds, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentDto, topicsAggregateIds, unitOfWork);
    }

    public void buildWorkflow(TournamentDto tournamentDto, Set<Integer> topicsAggregateIds, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getOriginalTournamentStep = new SagaStep("getOriginalTournamentStep", () -> {
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT);
            states.add(CourseExecutionSagaState.IN_DELETE_TOURNAMENT);
            GetExecutionIdByTournamentIdCommand lookupCommand = new GetExecutionIdByTournamentIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentDto.getAggregateId());
            this.executionAggregateId = (Integer) commandGateway.send(lookupCommand);
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentDto.getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            // lock and forbidden-state check retargeted to the owning execution root
            sagaCommand.setRootAggregateId(this.executionAggregateId);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT);
            sagaCommand.setForbiddenStates(states);
            TournamentDto originalTournamentDto = (TournamentDto) commandGateway.send(sagaCommand);
            setOriginalTournamentDto(originalTournamentDto);
        });

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> {
            topicsAggregateIds.forEach(topicId -> {
                GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicId);
                TopicDto topic = (TopicDto) commandGateway.send(getTopicByIdCommand);
                this.addTopic(topic);
            });
        }, new ArrayList<>(Arrays.asList(getOriginalTournamentStep)));

        SagaStep updateTournamentStep = new SagaStep("updateTournamentStep", () -> {
            UpdateTournamentCommand updateTournamentCommand = new UpdateTournamentCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentDto, this.getTopicsDtos());
            this.newTournamentDto = (TournamentDto) commandGateway.send(updateTournamentCommand);
        }, new ArrayList<>(Arrays.asList(getTopicsStep)));

        updateTournamentStep.registerCompensation(() -> {
            UpdateTournamentCommand updateTournamentCommand = new UpdateTournamentCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), originalTournamentDto, originalTournamentDto.getTopics());
            commandGateway.send(updateTournamentCommand);
        }, unitOfWork);

        SagaStep findQuestionsByTopicIds = new SagaStep("findQuestionsByTopicIds", () -> {
            FindQuestionsByTopicIdsCommand findQuestionsByTopicIdsCommand = new FindQuestionsByTopicIdsCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), new ArrayList<>(topicsAggregateIds));
            this.questionDtos = (List<QuestionDto>) commandGateway.send(findQuestionsByTopicIdsCommand);
        }, new ArrayList<>(Arrays.asList(updateTournamentStep)));

        SagaStep updateQuizStep = new SagaStep("updateQuizStep", () -> {
            QuizDto quizDto = new QuizDto();
            quizDto.setAggregateId(getNewTournamentDto().getQuiz().getAggregateId());
            quizDto.setAvailableDate(getNewTournamentDto().getStartTime());
            quizDto.setConclusionDate(getNewTournamentDto().getEndTime());
            quizDto.setResultsDate(getNewTournamentDto().getEndTime());
            setQuizDto(quizDto);

            if (topicsAggregateIds != null || getNewTournamentDto().getNumberOfQuestions() != null) {
                UpdateGeneratedQuizCommand updateGeneratedQuizCommand;
                updateGeneratedQuizCommand = new UpdateGeneratedQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), quizDto, Objects.requireNonNullElseGet(topicsAggregateIds, () -> getTopics().stream().map(TopicDto::getAggregateId).collect(Collectors.toSet())), this.newTournamentDto.getNumberOfQuestions(), this.questionDtos);
                commandGateway.send(updateGeneratedQuizCommand);
            }
        }, new ArrayList<>(Arrays.asList(updateTournamentStep, findQuestionsByTopicIds)));

        SagaStep updateSatelliteScalarsStep = new SagaStep("updateSatelliteScalarsStep", () -> {
            UpdateTournamentSatelliteScalarsCommand command = new UpdateTournamentSatelliteScalarsCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), this.getNewTournamentDto().getAggregateId(),
                    this.getNewTournamentDto().getStartTime(), null);
            commandGateway.send(command);
        }, new ArrayList<>(Arrays.asList(updateQuizStep)));

        SagaStep updateSatelliteSatelliteScalarsStep = new SagaStep("updateSatelliteSatelliteScalarsStep", () -> {
            UpdateTournamentSatelliteSatelliteScalarsCommand command = new UpdateTournamentSatelliteSatelliteScalarsCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(),
                    this.getNewTournamentDto().getAggregateId(), this.getNewTournamentDto().getStartTime(), null);
            commandGateway.send(command);
        }, new ArrayList<>(Arrays.asList(updateSatelliteScalarsStep)));

        workflow.addStep(getOriginalTournamentStep);
        workflow.addStep(getTopicsStep);
        workflow.addStep(findQuestionsByTopicIds);
        workflow.addStep(updateTournamentStep);
        workflow.addStep(updateQuizStep);
        workflow.addStep(updateSatelliteScalarsStep);
        workflow.addStep(updateSatelliteSatelliteScalarsStep);
    }

    public TournamentDto getOriginalTournamentDto() {
        return originalTournamentDto;
    }

    public void setOriginalTournamentDto(TournamentDto originalTournamentDto) {
        this.originalTournamentDto = originalTournamentDto;
    }

    public HashSet<TopicDto> getTopics() {
        return topics;
    }

    public void setTopics(HashSet<TopicDto> topics) {
        this.topics = topics;
    }

    public HashSet<TopicDto> getTopicsDtos() {
        HashSet<TopicDto> topicDtos = topics.stream().map(TopicDto -> (TopicDto) TopicDto).collect(Collectors.toCollection(HashSet::new));
        return topicDtos;
    }

    public void addTopic(TopicDto topic) {
        this.topics.add(topic);
    }

    public Tournament getTournament() {
        return tournament;
    }

    public void setTournament(Tournament tournament) {
        this.tournament = tournament;
    }

    public TournamentDto getNewTournamentDto() {
        return newTournamentDto;
    }

    public void setNewTournamentDto(TournamentDto newTournamentDto) {
        this.newTournamentDto = newTournamentDto;
    }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public void setQuizDto(QuizDto quizDto) {
        this.quizDto = quizDto;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }
}
