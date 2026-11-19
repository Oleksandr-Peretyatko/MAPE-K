package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

/*
 * TOPIC_EXISTS (tournament side) — reaction to DeleteTopicEvent; the subscriber is now
 * the Execution root. The dissolved aggregate's service body was a no-op and stays one.
 */
public class DeleteTournamentTopicFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public DeleteTournamentTopicFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
            Integer executionAggregateId, Integer topicAggregateId, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        buildWorkflow(executionAggregateId, topicAggregateId, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer executionAggregateId, Integer topicAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("deleteTopicStep", () -> {
            executionService.removeTopicInTournaments(executionAggregateId, topicAggregateId, eventVersion, unitOfWork);
        });
        workflow.addStep(step);
    }
}
