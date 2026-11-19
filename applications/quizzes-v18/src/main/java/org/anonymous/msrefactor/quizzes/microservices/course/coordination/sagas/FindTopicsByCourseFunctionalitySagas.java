package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

import java.util.List;

public class FindTopicsByCourseFunctionalitySagas extends WorkflowFunctionality {
    private List<TopicDto> topics;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public FindTopicsByCourseFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                                Integer courseAggregateId, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(courseAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findTopicsStep = new SagaStep("findTopicsStep", () -> {
            this.setTopics(courseService.findTopicsByCourseId(courseAggregateId, unitOfWork));
        });

        workflow.addStep(findTopicsStep);
    }

    public List<TopicDto> getTopics() {
        return topics;
    }

    public void setTopics(List<TopicDto> topics) {
        this.topics = topics;
    }
}
