package org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizQuestion;

public class QuizSubscribesUpdateQuestion extends EventSubscription {
    public QuizSubscribesUpdateQuestion(QuizQuestion quizQuestion) {
        super(quizQuestion.getQuestionAggregateId(),
                quizQuestion.getQuestionVersion(),
                UpdateQuestionEvent.class.getSimpleName());
    }

    public QuizSubscribesUpdateQuestion() {}

    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}