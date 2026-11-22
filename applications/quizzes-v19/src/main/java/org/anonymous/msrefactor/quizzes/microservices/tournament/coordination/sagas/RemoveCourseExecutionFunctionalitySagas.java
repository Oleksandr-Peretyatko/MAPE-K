package org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournament.RemoveCourseExecutionCommand;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.DeactivateTournamentSatelliteCommand;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer tournamentAggregateId, Integer courseExecutionAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(tournamentAggregateId, courseExecutionAggregateId, eventVersion, unitOfWork);
    }

    public void buildWorkflow(Integer tournamentAggregateId, Integer courseExecutionAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep removeCourseExecutionStep = new SagaStep("removeCourseExecutionStep", () -> {
            RemoveCourseExecutionCommand command = new RemoveCourseExecutionCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT.getServiceName(), tournamentAggregateId, courseExecutionAggregateId,
                    eventVersion);
            commandGateway.send(command);
        });

        // the whole tournament went INACTIVE before the split; the satellite's half
        // of that state change is now a second step
        SagaStep deactivateSatelliteStep = new SagaStep("deactivateSatelliteStep", () -> {
            DeactivateTournamentSatelliteCommand deactivateSatelliteCommand = new DeactivateTournamentSatelliteCommand(unitOfWork, ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(), tournamentAggregateId);
            commandGateway.send(deactivateSatelliteCommand);
        }, new ArrayList<>(Arrays.asList(removeCourseExecutionStep)));

        workflow.addStep(removeCourseExecutionStep);
        workflow.addStep(deactivateSatelliteStep);
    }
}
