package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.UpdateTopicCommand;

public class UpdateTopicFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateTopicFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, Integer executionAggregateId,
            Integer topicAggregateId, String topicName, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(executionAggregateId, topicAggregateId, topicName, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer executionAggregateId, Integer topicAggregateId, String topicName,
            Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateTopicStep", () -> {
            UpdateTopicCommand command = new UpdateTopicCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(),
                    executionAggregateId, topicAggregateId, topicName, eventVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
