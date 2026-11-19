package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizRepository;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.eventProcessing.QuizEventProcessing;

public abstract class QuizEventHandler extends EventHandler {
    protected QuizEventProcessing quizEventProcessing;

    public QuizEventHandler(QuizRepository quizRepository, QuizEventProcessing quizEventProcessing) {
        super(quizRepository);
        this.quizEventProcessing = quizEventProcessing;
    }

}
