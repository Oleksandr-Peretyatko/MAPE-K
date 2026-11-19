package org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DeleteUserEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing;

public class DeleteUserEventHandler extends CourseExecutionEventHandler {
    public DeleteUserEventHandler(CourseExecutionRepository courseExecutionRepository, ExecutionEventProcessing executionEventProcessing) {
        super(courseExecutionRepository, executionEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.executionEventProcessing.processDeleteUserEvent(subscriberAggregateId, (DeleteUserEvent) event);
    }
}
