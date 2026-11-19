package org.anonymous.msrefactor.quizzes.microservices.question.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionRepository;
import org.anonymous.msrefactor.quizzes.microservices.question.coordination.eventProcessing.QuestionEventProcessing;

public abstract class QuestionEventHandler extends EventHandler {
    protected QuestionEventProcessing questionEventProcessing;

    public QuestionEventHandler(QuestionRepository questionRepository, QuestionEventProcessing questionEventProcessing) {
        super(questionRepository);
        this.questionEventProcessing = questionEventProcessing;
    }

}
