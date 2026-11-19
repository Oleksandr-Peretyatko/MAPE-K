package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

/*
 * TOPICS_EXIST on DeleteTopicEvent: the subscriber is the Course root now, so the
 * event reaches every question of the course that copies the topic in one pass.
 */
public class DeleteTopicInQuestionFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public DeleteTopicInQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
            Integer courseAggregateId, Integer topicAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        buildWorkflow(courseAggregateId, topicAggregateId, eventVersion, unitOfWork);
    }

    private void buildWorkflow(Integer courseAggregateId, Integer topicAggregateId, Long eventVersion,
            SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);
        SagaStep step = new SagaStep("deleteTopicInQuestion", () -> {
            courseService.removeTopicInQuestions(courseAggregateId, topicAggregateId, eventVersion, unitOfWork);
        });
        workflow.addStep(step);
    }
}
