package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.SagaAggregate;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetStudentByExecutionIdAndUserIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.AddParticipantCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AddParticipantFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private UserDto userDto;
    private TournamentDto tournamentDto;
    private final CommandGateway commandGateway;

    public AddParticipantFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId,
                                            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, executionAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getUserStep = new SagaStep("getUserStep", () -> {
            GetStudentByExecutionIdAndUserIdCommand getStudentCommand = new GetStudentByExecutionIdAndUserIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), executionAggregateId, userAggregateId);
            this.userDto = (UserDto) commandGateway.send(getStudentCommand);
        });

        // the date/cancelled check stays on the core: the fresh scalars are read here
        // (guarded by the same forbidden state the add used to carry) and passed to
        // the satellite so its enrollment invariants run against current values
        SagaStep getTournamentStep = new SagaStep("getTournamentStep", () -> {
            List<SagaAggregate.SagaState> states = new ArrayList<>();
            states.add(TournamentSagaState.IN_UPDATE_TOURNAMENT);
            GetTournamentByIdCommand getTournamentByIdCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTournamentByIdCommand);
            sagaCommand.setForbiddenStates(states);
            this.tournamentDto = (TournamentDto) commandGateway.send(sagaCommand);
        }, new ArrayList<>(Arrays.asList(getUserStep)));

        SagaStep addParticipantStep = new SagaStep("addParticipantStep", () -> {
            AddParticipantCommand addParticipantCommand = new AddParticipantCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId, this.userDto, this.tournamentDto);
            commandGateway.send(addParticipantCommand);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        this.workflow.addStep(getUserStep);
        this.workflow.addStep(getTournamentStep);
        this.workflow.addStep(addParticipantStep);
    }

    public void setUserDto(UserDto userDto) {
        this.userDto = userDto;
    }

    public UserDto getUserDto() {
        return userDto;
    }
}
