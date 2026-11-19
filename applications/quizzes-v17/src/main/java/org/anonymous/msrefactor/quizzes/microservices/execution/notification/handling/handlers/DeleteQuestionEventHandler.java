package org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing;

public class DeleteQuestionEventHandler extends CourseExecutionEventHandler {
    public DeleteQuestionEventHandler(CourseExecutionRepository courseExecutionRepository, ExecutionEventProcessing executionEventProcessing) {
        super(courseExecutionRepository, executionEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.executionEventProcessing.processDeleteQuizQuestionEvent(subscriberAggregateId, (DeleteQuestionEvent) event);
    }
}
