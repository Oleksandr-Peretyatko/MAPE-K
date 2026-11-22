package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.UpdateUserNameCommand;

public class UpdateUserNameTournamentSatelliteSatelliteFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateUserNameTournamentSatelliteSatelliteFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Long eventVersion, Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            SagaUnitOfWork unitOfWork, String name, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, name, eventVersion, unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            String name, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep updateUserNameSatelliteStep = new SagaStep("updateUserNameSatelliteSatelliteStep", () -> {
            UpdateUserNameCommand command = new UpdateUserNameCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(), satelliteAggregateId,
                    executionAggregateId, eventVersion, userAggregateId, name);
            commandGateway.send(command);
        });

        this.workflow.addStep(updateUserNameSatelliteStep);
    }
}
