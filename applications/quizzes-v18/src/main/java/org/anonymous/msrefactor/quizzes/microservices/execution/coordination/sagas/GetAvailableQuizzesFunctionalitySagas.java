package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

import java.util.List;

public class GetAvailableQuizzesFunctionalitySagas extends WorkflowFunctionality {
    private List<QuizDto> availableQuizzes;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final ExecutionService executionService;

    public GetAvailableQuizzesFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, ExecutionService executionService,
                                                 Integer userAggregateId, Integer courseExecutionAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.executionService = executionService;
        this.buildWorkflow(userAggregateId, courseExecutionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer userAggregateId, Integer courseExecutionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getAvailableQuizzesStep = new SagaStep("getAvailableQuizzesStep", () -> {
            this.setAvailableQuizzes(executionService.getAvailableQuizzes(courseExecutionAggregateId, unitOfWork));
        });

        workflow.addStep(getAvailableQuizzesStep);
    }

    public List<QuizDto> getAvailableQuizzes() {
        return availableQuizzes;
    }

    public void setAvailableQuizzes(List<QuizDto> availableQuizzes) {
        this.availableQuizzes = availableQuizzes;
    }
}
