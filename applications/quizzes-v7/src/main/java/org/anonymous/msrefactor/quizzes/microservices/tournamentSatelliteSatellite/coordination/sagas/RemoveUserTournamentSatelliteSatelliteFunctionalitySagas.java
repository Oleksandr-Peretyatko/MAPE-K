package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.RemoveUserCommand;

public class RemoveUserTournamentSatelliteSatelliteFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveUserTournamentSatelliteSatelliteFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, eventVersion, unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep removeUserSatelliteStep = new SagaStep("removeUserSatelliteSatelliteStep", () -> {
            RemoveUserCommand command = new RemoveUserCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), satelliteAggregateId,
                    executionAggregateId, userAggregateId, eventVersion);
            commandGateway.send(command);
        });

        this.workflow.addStep(removeUserSatelliteStep);
    }
}
