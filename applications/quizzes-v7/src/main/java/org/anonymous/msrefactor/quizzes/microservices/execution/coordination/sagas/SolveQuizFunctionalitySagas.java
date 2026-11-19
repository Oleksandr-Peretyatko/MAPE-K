package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetExecutionIdByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.answer.RemoveQuizAnswerCommand;
import org.anonymous.msrefactor.quizzes.commands.answer.StartQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetStudentByExecutionIdAndUserIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetQuizByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.StartTournamentQuizCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.SolveQuizCommand;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.states.QuizAnswerSagaState;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SolveQuizFunctionalitySagas extends WorkflowFunctionality {
    private TournamentDto tournament;
    private QuizDto quizDto;
    private UserDto userDto;
    private QuizAnswerDto quizAnswerDto;
    private Tournament oldTournament;
    private Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public SolveQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                       Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork,
                                       CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            GetExecutionIdByTournamentIdCommand lookupCommand = new GetExecutionIdByTournamentIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            this.executionAggregateId = (Integer) commandGateway.send(lookupCommand);
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            // lock retargeted to the owning execution root (the tournament is a member now)
            sagaCommand.setRootAggregateId(this.executionAggregateId);
            sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_TOURNAMENT);
            TournamentDto tournament = (TournamentDto) commandGateway.send(sagaCommand);
            this.setTournament(tournament);
        });

        SagaStep startQuizStep = new SagaStep("startQuizStep", () -> {
            StartTournamentQuizCommand startTournamentQuizCommand = new StartTournamentQuizCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), userAggregateId, this.getTournamentDto().getQuiz().getAggregateId());
            QuizDto quiz = (QuizDto) commandGateway.send(startTournamentQuizCommand);
            this.setQuizDto(quiz);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep getQuestionById = new SagaStep("getQuestionById", () -> { // TODO no loop for multiple sends inside a step
            List<QuestionDto> questionDtoList = new ArrayList<>();
            quizDto.getQuestionDtos().forEach(quizQuestion -> {
                GetQuestionByIdCommand getQuestionByIdCommand = new GetQuestionByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), quizQuestion.getAggregateId());
                QuestionDto questionDto = (QuestionDto) commandGateway.send(getQuestionByIdCommand);
                questionDto.getOptionDtos().forEach(o -> {
                    o.setCorrect(false); // by setting all to false frontend doesn't know which is correct
                });
                questionDtoList.add(questionDto);
            });
            quizDto.setQuestionDtos(questionDtoList);
        }, new ArrayList<>(Arrays.asList(startQuizStep)));

        SagaStep getQuizById = new SagaStep("getQuizById", () -> {
            GetQuizByIdCommand getQuizByIdCommand = new GetQuizByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), this.getQuizDto().getAggregateId());
            this.quizDto = (QuizDto) commandGateway.send(getQuizByIdCommand);
        }, new ArrayList<>(Arrays.asList(getQuestionById)));

        SagaStep getStudentByExecutionIdAndUserId = new SagaStep("getStudentByExecutionIdAndUserId", () -> {
            GetStudentByExecutionIdAndUserIdCommand getStudentByExecutionIdAndUserIdCommand = new GetStudentByExecutionIdAndUserIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), this.quizDto.getCourseExecutionAggregateId(), userAggregateId);
            this.userDto = (UserDto) commandGateway.send(getStudentByExecutionIdAndUserIdCommand);
        }, new ArrayList<>(Arrays.asList(getQuizById)));

        SagaStep startQuizAnswerStep = new SagaStep("startQuizAnswerStep", () -> {
            StartQuizCommand startTournamentQuizCommand = new StartQuizCommand(unitOfWork, ServiceMapping.ANSWER.getServiceName(), this.getQuizDto().getAggregateId(), this.getTournamentDto().getCourseExecution().getAggregateId(), this.quizDto, this.userDto);
            // STARTED_QUIZ used to be registered on the quiz id; after the execution<-quiz merge the quiz
            // is a member entity, not an aggregate, so that lock can no longer be taken on its id and it
            // was unchecked anyway (no QuizAnswer query reads a quiz's saga state) — dropped (layer 3.9).
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
            SolveQuizCommand solveQuizCommand = new SolveQuizCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId, userAggregateId, this.getQuizAnswerDto().getAggregateId());
            commandGateway.send(solveQuizCommand);
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

    public Tournament getOldTournament() {
        return oldTournament;
    }

    public void setOldTournament(Tournament oldTournament) {
        this.oldTournament = oldTournament;
    }
}
