package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizRepository;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.eventProcessing.QuizEventProcessing;

public class DeleteQuestionEventHandler extends QuizEventHandler {
    public DeleteQuestionEventHandler(QuizRepository quizRepository, QuizEventProcessing quizEventProcessing) {
        super(quizRepository, quizEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.quizEventProcessing.processDeleteQuizQuestionEvent(subscriberAggregateId, (DeleteQuestionEvent) event);
    }
}
