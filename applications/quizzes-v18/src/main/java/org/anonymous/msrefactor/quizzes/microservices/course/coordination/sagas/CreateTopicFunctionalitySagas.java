package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.states.CourseSagaState;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

public class CreateTopicFunctionalitySagas extends WorkflowFunctionality {
    private TopicDto createdTopicDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public CreateTopicFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                         Integer courseAggregateId, TopicDto topicDto, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(courseAggregateId, topicDto, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, TopicDto topicDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep createTopicStep = new SagaStep("createTopicStep", () -> {
            // the course read and the topic creation are local now; the semantic lock
            // the remote getCourseStep took on the course is kept in-process (Course
            // states are checked). The TopicCourse replica the old step produced is
            // gone: the member's back-reference to the root replaced it.
            unitOfWorkService.registerSagaState(courseAggregateId, CourseSagaState.READ_COURSE, unitOfWork);
            TopicDto createdTopicDto = courseService.createTopic(courseAggregateId, topicDto, unitOfWork);
            this.setCreatedTopicDto(createdTopicDto);
        });

        workflow.addStep(createTopicStep);
    }

    public TopicDto getCreatedTopicDto() {
        return createdTopicDto;
    }

    public void setCreatedTopicDto(TopicDto createdTopicDto) {
        this.createdTopicDto = createdTopicDto;
    }
}
