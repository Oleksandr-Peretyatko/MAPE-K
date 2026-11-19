package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

public class RemoveQuestionFunctionalitySagas extends WorkflowFunctionality {

    private QuestionDto question;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public RemoveQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                            Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(questionAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer questionAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep removeQuestionStep = new SagaStep("removeQuestionStep", () -> {
            // the remote getQuestionStep collapsed into its only dependent (lock dropped,
            // Question unchecked); the counter step is gone — the invariant reads the
            // owned collection directly
            this.setQuestion(courseService.getQuestionById(questionAggregateId, unitOfWork));
            courseService.removeQuestion(questionAggregateId, unitOfWork);
        });

        workflow.addStep(removeQuestionStep);
    }

    public QuestionDto getQuestion() {
        return question;
    }

    public void setQuestion(QuestionDto question) {
        this.question = question;
    }
}
