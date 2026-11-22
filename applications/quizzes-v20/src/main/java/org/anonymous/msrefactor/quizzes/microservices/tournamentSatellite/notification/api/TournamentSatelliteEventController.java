package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.TournamentSatelliteEventHandling;

/*
 * The two event-trigger routes that force handling of the student events moved
 * here with their quads; the paths are unchanged.
 */
@RestController
public class TournamentSatelliteEventController {
    @Autowired
    private TournamentSatelliteEventHandling tournamentSatelliteEventHandling;

    @PostMapping(value = "/tournament/process/anonymize")
    public void processAnonymize() {
        tournamentSatelliteEventHandling.handleAnonymizeStudentEvents();
    }

    @PostMapping(value = "/tournament/process/updateExecutionStudentName")
    public void processUpdateExecutionName() {
        tournamentSatelliteEventHandling.handleUpdateStudentNameEvent();
    }
}
