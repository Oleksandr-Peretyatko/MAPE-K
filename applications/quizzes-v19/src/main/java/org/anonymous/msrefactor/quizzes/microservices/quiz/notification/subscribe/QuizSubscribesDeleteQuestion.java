package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizQuestion;

public class QuizSubscribesDeleteQuestion extends EventSubscription {
    public QuizSubscribesDeleteQuestion(QuizQuestion quizQuestion) {
        super(quizQuestion.getQuestionAggregateId(),
                quizQuestion.getQuestionVersion(),
                DeleteQuestionEvent.class.getSimpleName());
    }

    public QuizSubscribesDeleteQuestion() {}

    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}