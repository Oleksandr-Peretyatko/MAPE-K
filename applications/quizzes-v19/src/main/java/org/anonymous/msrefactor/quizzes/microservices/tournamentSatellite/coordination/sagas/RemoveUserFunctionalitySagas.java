package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.DeactivateTournamentCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.RemoveUserCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveUserFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentSatelliteDto satelliteDto;

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
            RemoveUserCommand command = new RemoveUserCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(),
                    satelliteAggregateId, executionAggregateId, userAggregateId, eventVersion);
            this.satelliteDto = (TournamentSatelliteDto) commandGateway.send(command);
        });

        // disenrolling the creator deactivated the whole tournament before the split
        SagaStep deactivateTournamentStep = new SagaStep("deactivateTournamentStep", () -> {
            if (this.satelliteDto != null && "INACTIVE".equals(this.satelliteDto.getState())) {
                DeactivateTournamentCommand deactivateTournamentCommand = new DeactivateTournamentCommand(unitOfWork, ServiceMapping.TOURNAMENT.getServiceName(), this.satelliteDto.getTournamentAggregateId());
                commandGateway.send(deactivateTournamentCommand);
            }
        }, new ArrayList<>(Arrays.asList(step)));

        workflow.addStep(step);
        workflow.addStep(deactivateTournamentStep);
    }
}
