package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.UpdateParticipantAnswerCommand;

public class UpdateParticipantAnswerFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateParticipantAnswerFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer satelliteAggregateId,
            Integer userAggregateId, Integer quizAnswerAggregateId, Integer questionAggregateId,
            boolean correct, Long eventVersion, SagaUnitOfWork unitOfWork,
            CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(satelliteAggregateId, userAggregateId, quizAnswerAggregateId, questionAggregateId, correct,
                eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer satelliteAggregateId, Integer userAggregateId, Integer quizAnswerAggregateId,
            Integer questionAggregateId, boolean correct, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateParticipantAnswerStep", () -> {
            UpdateParticipantAnswerCommand command = new UpdateParticipantAnswerCommand(unitOfWork,
                    ServiceMapping.TOURNAMENT_SATELLITE.getServiceName(),
                    satelliteAggregateId, userAggregateId, quizAnswerAggregateId, questionAggregateId, correct,
                    eventVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
