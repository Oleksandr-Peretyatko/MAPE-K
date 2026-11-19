package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

import java.util.List;

public class FindQuestionsByCourseFunctionalitySagas extends WorkflowFunctionality {
    private List<QuestionDto> questions;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public FindQuestionsByCourseFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                                   Integer courseAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(courseAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findQuestionsStep = new SagaStep("findQuestionsStep", () -> {
            this.setQuestions(courseService.findQuestionsByCourseAggregateId(courseAggregateId, unitOfWork));
        });

        workflow.addStep(findQuestionsStep);
    }

    public List<QuestionDto> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDto> questions) {
        this.questions = questions;
    }
}
