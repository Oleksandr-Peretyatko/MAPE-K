package org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing;

public abstract class CourseExecutionEventHandler extends EventHandler {
    protected ExecutionEventProcessing executionEventProcessing;

    public CourseExecutionEventHandler(CourseExecutionRepository courseExecutionRepository, ExecutionEventProcessing executionEventProcessing) {
        super(courseExecutionRepository);
        this.executionEventProcessing = executionEventProcessing;
    }

}
