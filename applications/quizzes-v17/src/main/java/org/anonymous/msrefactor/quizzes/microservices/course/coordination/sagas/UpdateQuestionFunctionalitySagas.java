package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

public class UpdateQuestionFunctionalitySagas extends WorkflowFunctionality {
    private QuestionDto question;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public UpdateQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                            QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(questionDto, unitOfWork);
    }

    public void buildWorkflow(QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep updateQuestionStep = new SagaStep("updateQuestionStep", () -> {
            // the remote getQuestionStep collapsed into its only dependent: the read is
            // in-process now (its READ_QUESTION lock was dropped — Question's states
            // were never checked)
            this.setQuestion(courseService.getQuestionById(questionDto.getAggregateId(), unitOfWork));
            courseService.updateQuestion(questionDto, unitOfWork);
        });

        workflow.addStep(updateQuestionStep);
    }

    public QuestionDto getQuestion() {
        return question;
    }

    public void setQuestion(QuestionDto question) {
        this.question = question;
    }
}
