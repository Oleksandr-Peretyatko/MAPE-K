package org.anonymous.msrefactor.quizzes.microservices.execution.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.CourseExecutionEventHandling;

/*
 * Event-trigger API adopted from the dissolved tournament service: forces event
 * handling instead of waiting for the poll. The paths are externally visible and keep
 * answering.
 */
@RestController
public class TournamentEventController {
    @Autowired
    private CourseExecutionEventHandling courseExecutionEventHandling;

    @PostMapping("/events/topic/update")
    public void processUpdateTopic() {
        courseExecutionEventHandling.handleUpdateTopicEvents();
    }

    /*
     * Student anonymisation and renames now reach tournaments synchronously inside the
     * Execution aggregate — there is no pending event handling to force, so these
     * triggers are deliberate no-ops that keep their routes answering.
     */
    @PostMapping(value = "/tournament/process/anonymize")
    public void processAnonymize() {
    }

    @PostMapping(value = "/tournament/process/updateExecutionStudentName")
    public void processUpdateExecutionName() {
    }
}
