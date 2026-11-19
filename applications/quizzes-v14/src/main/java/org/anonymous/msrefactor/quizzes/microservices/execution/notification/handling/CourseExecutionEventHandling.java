package org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteUserEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.DeleteQuestionEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.DeleteUserEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.UpdateQuestionEventHandler;

@Component
public class CourseExecutionEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private ExecutionEventProcessing executionEventProcessing;
    @Autowired
    private CourseExecutionRepository courseExecutionRepository;

    /*
     * USER_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveUserEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteUserEvent.class,
                new DeleteUserEventHandler(courseExecutionRepository, executionEventProcessing));
    }

    /*
        QUESTION_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(UpdateQuestionEvent.class,
                new UpdateQuestionEventHandler(courseExecutionRepository, executionEventProcessing));
    }

    /*
        QUESTION_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(DeleteQuestionEvent.class,
                new DeleteQuestionEventHandler(courseExecutionRepository, executionEventProcessing));
    }

}
