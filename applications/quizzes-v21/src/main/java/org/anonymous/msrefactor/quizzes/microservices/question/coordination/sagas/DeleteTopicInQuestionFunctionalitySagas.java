package org.anonymous.msrefactor.quizzes.microservices.question.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.question.RemoveTopicCommand;

public class DeleteTopicInQuestionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public DeleteTopicInQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer questionAggregateId, Integer topicAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(questionAggregateId, topicAggregateId, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer questionAggregateId, Integer topicAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("deleteTopicInQuestion", () -> {
            RemoveTopicCommand command = new RemoveTopicCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(),
                    questionAggregateId, topicAggregateId, eventVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
