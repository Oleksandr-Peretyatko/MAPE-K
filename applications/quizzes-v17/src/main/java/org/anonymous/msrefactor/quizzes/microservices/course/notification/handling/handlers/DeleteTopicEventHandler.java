package org.anonymous.msrefactor.quizzes.microservices.course.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.eventProcessing.CourseEventProcessing;

public class DeleteTopicEventHandler extends CourseEventHandler {
    public DeleteTopicEventHandler(CourseRepository courseRepository, CourseEventProcessing courseEventProcessing) {
        super(courseRepository, courseEventProcessing);
    }

    @Override
    public void handleEvent(Integer subscriberAggregateId, Event event) {
        this.courseEventProcessing.processDeleteTopic(subscriberAggregateId, (DeleteTopicEvent) event);
    }
}
