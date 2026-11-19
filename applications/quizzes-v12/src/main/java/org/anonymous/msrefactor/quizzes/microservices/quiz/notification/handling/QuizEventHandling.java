package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizRepository;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.eventProcessing.QuizEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers.*;

@Component
public class QuizEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private QuizEventProcessing quizEventProcessing;
    @Autowired
    private QuizRepository quizRepository;

    /*
        COURSE_EXECUTION_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveCourseExecutionEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteCourseExecutionEvent.class,
                new DeleteCourseExecutionEventHandler(quizRepository, quizEventProcessing));
    }

    /*
        QUESTION_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateQuestionEvent.class,
                new UpdateQuestionEventHandler(quizRepository, quizEventProcessing));
    }

    /*
        QUESTION_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(DeleteQuestionEvent.class,
                new DeleteQuestionEventHandler(quizRepository, quizEventProcessing));
    }

    /*
        Absorbed from QuizAnswerEventHandling (merge quiz <- answer). The DeleteUserEvent poll is
        carried forward with its pre-existing defect: it has a handler but no subscription, so it
        never delivers — exactly as in the answer service before the merge.
     */

    /*
        USER_EXISTS
    */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveUserEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteUserEvent.class,
                new DeleteUserEventHandler(quizRepository, quizEventProcessing));
    }

    /*
        USER_EXISTS (unenroll)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUnenrollStudentEvent() {
        eventApplicationService.handleSubscribedEvent(DisenrollStudentFromCourseExecutionEvent.class,
                new DisenrollStudentFromCourseExecutionEventHandler(quizRepository, quizEventProcessing));
    }

    /*
        USER_EXISTS (name update)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateExecutionStudentNameEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateStudentNameEvent.class,
                new UpdateStudentNameEventHandler(quizRepository, quizEventProcessing));
    }

    /*
        USER_EXISTS (anonymize)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleAnonymizeStudentEvents() {
        eventApplicationService.handleSubscribedEvent(AnonymizeStudentEvent.class,
                new AnonymizeStudentEventHandler(quizRepository, quizEventProcessing));
    }

}
