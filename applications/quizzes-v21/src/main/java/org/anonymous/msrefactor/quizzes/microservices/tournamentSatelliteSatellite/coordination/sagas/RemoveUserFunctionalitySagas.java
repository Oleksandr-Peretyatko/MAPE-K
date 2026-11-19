package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatelliteSatellite.RemoveUserCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;

/*
 * The participant branch of the DisenrollStudent processing — the creator branch
 * stays on the core TournamentSatellite, whose own workflow deactivates the
 * tournament and this roster when the creator is the disenrolled student.
 */
public class RemoveUserFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentSatelliteSatelliteDto satelliteDto;

    public RemoveUserFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, Integer satelliteAggregateId,
            Integer executionAggregateId, Integer userAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("removeUserStep", () -> {
            RemoveUserCommand command = new RemoveUserCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE_SATELLITE.getServiceName(),
                    satelliteAggregateId, executionAggregateId, userAggregateId, eventVersion);
            this.satelliteDto = (TournamentSatelliteSatelliteDto) commandGateway.send(command);
        });

        workflow.addStep(step);
    }
}
