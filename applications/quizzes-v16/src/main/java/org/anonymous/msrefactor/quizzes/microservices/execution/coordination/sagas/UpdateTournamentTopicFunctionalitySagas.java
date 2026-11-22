package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

/*
 * TOPIC_EXISTS (tournament side) — reaction to UpdateTopicEvent; the subscriber is now
 * the Execution root.
 */
public class UpdateTournamentTopicFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public UpdateTournamentTopicFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
            Integer executionAggregateId, Integer topicAggregateId, String topicName, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        buildWorkflow(executionAggregateId, topicAggregateId, topicName, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer executionAggregateId, Integer topicAggregateId, String topicName,
            Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateTopicStep", () -> {
            executionService.updateTopicInTournaments(executionAggregateId, topicAggregateId, topicName, eventVersion, unitOfWork);
        });
        workflow.addStep(step);
    }
}
