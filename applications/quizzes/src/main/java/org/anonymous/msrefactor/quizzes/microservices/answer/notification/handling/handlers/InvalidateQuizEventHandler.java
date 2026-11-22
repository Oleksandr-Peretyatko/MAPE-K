package org.anonymous.msrefactor.quizzes.microservices.answer.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerRepository;
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.eventProcessing.QuizAnswerEventProcessing;

public class InvalidateQuizEventHandler extends QuizAnswerEventHandler {
    public InvalidateQuizEventHandler(QuizAnswerRepository quizAnswerRepository,
            QuizAnswerEventProcessing quizAnswerEventProcessing) {
        super(quizAnswerRepository, quizAnswerEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.quizAnswerEventProcessing.processInvalidateQuizEvent(subscriberAggregateId,
                (InvalidateQuizEvent) event);
    }
}
