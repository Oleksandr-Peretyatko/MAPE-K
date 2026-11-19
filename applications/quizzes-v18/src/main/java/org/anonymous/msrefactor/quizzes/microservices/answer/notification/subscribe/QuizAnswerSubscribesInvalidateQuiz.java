package org.anonymous.msrefactor.quizzes.microservices.answer.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswer;

public class QuizAnswerSubscribesInvalidateQuiz extends EventSubscription {
    public QuizAnswerSubscribesInvalidateQuiz(QuizAnswer quizAnswer) {
        super(quizAnswer.getQuiz().getQuizAggregateId(),
                quizAnswer.getQuiz().getQuizVersion(),
                InvalidateQuizEvent.class.getSimpleName());
    }

    public QuizAnswerSubscribesInvalidateQuiz() {}

    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event);
    }

}