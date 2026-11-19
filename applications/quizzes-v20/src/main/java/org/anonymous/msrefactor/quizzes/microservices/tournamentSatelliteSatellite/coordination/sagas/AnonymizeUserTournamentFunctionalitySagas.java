package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.tournament.GetTournamentByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.AnonymizeUserCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.GetTournamentSatelliteSatelliteByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.states.TournamentSagaState;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
 * The participant branch of the AnonymizeStudent processing — the creator branch
 * stays on the core TournamentSatellite, whose own workflow also deactivates the
 * tournament and this roster when the creator is the anonymized student.
 */
public class AnonymizeUserTournamentFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentSatelliteSatelliteDto satelliteDto;

    public AnonymizeUserTournamentFunctionalitySagas(UnitOfWorkService unitOfWorkService, Integer satelliteAggregateId,
            Integer executionAggregateId, Integer userAggregateId, String name, String username, Long eventVersion,
            UnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = (SagaUnitOfWorkService) unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, name, username, eventVersion,
                (SagaUnitOfWork) unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            String name, String username, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        // the guard against a concurrent tournament update is still anchored on the
        // core Tournament aggregate, reached through the roster's back reference
        SagaStep checkTournamentStep = new SagaStep("checkTournamentStep", () -> {
            GetTournamentSatelliteSatelliteByIdCommand getSatelliteCommand = new GetTournamentSatelliteSatelliteByIdCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), satelliteAggregateId);
            TournamentSatelliteSatelliteDto satellite = (TournamentSatelliteSatelliteDto) commandGateway
                    .send(getSatelliteCommand);
            GetTournamentByIdCommand getTournamentCommand = new GetTournamentByIdCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT.getServiceName(), satellite.getTournamentAggregateId());
            SagaCommand sagaCommand = new SagaCommand(getTournamentCommand);
            sagaCommand.setForbiddenStates(new ArrayList<>(List.of(TournamentSagaState.IN_UPDATE_TOURNAMENT)));
            commandGateway.send(sagaCommand);
        });

        SagaStep anonymizeUserStep = new SagaStep("anonymizeUserStep", () -> {
            AnonymizeUserCommand anonymizeUserCommand = new AnonymizeUserCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), satelliteAggregateId,
                    executionAggregateId, userAggregateId, name, username, eventVersion);
            this.satelliteDto = (TournamentSatelliteSatelliteDto) commandGateway.send(anonymizeUserCommand);
        }, new ArrayList<>(Arrays.asList(checkTournamentStep)));

        this.workflow.addStep(checkTournamentStep);
        this.workflow.addStep(anonymizeUserStep);
    }

}
