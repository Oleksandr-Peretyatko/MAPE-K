package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizCourseExecution;

public class QuizSubscribesDeleteCourseExecution extends EventSubscription {
    public QuizSubscribesDeleteCourseExecution(QuizCourseExecution quizCourseExecution) {
        super(quizCourseExecution.getCourseExecutionAggregateId(),
                quizCourseExecution.getCourseExecutionVersion(),
                DeleteCourseExecutionEvent.class.getSimpleName());
    }

    public QuizSubscribesDeleteCourseExecution() {}

    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event);
    }

}