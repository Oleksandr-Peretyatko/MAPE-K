package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.execution.RemoveUserCommand;

public class RemoveUserFromCourseExecutionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public RemoveUserFromCourseExecutionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
            Integer courseExecutionAggregateId, Integer userAggregateId,
            SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        buildWorkflow(courseExecutionAggregateId, userAggregateId, unitOfWork);
    }

    private void buildWorkflow(Integer courseExecutionAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("removeUserFromCourseExecution", () -> {
            RemoveUserCommand command = new RemoveUserCommand(unitOfWork,
                    ServiceMapping.EXECUTION.getServiceName(),
                    courseExecutionAggregateId, userAggregateId);
            commandGateway.send(command);
        });
        workflow.addStep(step);
    }
}
