package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizRepository;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.eventProcessing.QuizEventProcessing;

// moved from the answer service (merge quiz <- answer); the subscriber repository is the Quiz root's
public class DisenrollStudentFromCourseExecutionEventHandler extends QuizEventHandler {
    public DisenrollStudentFromCourseExecutionEventHandler(QuizRepository quizRepository, QuizEventProcessing quizEventProcessing) {
        super(quizRepository, quizEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.quizEventProcessing.processDisenrollStudentEvent(subscriberAggregateId, (DisenrollStudentFromCourseExecutionEvent) event);
    }
}
