package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

public class FindQuestionByAggregateIdFunctionalitySagas extends WorkflowFunctionality {
    private QuestionDto questionDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public FindQuestionByAggregateIdFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                                       Integer aggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(aggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer aggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findQuestionStep = new SagaStep("findQuestionStep", () -> {
            this.setQuestionDto(courseService.getQuestionById(aggregateId, unitOfWork));
        });

        workflow.addStep(findQuestionStep);
    }

    public QuestionDto getQuestionDto() {
        return questionDto;
    }

    public void setQuestionDto(QuestionDto questionDto) {
        this.questionDto = questionDto;
    }
}
