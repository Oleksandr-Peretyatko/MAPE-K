package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.DeactivateTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.AnonymizeUserCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.GetTournamentSatelliteByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AnonymizeUserTournamentFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentSatelliteDto satelliteDto;

    public AnonymizeUserTournamentFunctionalitySagas(UnitOfWorkService unitOfWorkService, Integer satelliteAggregateId, Integer executionAggregateId,
                                                     Integer userAggregateId, String name, String username, Long eventVersion, UnitOfWork unitOfWork,
                                                     CommandGateway commandGateway) {
        this.unitOfWorkService = (SagaUnitOfWorkService) unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, name, username, eventVersion,
                (SagaUnitOfWork) unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            String name, String username, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        // before the split the anonymize command carried the IN_UPDATE_TOURNAMENT
        // forbidden state on the tournament itself; the lock still lives on the core
        // aggregate, so the guard has to be re-anchored there via the satellite's back
        // reference
        SagaStep checkTournamentStep = new SagaStep("checkTournamentStep", () -> {
            GetTournamentSatelliteByIdCommand getSatelliteCommand = new GetTournamentSatelliteByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId);
            TournamentSatelliteDto satellite = (TournamentSatelliteDto) commandGateway.send(getSatelliteCommand);
            GetTournamentByIdCommand getTournamentCommand = new GetTournamentByIdCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), satellite.getTournamentAggregateId());
            SagaCommand sagaCommand = new SagaCommand(getTournamentCommand);
            sagaCommand.setForbiddenStates(new ArrayList<>(List.of(TournamentSagaState.IN_UPDATE_TOURNAMENT)));
            commandGateway.send(sagaCommand);
        });

        SagaStep anonymizeUserStep = new SagaStep("anonymizeUserStep", () -> {
            AnonymizeUserCommand anonymizeUserCommand = new AnonymizeUserCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId, executionAggregateId, userAggregateId, name, username, eventVersion);
            this.satelliteDto = (TournamentSatelliteDto) commandGateway.send(anonymizeUserCommand);
        }, new ArrayList<>(Arrays.asList(checkTournamentStep)));

        // anonymizing the creator deactivated the whole tournament before the split;
        // after it the core's half of that state change is a second step
        SagaStep deactivateTournamentStep = new SagaStep("deactivateTournamentStep", () -> {
            if (this.satelliteDto != null && "INACTIVE".equals(this.satelliteDto.getState())) {
                DeactivateTournamentCommand deactivateTournamentCommand = new DeactivateTournamentCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), this.satelliteDto.getTournamentAggregateId());
                commandGateway.send(deactivateTournamentCommand);
            }
        }, new ArrayList<>(Arrays.asList(anonymizeUserStep)));

        this.workflow.addStep(checkTournamentStep);
        this.workflow.addStep(anonymizeUserStep);
        this.workflow.addStep(deactivateTournamentStep);
    }

}
