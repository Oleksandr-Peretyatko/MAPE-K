package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

/*
 * QUIZ_ANSWER_EXISTS — reaction to QuizAnswerQuestionAnswerEvent; the subscriber is now
 * the Execution root.
 */
public class UpdateParticipantAnswerFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public UpdateParticipantAnswerFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
            Integer executionAggregateId,
            Integer userAggregateId, Integer quizAnswerAggregateId, Integer questionAggregateId,
            boolean correct, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        buildWorkflow(executionAggregateId, userAggregateId, quizAnswerAggregateId, questionAggregateId, correct,
                eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer executionAggregateId, Integer userAggregateId, Integer quizAnswerAggregateId,
            Integer questionAggregateId, boolean correct, Long eventVersion, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("updateParticipantAnswerStep", () -> {
            executionService.updateParticipantAnswer(executionAggregateId, userAggregateId, quizAnswerAggregateId,
                    questionAggregateId, correct, eventVersion, unitOfWork);
        });
        workflow.addStep(step);
    }
}
