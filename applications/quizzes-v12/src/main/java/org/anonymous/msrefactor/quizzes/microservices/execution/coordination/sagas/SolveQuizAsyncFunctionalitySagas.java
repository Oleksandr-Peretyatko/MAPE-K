package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.quiz.RemoveQuizAnswerCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.StartQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.question.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.GetQuizByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.quiz.StartTournamentQuizCommand;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.sagas.states.QuizSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SolveQuizAsyncFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournament;
    private QuizDto quizDto;
    private QuizAnswerDto quizAnswerDto;
    private CompletableFuture<UserDto> userDtoFuture;
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
            // merge execution <- tournament: direct call; the checked READ_TOURNAMENT lock is
            // kept in-process, retargeted at the owning Execution root
            Integer executionAggregateId = executionService.findExecutionIdByTournamentId(tournamentAggregateId);
            unitOfWorkService.registerSagaState(executionAggregateId, CourseExecutionSagaState.READ_TOURNAMENT, unitOfWork);
            this.setTournament(executionService.getTournamentById(tournamentAggregateId, unitOfWork));
        });

        SagaStep startQuizStep = new SagaStep("startQuizStep", () -> {
            StartTournamentQuizCommand startTournamentQuizCommand = new StartTournamentQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), userAggregateId, this.getTournamentDto().getQuiz().getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(startTournamentQuizCommand);
            sagaCommand.setSemanticLock(QuizSagaState.STARTED_TOURNAMENT_QUIZ);
            QuizDto quiz = (QuizDto) commandGateway.send(sagaCommand);
            this.setQuizDto(quiz);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep getQuizByIdStep = new SagaStep("getQuizByIdStep", () -> {
            GetQuizByIdCommand getQuizByIdCommand = new GetQuizByIdCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), this.getQuizDto().getAggregateId());
            this.quizDto = (QuizDto) commandGateway.send(getQuizByIdCommand);
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

        SagaStep getStudentByExecutionIdAndUserIdAsyncStep = new SagaStep("getStudentByExecutionIdAndUserIdAsyncStep", () -> {
            this.userDtoFuture = CompletableFuture.completedFuture(
                    executionService.getStudentByExecutionIdAndUserId(this.quizDto.getCourseExecutionAggregateId(), userAggregateId, unitOfWork));
        }, new ArrayList<>(Arrays.asList(getQuizByIdStep)));

        SagaStep startQuizAnswerStep = new SagaStep("startQuizAnswerStep", () -> {
            this.quizDto.setQuestionDtos(this.questionDtosFuture.join());
            UserDto userDto = this.userDtoFuture.join();

            StartQuizCommand startTournamentQuizCommand = new StartQuizCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), this.getQuizDto().getAggregateId(), this.getTournamentDto().getCourseExecution().getAggregateId(), userDto);
            SagaCommand sagaCommand = new SagaCommand(startTournamentQuizCommand);
            sagaCommand.setSemanticLock(QuizSagaState.STARTED_QUIZ);
            QuizAnswerDto quizAnswerDto = (QuizAnswerDto) commandGateway.send(sagaCommand);
            this.setQuizAnswerDto(quizAnswerDto);
        }, new ArrayList<>(Arrays.asList(getQuestionsByIdAsyncStep, getStudentByExecutionIdAndUserIdAsyncStep)));

        startQuizAnswerStep.registerCompensation(() -> {
            if (this.getQuizAnswerDto() != null) {
                RemoveQuizAnswerCommand removeQuizAnswerCommand = new RemoveQuizAnswerCommand(unitOfWork, ServiceMapping.QUIZ.getServiceName(), this.getQuizAnswerDto().getAggregateId());
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
        workflow.addStep(getStudentByExecutionIdAndUserIdAsyncStep);
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