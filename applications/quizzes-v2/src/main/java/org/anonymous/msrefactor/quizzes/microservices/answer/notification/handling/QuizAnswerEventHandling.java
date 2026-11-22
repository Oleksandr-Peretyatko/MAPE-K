package org.anonymous.msrefactor.quizzes.microservices.answer.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerRepository;
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.eventProcessing.QuizAnswerEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.answer.notification.handling.handlers.*;

@Component
public class QuizAnswerEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private QuizAnswerRepository quizAnswerRepository;
    @Autowired
    private QuizAnswerEventProcessing quizAnswerEventProcessing;

    /*
        USER_EXISTS
    */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveUserEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteUserEvent.class,
                new DeleteUserEventHandler(quizAnswerRepository, quizAnswerEventProcessing));
    }

    /*
        COURSE_EXECUTION_EXISTS
    */
    @Scheduled(fixedDelay = 1000)
    public void handleDeleteCourseExecutionEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteCourseExecutionEvent.class,
                new DeleteCourseExecutionEventHandler(quizAnswerRepository, quizAnswerEventProcessing));
    }

    /*
        QUIZ_EXISTS
    */
    @Scheduled(fixedDelay = 1000)
    public void handleInvalidateQuizEvents() {
        eventApplicationService.handleSubscribedEvent(InvalidateQuizEvent.class,
                new InvalidateQuizEventHandler(quizAnswerRepository, quizAnswerEventProcessing));
    }

    /*
        USER_EXISTS (unenroll)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUnenrollStudentEvent() {
        eventApplicationService.handleSubscribedEvent(DisenrollStudentFromCourseExecutionEvent.class,
                new DisenrollStudentFromCourseExecutionEventHandler(quizAnswerRepository, quizAnswerEventProcessing));
    }

    /*
        USER_EXISTS (name update)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateExecutionStudentNameEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateStudentNameEvent.class,
                new UpdateStudentNameEventHandler(quizAnswerRepository, quizAnswerEventProcessing));
    }

    /*
        USER_EXISTS (anonymize)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleAnonymizeStudentEvents() {
        eventApplicationService.handleSubscribedEvent(AnonymizeStudentEvent.class,
                new AnonymizeStudentEventHandler(quizAnswerRepository, quizAnswerEventProcessing));
    }

}
