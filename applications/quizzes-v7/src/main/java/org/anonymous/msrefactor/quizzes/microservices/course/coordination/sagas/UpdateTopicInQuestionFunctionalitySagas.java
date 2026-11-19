package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.course.UpdateTopicCommand;

public class UpdateTopicInQuestionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateTopicInQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer questionAggregateId, Integer topicAggregateId, String topicName, Long eventVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(questionAggregateId, topicAggregateId, topicName, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer questionAggregateId, Integer topicAggregateId, String topicName,
            Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateTopicInQuestion", () -> {
            UpdateTopicCommand command = new UpdateTopicCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(),
                    questionAggregateId, topicAggregateId, topicName, eventVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
