package org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing;

/* moved from tournament (merge execution <- tournament): the subscriber is the owning Execution root */
public class QuizAnswerQuestionAnswerEventHandler extends CourseExecutionEventHandler {
    public QuizAnswerQuestionAnswerEventHandler(CourseExecutionRepository courseExecutionRepository, ExecutionEventProcessing executionEventProcessing) {
        super(courseExecutionRepository, executionEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.executionEventProcessing.processAnswerQuestionEvent(subscriberAggregateId, (QuizAnswerQuestionAnswerEvent) event);
    }
}
