package org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizRepository;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.eventProcessing.QuizEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers.DeleteCourseExecutionEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers.DeleteQuestionEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.handlers.UpdateQuestionEventHandler;

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

}
