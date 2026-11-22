package org.anonymous.msrefactor.quizzes.microservices.answer.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerRepository;
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.eventProcessing.QuizAnswerEventProcessing;

public class DeleteCourseExecutionEventHandler extends QuizAnswerEventHandler {
    public DeleteCourseExecutionEventHandler(QuizAnswerRepository quizAnswerRepository,
            QuizAnswerEventProcessing quizAnswerEventProcessing) {
        super(quizAnswerRepository, quizAnswerEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.quizAnswerEventProcessing.processDeleteCourseExecutionEvent(subscriberAggregateId,
                (DeleteCourseExecutionEvent) event);
    }
}
