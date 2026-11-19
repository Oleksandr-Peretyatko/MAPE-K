package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.sagas.states.QuizSagaState;
import org.anonymous.msrefactor.quizzes.microservices.quiz.service.QuizService;

// Absorbed from the answer service (merge quiz <- answer). Both former steps are local; the
// workflow is kept as a single step to carry the checked READ_QUIZ_ANSWER lock in-process,
// registered on the owning Quiz root (workflow commit releases it).
public class ConcludeQuizFunctionalitySagas extends WorkflowFunctionality {

    private final SagaUnitOfWorkService unitOfWorkService;
    private final QuizService quizService;

    public ConcludeQuizFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, QuizService quizService,
                                          Integer quizAggregateId, Integer userAggregateId,
                                          SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.quizService = quizService;
        this.buildWorkflow(quizAggregateId, userAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer quizAggregateId, Integer userAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep concludeQuizStep = new SagaStep("concludeQuizStep", () -> {
            unitOfWorkService.registerSagaState(quizAggregateId, QuizSagaState.READ_QUIZ_ANSWER, unitOfWork);
            quizService.concludeQuiz(quizAggregateId, userAggregateId, unitOfWork);
        });

        workflow.addStep(concludeQuizStep);
    }
}
