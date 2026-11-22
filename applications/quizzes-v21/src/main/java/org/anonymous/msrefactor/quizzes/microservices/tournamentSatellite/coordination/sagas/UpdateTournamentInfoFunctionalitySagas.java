package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.UpdateTournamentSatelliteInfoCommand;

/*
 * Event-driven refresh of the replicated core scalars (TOURNAMENT_INFO) — the
 * processing side of the UpdateTournamentEvent quad. The event's publisher id is
 * the core tournament's aggregate id, which is how the satellite resolves itself.
 */
public class UpdateTournamentInfoFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateTournamentInfoFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer tournamentAggregateId, String startTime, boolean cancelled, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(tournamentAggregateId, startTime, cancelled, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer tournamentAggregateId, String startTime, boolean cancelled, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateTournamentInfoStep", () -> {
            UpdateTournamentSatelliteInfoCommand command = new UpdateTournamentSatelliteInfoCommand(
                    unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId, startTime,
                    cancelled, eventVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
