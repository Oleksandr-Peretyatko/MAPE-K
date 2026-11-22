package org.anonymous.msrefactor.quizzes.microservices.answer.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.AnswerCourseExecution;

public class QuizAnswerSubscribesRemoveCourseExecution extends EventSubscription {
    public QuizAnswerSubscribesRemoveCourseExecution(AnswerCourseExecution answerCourseExecution) {
        super(answerCourseExecution.getCourseExecutionAggregateId(),
                answerCourseExecution.getCourseExecutionVersion(),
                DeleteCourseExecutionEvent.class.getSimpleName());
    }

    public QuizAnswerSubscribesRemoveCourseExecution() {}

    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event);
    }


}