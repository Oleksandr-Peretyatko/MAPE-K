package org.anonymous.msrefactor.quizzes.microservices.answer.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerRepository;
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.eventProcessing.QuizAnswerEventProcessing;

public abstract class QuizAnswerEventHandler extends EventHandler {
    protected QuizAnswerEventProcessing quizAnswerEventProcessing;

    public QuizAnswerEventHandler(QuizAnswerRepository quizAnswerRepository, QuizAnswerEventProcessing quizAnswerEventProcessing) {
        super(quizAnswerRepository);
        this.quizAnswerEventProcessing = quizAnswerEventProcessing;
    }

}
