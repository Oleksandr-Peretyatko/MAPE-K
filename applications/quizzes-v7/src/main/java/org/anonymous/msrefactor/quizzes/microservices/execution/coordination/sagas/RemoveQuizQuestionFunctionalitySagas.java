package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveQuizQuestionCommand;

public class RemoveQuizQuestionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveQuizQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer executionAggregateId, Integer questionAggregateId, SagaUnitOfWork unitOfWork,
            CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(executionAggregateId, questionAggregateId, unitOfWork);
    }

    private void buildWorkflow(Integer executionAggregateId, Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("removeQuizQuestion", () -> {
            RemoveQuizQuestionCommand command = new RemoveQuizQuestionCommand(unitOfWork,
                    ServiceMapping.EXECUTION.getServiceName(),
                    executionAggregateId, questionAggregateId);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
