package org.anonymous.msrefactor.quizzes.microservices.answer.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswer;

public class QuizAnswerSubscribesAnonymizeStudent extends EventSubscription {
    private Integer studentAggregateId;

    public QuizAnswerSubscribesAnonymizeStudent(QuizAnswer quizAnswer) {
        super(quizAnswer.getAnswerCourseExecution().getCourseExecutionAggregateId(),
                quizAnswer.getAnswerCourseExecution().getCourseExecutionVersion(),
                AnonymizeStudentEvent.class.getSimpleName());

        this.studentAggregateId = quizAnswer.getStudent().getStudentAggregateId();
    }

    public QuizAnswerSubscribesAnonymizeStudent() {}

    public boolean subscribesEvent(Event event) {
        return super.subscribesEvent(event) && checkAnswerInfo((AnonymizeStudentEvent)event);
    }

    private boolean checkAnswerInfo(AnonymizeStudentEvent anonymizeStudentEvent) {
        return this.studentAggregateId.equals(anonymizeStudentEvent.getStudentAggregateId());
    }


}