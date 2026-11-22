package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

public class GetTopicByIdFunctionalitySagas extends WorkflowFunctionality {
    private TopicDto topicDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public GetTopicByIdFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                          Integer topicAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(topicAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer topicAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicStep = new SagaStep("getTopicStep", () -> {
            this.setTopicDto(courseService.getTopicById(topicAggregateId, unitOfWork));
        });

        workflow.addStep(getTopicStep);
    }

    public void setTopicDto(TopicDto topicDto) {
        this.topicDto = topicDto;
    }

    public TopicDto getTopicDto() {
        return this.topicDto;
    }

}
