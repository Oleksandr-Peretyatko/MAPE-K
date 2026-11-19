package org.anonymous.msrefactor.quizzes.microservices.answer.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.answer.RemoveUserFromQuizAnswerCommand;

public class RemoveUserFromQuizAnswerFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveUserFromQuizAnswerFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer quizAnswerAggregateId, Integer userAggregateId, Long publisherAggregateVersion,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(quizAnswerAggregateId, userAggregateId, publisherAggregateVersion, unitOfWork);
    }

    private void buildWorkflow(Integer quizAnswerAggregateId, Integer userAggregateId,
            Long publisherAggregateVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("removeUserFromQuizAnswer", () -> {
            RemoveUserFromQuizAnswerCommand command = new RemoveUserFromQuizAnswerCommand(unitOfWork,
                    ServiceMapping.ANSWER.getServiceName(), quizAnswerAggregateId, userAggregateId,
                    publisherAggregateVersion);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
