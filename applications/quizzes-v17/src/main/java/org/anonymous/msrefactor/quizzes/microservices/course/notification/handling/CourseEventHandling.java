package org.anonymous.msrefactor.quizzes.microservices.course.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.eventProcessing.CourseEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.course.notification.handling.handlers.DeleteTopicEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.course.notification.handling.handlers.UpdateTopicEventHandler;

@Component
public class CourseEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private CourseEventProcessing courseEventProcessing;
    @Autowired
    private CourseRepository courseRepository;

    /*
        TOPICS_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateTopicEvents() {
        eventApplicationService.handleSubscribedEvent(UpdateTopicEvent.class,
                new UpdateTopicEventHandler(courseRepository, courseEventProcessing));
    }

    /*
        TOPICS_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleDeleteTopicEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteTopicEvent.class,
                new DeleteTopicEventHandler(courseRepository, courseEventProcessing));
    }
}
