package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.GetExecutionIdByTournamentIdCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.CancelTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.execution.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.UpdateTournamentSatelliteScalarsCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.UpdateTournamentSatelliteSatelliteScalarsCommand;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.states.CourseExecutionSagaState;

import java.util.ArrayList;
import java.util.Arrays;

public class CancelTournamentFunctionalitySagas extends WorkflowFunctionality {

    private TournamentDto tournament;
    private Integer executionAggregateId;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public CancelTournamentFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                              Integer tournamentAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, SagaUnitOfWork unitOfWork) {
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

        getTournamentStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), this.executionAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep cancelTournamentStep = new SagaStep("cancelTournamentStep", () -> {
            CancelTournamentCommand cancelTournamentCommand = new CancelTournamentCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), tournamentAggregateId);
            commandGateway.send(cancelTournamentCommand);
        }, new ArrayList<>(Arrays.asList(getTournamentStep)));

        SagaStep cancelSatelliteStep = new SagaStep("cancelSatelliteStep", () -> {
            UpdateTournamentSatelliteScalarsCommand command = new UpdateTournamentSatelliteScalarsCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId, null, true);
            commandGateway.send(command);
        }, new ArrayList<>(Arrays.asList(cancelTournamentStep)));

        SagaStep cancelSatelliteSatelliteStep = new SagaStep("cancelSatelliteSatelliteStep", () -> {
            UpdateTournamentSatelliteSatelliteScalarsCommand command = new UpdateTournamentSatelliteSatelliteScalarsCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), tournamentAggregateId,
                    null, true);
            commandGateway.send(command);
        }, new ArrayList<>(Arrays.asList(cancelSatelliteStep)));

        workflow.addStep(getTournamentStep);
        workflow.addStep(cancelTournamentStep);
        workflow.addStep(cancelSatelliteStep);
        workflow.addStep(cancelSatelliteSatelliteStep);
    }

    public TournamentDto getTournament() {
        return tournament;
    }

    public void setTournament(TournamentDto Tournament) {
        this.tournament = Tournament;
    }
}
