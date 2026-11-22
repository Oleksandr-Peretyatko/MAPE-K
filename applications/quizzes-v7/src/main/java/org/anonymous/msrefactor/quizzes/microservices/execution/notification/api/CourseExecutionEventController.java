package org.anonymous.msrefactor.quizzes.microservices.execution.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.CourseExecutionEventHandling;

/*
 * Moved from the dissolved tournament-service (merge execution <- tournament); the @PostMapping
 * path is unchanged so the externally visible behaviour is preserved.
 */
@RestController
public class CourseExecutionEventController {
    @Autowired
    private CourseExecutionEventHandling courseExecutionEventHandling;

    @PostMapping("/events/topic/update")
    public void processUpdateTopic() {
        courseExecutionEventHandling.handleUpdateTopicEvents();
    }
}
