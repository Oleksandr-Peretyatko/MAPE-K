package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

public class UpdateTopicFunctionalitySagas extends WorkflowFunctionality {
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public UpdateTopicFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                         TopicDto topicDto, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(topicDto, unitOfWork);
    }

    public void buildWorkflow(TopicDto topicDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep updateTopicStep = new SagaStep("updateTopicStep", () -> {
            // the remote getTopicStep collapsed into its only dependent: the existence
            // check is inherent in the owner lookup, and its READ_TOPIC lock was
            // dropped (Topic's states were unchecked)
            courseService.updateTopic(topicDto, unitOfWork);
        });

        workflow.addStep(updateTopicStep);
    }
}
