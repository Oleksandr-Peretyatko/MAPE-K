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
import java.util.stream.Collectors;

/*
 * Merge execution <- tournament: the execution reads, and the tournament creation itself, are
 * direct ExecutionService calls; topic, question and quiz steps stay remote. Execution's lock
 * states are checked, so the READ_COURSE lock the remote getCourseExecutionStep used to take
 * is kept in-process on the owning root.
 */
public class CreateTournamentFunctionalitySagas extends WorkflowFunctionality {
    private CourseExecutionDto courseExecutionDto;
    private UserDto userDto;
    private HashSet<TopicDto> topicDtos = new HashSet<>();
    private QuizDto quizDto;
    private TournamentDto tournamentDto;
    private List<QuestionDto> questionDtos;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private final CommandGateway commandGateway;

    public CreateTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                              Integer userId, Integer executionId, List<Integer> topicsId, TournamentDto tournamentDto,
                                              SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(userId, executionId, topicsId, tournamentDto, unitOfWork);
    }

    public void buildWorkflow(Integer userId, Integer executionId, List<Integer> topicsId,
            TournamentDto tournamentDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseExecutionStep = new SagaStep("getCourseExecutionStep", () -> {
            // by making this call locks regarding the course execution are guaranteed
            unitOfWorkService.registerSagaState(executionId, CourseExecutionSagaState.READ_COURSE, unitOfWork);
            this.setCourseExecutionDto(executionService.getCourseExecutionById(executionId, unitOfWork));
        });

        SagaStep getCreatorStep = new SagaStep("getCreatorStep", () -> {
            // by making this call the invariants regarding the course execution and the
            // role of the creator are guaranteed
            this.setUserDto(executionService.getStudentByExecutionIdAndUserId(executionId, userId, unitOfWork));
        }, new ArrayList<>(Arrays.asList(getCourseExecutionStep)));

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> { // TODO EACH TOPIC IN A SEPARATE STEP??
            topicsId.forEach(topicId -> {
                GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), topicId);
                TopicDto topic = (TopicDto) commandGateway.send(getTopicByIdCommand);
                this.addTopicDto(topic);
            });
        });

        SagaStep findQuestionsByTopicIdsStep = new SagaStep("findQuestionsByTopicIdsStep", () -> {
            FindQuestionsByTopicIdsCommand findQuestionsByTopicIdsCommand = new FindQuestionsByTopicIdsCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), topicsId);
            this.questionDtos = (List<QuestionDto>) commandGateway.send(findQuestionsByTopicIdsCommand);
        }, new ArrayList<>(Arrays.asList(getTopicsStep)));

        SagaStep getCourseExecutionById = new SagaStep("getCourseExecutionById", () -> {
            this.courseExecutionDto = executionService.getCourseExecutionById(executionId, unitOfWork);
        }, new ArrayList<>(Arrays.asList(findQuestionsByTopicIdsStep)));

        SagaStep generateQuizStep = new SagaStep("generateQuizStep", () -> {
            QuizDto quizDto = new QuizDto();
            quizDto.setAvailableDate(tournamentDto.getStartTime());
            quizDto.setConclusionDate(tournamentDto.getEndTime());
            quizDto.setResultsDate(tournamentDto.getEndTime());
            GenerateQuizCommand generateQuizCommand = new GenerateQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), courseExecutionDto, quizDto, questionDtos, tournamentDto.getNumberOfQuestions());
            QuizDto quizResultDto = (QuizDto) commandGateway.send(generateQuizCommand);
            this.setQuizDto(quizResultDto);
        }, new ArrayList<>(Arrays.asList(findQuestionsByTopicIdsStep, getCourseExecutionById)));

        generateQuizStep.registerCompensation(() -> {
            if (this.getQuizDto() != null) {
                RemoveQuizCommand removeQuizCommand = new RemoveQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), this.getQuizDto().getAggregateId());
                commandGateway.send(removeQuizCommand);
            }
        }, unitOfWork);

        SagaStep createTournamentStep = new SagaStep("createTournamentStep", () -> {
            TournamentDto tournamentResultDto = executionService.createTournament(tournamentDto, this.getUserDto(), this.getCourseExecutionDto(), this.getTopicsDtos(), this.getQuizDto(), unitOfWork);
            this.setTournamentDto(tournamentResultDto);
        }, new ArrayList<>(Arrays.asList(getCreatorStep, getCourseExecutionStep, getTopicsStep, generateQuizStep)));

        this.workflow.addStep(getCreatorStep);
        this.workflow.addStep(getCourseExecutionStep);
        this.workflow.addStep(getTopicsStep);
        this.workflow.addStep(findQuestionsByTopicIdsStep);
        this.workflow.addStep(getCourseExecutionById);
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
