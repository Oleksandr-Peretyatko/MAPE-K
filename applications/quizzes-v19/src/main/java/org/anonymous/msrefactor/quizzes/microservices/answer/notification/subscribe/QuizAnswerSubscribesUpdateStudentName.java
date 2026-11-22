package org.anonymous.msrefactor.quizzes.microservices.answer.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswer;

public class QuizAnswerSubscribesUpdateStudentName extends EventSubscription {
    private Integer studentAggregateId;

    public QuizAnswerSubscribesUpdateStudentName(QuizAnswer quizAnswer) {
        super(quizAnswer.getAnswerCourseExecution().getCourseExecutionAggregateId(),
                quizAnswer.getAnswerCourseExecution().getCourseExecutionVersion(),
                UpdateStudentNameEvent.class.getSimpleName());

        this.studentAggregateId = quizAnswer.getStudent().getStudentAggregateId();
    }

    public QuizAnswerSubscribesUpdateStudentName() {}

    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkAnswerInfo((UpdateStudentNameEvent)event);
    }

    private boolean checkAnswerInfo(UpdateStudentNameEvent updateStudentNameEvent) {
        return this.studentAggregateId.equals(updateStudentNameEvent.getStudentAggregateId());
    }


}