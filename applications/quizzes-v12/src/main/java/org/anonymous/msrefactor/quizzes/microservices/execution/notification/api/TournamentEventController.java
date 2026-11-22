package org.anonymous.msrefactor.quizzes.microservices.execution.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.CourseExecutionEventHandling;

/*
 * Moved from tournament (merge execution <- tournament). The paths are externally visible and
 * keep answering. The anonymize and updateExecutionStudentName endpoints used to force the
 * eventual tournament-side handling of Execution's own events; that synchronisation is
 * synchronous inside the merged aggregate now, so by the time these endpoints are called the
 * state they forced is already consistent — they answer and have nothing left to force.
 */
@RestController
public class TournamentEventController {
    @Autowired
    private CourseExecutionEventHandling courseExecutionEventHandling;

    @PostMapping("/events/topic/update")
    public void processUpdateTopic() {
        courseExecutionEventHandling.handleUpdateTopicEvents();
    }

    @PostMapping(value = "/tournament/process/anonymize")
    public void processAnonymize() {
        // intentionally empty: anonymisation propagates to tournaments synchronously
    }

    @PostMapping(value = "/tournament/process/updateExecutionStudentName")
    public void processUpdateExecutionName() {
        // intentionally empty: student-name updates propagate to tournaments synchronously
    }
}
