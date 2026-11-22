package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.answer.RemoveQuizAnswerCommand;
import org.anonymous.msrefactor.quizzes.commands.answer.StartQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.question.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SolveQuizFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournament;
    private QuizDto quizDto;
    private UserDto userDto;
    private QuizAnswerDto quizAnswerDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private final CommandGateway commandGateway;

    public SolveQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                       Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                       CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            // the semantic lock the remote read took on the tournament is kept,
            // retargeted at the owning execution root
            Integer executionAggregateId = executionService.getExecutionIdOfTournament(tournamentAggregateId);
            unitOfWorkService.registerSagaState(executionAggregateId, CourseExecutionSagaState.READ_TOURNAMENT, unitOfWork);
            this.setTournament(executionService.getTournamentById(tournamentAggregateId, unitOfWork));
        });

        SagaStep startQuizStep = new SagaStep("startQuizStep", () -> {
            this.setQuizDto(executionService.startTournamentQuiz(userAggregateId, this.getTournamentDto().getQuiz().getAggregateId(), unitOfWork));
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep getQuestionById = new SagaStep("getQuestionById", () -> { // TODO no loop for multiple sends inside a step
            List<QuestionDto> questionDtoList = new ArrayList<>();
            quizDto.getQuestionDtos().forEach(quizQuestion -> {
                GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), quizQuestion.getAggregateId());
                QuestionDto questionDto = (QuestionDto) commandGateway.send(getQuestionByIdCommand);
                questionDto.getOptionDtos().forEach(o -> {
                    o.setCorrect(false); // by setting all to false frontend doesn't know which is correct
                });
                questionDtoList.add(questionDto);
            });
            quizDto.setQuestionDtos(questionDtoList);
        }, new ArrayList<>(Arrays.asList(startQuizStep)));

        SagaStep getQuizById = new SagaStep("getQuizById", () -> {
            this.quizDto = executionService.getQuizById(this.getQuizDto().getAggregateId(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(getQuestionById)));

        SagaStep getStudentByExecutionIdAndUserId = new SagaStep("getStudentByExecutionIdAndUserId", () -> {
            this.userDto = executionService.getStudentByExecutionIdAndUserId(this.quizDto.getCourseExecutionAggregateId(), userAggregateId, unitOfWork);
        }, new ArrayList<>(Arrays.asList(getQuizById)));

        SagaStep startQuizAnswerStep = new SagaStep("startQuizAnswerStep", () -> {
            StartQuizCommand startTournamentQuizCommand = new StartQuizCommand(unitOfWork, ServiceMapping.ANSWER.getServiceName(), this.getQuizDto().getAggregateId(), this.getTournamentDto().getCourseExecution().getAggregateId(), this.quizDto, this.userDto);
            QuizAnswerDto quizAnswerDto = (QuizAnswerDto) commandGateway.send(startTournamentQuizCommand);
            this.setQuizAnswerDto(quizAnswerDto);
        }, new ArrayList<>(Arrays.asList(getQuestionById, getStudentByExecutionIdAndUserId)));

        startQuizAnswerStep.registerCompensation(() -> {
            if (this.getQuizAnswerDto() != null) {
                RemoveQuizAnswerCommand removeQuizAnswerCommand = new RemoveQuizAnswerCommand(unitOfWork, ServiceMapping.ANSWER.getServiceName(), this.getQuizAnswerDto().getAggregateId());
                commandGateway.send(removeQuizAnswerCommand);
            }
        }, unitOfWork);

        SagaStep solveQuizStep = new SagaStep("solveQuizStep", () -> {
            executionService.solveQuiz(tournamentAggregateId, userAggregateId, this.getQuizAnswerDto().getAggregateId(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(startQuizAnswerStep)));

        workflow.addStep(getTournamentStep);
        workflow.addStep(startQuizStep);
        workflow.addStep(getQuestionById);
        workflow.addStep(getQuizById);
        workflow.addStep(getStudentByExecutionIdAndUserId);
        workflow.addStep(startQuizAnswerStep);
        workflow.addStep(solveQuizStep);
    }

    public TournamentDto getTournamentDto() {
        return tournament;
    }

    public void setTournament(TournamentDto tournament) {
        this.tournament = tournament;
    }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public void setQuizDto(QuizDto quizDto) {
        this.quizDto = quizDto;
    }

    public QuizAnswerDto getQuizAnswerDto() {
        return quizAnswerDto;
    }

    public void setQuizAnswerDto(QuizAnswerDto quizAnswerDto) {
        this.quizAnswerDto = quizAnswerDto;
    }
}
