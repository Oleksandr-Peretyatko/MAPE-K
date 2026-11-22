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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SolveQuizAsyncFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournament;
    private QuizDto quizDto;
    private UserDto userDto;
    private QuizAnswerDto quizAnswerDto;
    private CompletableFuture<List<QuestionDto>> questionDtosFuture;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;
    private final CommandGateway commandGateway;

    public SolveQuizAsyncFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                            Integer tournamentAggregateId, Integer userAggregateId,
                                            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
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

        SagaStep getQuizByIdStep = new SagaStep("getQuizByIdStep", () -> {
            this.quizDto = executionService.getQuizById(this.getQuizDto().getAggregateId(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(startQuizStep)));

        SagaStep getQuestionsByIdAsyncStep = new SagaStep("getQuestionsByIdAsyncStep", () -> {
            List<CompletableFuture<QuestionDto>> questionFutures = this.quizDto.getQuestionDtos().stream()
                .map(quizQuestion -> {
                    GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), quizQuestion.getAggregateId());
                    return commandGateway.sendAsync(getQuestionByIdCommand).thenApply(dto -> {
                        QuestionDto questionDto = (QuestionDto) dto;
                        questionDto.getOptionDtos().forEach(o -> o.setCorrect(false));
                        return questionDto;
                    });
                })
                .collect(Collectors.toList());

            this.questionDtosFuture = CompletableFuture.allOf(questionFutures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> questionFutures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
        }, new ArrayList<>(Arrays.asList(getQuizByIdStep)));

        SagaStep getStudentByExecutionIdAndUserIdStep = new SagaStep("getStudentByExecutionIdAndUserIdStep", () -> {
            // the student read is in-process now; the async hop to execution-service is gone
            this.userDto = executionService.getStudentByExecutionIdAndUserId(this.quizDto.getCourseExecutionAggregateId(), userAggregateId, unitOfWork);
        }, new ArrayList<>(Arrays.asList(getQuizByIdStep)));

        SagaStep startQuizAnswerStep = new SagaStep("startQuizAnswerStep", () -> {
            this.quizDto.setQuestionDtos(this.questionDtosFuture.join());

            StartQuizCommand startTournamentQuizCommand = new StartQuizCommand(unitOfWork, ServiceMapping.ANSWER.getServiceName(), this.getQuizDto().getAggregateId(), this.getTournamentDto().getCourseExecution().getAggregateId(), this.quizDto, this.userDto);
            QuizAnswerDto quizAnswerDto = (QuizAnswerDto) commandGateway.send(startTournamentQuizCommand);
            this.setQuizAnswerDto(quizAnswerDto);
        }, new ArrayList<>(Arrays.asList(getQuestionsByIdAsyncStep, getStudentByExecutionIdAndUserIdStep)));

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
        workflow.addStep(getQuizByIdStep);
        workflow.addStep(getQuestionsByIdAsyncStep);
        workflow.addStep(getStudentByExecutionIdAndUserIdStep);
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
