package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

public class RemoveQuizQuestionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public RemoveQuizQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
            Integer executionAggregateId, Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        buildWorkflow(executionAggregateId, questionAggregateId, unitOfWork);
    }

    private void buildWorkflow(Integer executionAggregateId, Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("removeQuizQuestion", () -> {
            executionService.removeQuizQuestion(executionAggregateId, questionAggregateId, unitOfWork);
        });
        workflow.addStep(step);
    }
}
