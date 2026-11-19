package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.RemoveUserCommand;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveUserTournamentSatelliteFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;
    private TournamentSatelliteDto satelliteDto;

    public RemoveUserTournamentSatelliteFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(satelliteAggregateId, executionAggregateId, userAggregateId, eventVersion, unitOfWork);
    }

    public void buildWorkflow(Integer satelliteAggregateId, Integer executionAggregateId, Integer userAggregateId,
            Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep removeUserSatelliteStep = new SagaStep("removeUserSatelliteStep", () -> {
            RemoveUserCommand command = new RemoveUserCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), satelliteAggregateId, executionAggregateId,
                    userAggregateId, eventVersion);
            this.satelliteDto = (TournamentSatelliteDto) commandGateway.send(command);
        });

        SagaStep removeUserCoreStep = new SagaStep("removeUserCoreStep", () -> {
            if (this.satelliteDto != null && this.satelliteDto.getTournamentAggregateId() != null) {
                // merge execution <- tournament: the tournament member lives in the execution service
                org.anonymous.msrefactor.quizzes.commands.execution.RemoveTournamentUserCommand coreCommand =
                        new org.anonymous.msrefactor.quizzes.commands.execution.RemoveTournamentUserCommand(unitOfWork,
                                ServiceMapping.EXECUTION.getServiceName(),
                                this.satelliteDto.getTournamentAggregateId(), executionAggregateId, userAggregateId,
                                eventVersion);
                commandGateway.send(coreCommand);
            }
        }, new ArrayList<>(Arrays.asList(removeUserSatelliteStep)));

        this.workflow.addStep(removeUserSatelliteStep);
        this.workflow.addStep(removeUserCoreStep);
    }
}
