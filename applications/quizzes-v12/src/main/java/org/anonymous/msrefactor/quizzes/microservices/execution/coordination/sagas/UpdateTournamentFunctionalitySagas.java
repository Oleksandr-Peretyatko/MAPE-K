package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.question.FindQuestionsByTopicIdsCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.UpdateGeneratedQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.course.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;

import java.util.*;
import java.util.stream.Collectors;

/*
 * Merge execution <- tournament: the tournament read and update are direct ExecutionService
 * calls; topic and quiz steps stay remote. Tournament's lock states are checked, so the
 * IN_UPDATE_TOURNAMENT lock and its forbidden states are kept in-process, retargeted at the
 * owning Execution root. The update compensation stays: a later remote quiz step can still
 * fail after the local update was persisted.
 */
public class UpdateTournamentFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private final CommandGateway commandGateway;
    private TournamentDto originalTournamentDto;
    private HashSet<TopicDto> topics = new HashSet<TopicDto>();
    private TournamentDto newTournamentDto;
    private QuizDto quizDto;
    private List<QuestionDto> questionDtos;

    public UpdateTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                              TournamentDto tournamentDto, Set<Integer> topicsAggregateIds,
                                              SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentDto, topicsAggregateIds, unitOfWork);
    }

    public void buildWorkflow(TournamentDto tournamentDto, Set<Integer> topicsAggregateIds, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getOriginalTournamentStep = new SagaStep("getOriginalTournamentStep", () -> {
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(CourseExecutionSagaState.IN_UPDATE_TOURNAMENT);
            states.add(CourseExecutionSagaState.IN_DELETE_TOURNAMENT);
            Integer executionAggregateId = executionService.findExecutionIdByTournamentId(tournamentDto.getAggregateId());
            unitOfWorkService.verifySagaState(executionAggregateId, states);
            unitOfWorkService.registerSagaState(executionAggregateId, CourseExecutionSagaState.IN_UPDATE_TOURNAMENT, unitOfWork);
            TournamentDto originalTournamentDto = executionService.getTournamentById(tournamentDto.getAggregateId(), unitOfWork);
            setOriginalTournamentDto(originalTournamentDto);
        });

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> {
            topicsAggregateIds.forEach(topicId -> {
                GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), topicId);
                TopicDto topic = (TopicDto) commandGateway.send(getTopicByIdCommand);
                this.addTopic(topic);
            });
        }, new ArrayList<>(Arrays.asList(getOriginalTournamentStep)));

        SagaStep updateTournamentStep = new SagaStep("updateTournamentStep", () -> {
            this.newTournamentDto = executionService.updateTournament(tournamentDto, this.getTopicsDtos(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(getTopicsStep)));

        updateTournamentStep.registerCompensation(() -> {
            executionService.updateTournament(originalTournamentDto, originalTournamentDto.getTopics(), unitOfWork);
        }, unitOfWork);

        SagaStep findQuestionsByTopicIds = new SagaStep("findQuestionsByTopicIds", () -> {
            FindQuestionsByTopicIdsCommand findQuestionsByTopicIdsCommand = new FindQuestionsByTopicIdsCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), new ArrayList<>(topicsAggregateIds));
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
                updateGeneratedQuizCommand = new UpdateGeneratedQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), quizDto, Objects.requireNonNullElseGet(topicsAggregateIds, () -> getTopics().stream().map(TopicDto::getAggregateId).collect(Collectors.toSet())), this.newTournamentDto.getNumberOfQuestions(), this.questionDtos);
                commandGateway.send(updateGeneratedQuizCommand);
            }
        }, new ArrayList<>(Arrays.asList(updateTournamentStep, findQuestionsByTopicIds)));

        workflow.addStep(getOriginalTournamentStep);
        workflow.addStep(getTopicsStep);
        workflow.addStep(findQuestionsByTopicIds);
        workflow.addStep(updateTournamentStep);
        workflow.addStep(updateQuizStep);
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
}
