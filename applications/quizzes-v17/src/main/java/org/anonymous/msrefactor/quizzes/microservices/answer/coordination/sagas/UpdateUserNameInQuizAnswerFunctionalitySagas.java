package org.anonymous.msrefactor.quizzes.microservices.answer.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.answer.UpdateUserNameCommand;

public class UpdateUserNameInQuizAnswerFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateUserNameInQuizAnswerFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer quizAnswerAggregateId, Integer publisherAggregateId, Long publisherAggregateVersion,
            Integer studentAggregateId, String updatedName,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(quizAnswerAggregateId, publisherAggregateId, publisherAggregateVersion, studentAggregateId,
                updatedName, unitOfWork);
    }

    private void buildWorkflow(Integer quizAnswerAggregateId, Integer publisherAggregateId,
            Long publisherAggregateVersion,
            Integer studentAggregateId, String updatedName, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateUserNameInQuizAnswer", () -> {
            UpdateUserNameCommand command = new UpdateUserNameCommand(unitOfWork,
                    ServiceMapping.ANSWER.getServiceName(), quizAnswerAggregateId, publisherAggregateId,
                    publisherAggregateVersion, studentAggregateId, updatedName);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
