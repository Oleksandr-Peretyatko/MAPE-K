package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.TournamentSatelliteSatelliteEventHandling;

/*
 * Event-trigger routes that force handling of the participant branch of the
 * student events. The core TournamentSatellite keeps the original
 * /tournament/process/* paths for its creator branch; these are the roster's
 * own, new paths.
 */
@RestController
public class TournamentSatelliteSatelliteEventController {
    @Autowired
    private TournamentSatelliteSatelliteEventHandling tournamentSatelliteSatelliteEventHandling;

    @PostMapping(value = "/tournamentSatelliteSatellite/process/anonymize")
    public void processAnonymize() {
        tournamentSatelliteSatelliteEventHandling.handleAnonymizeStudentEvents();
    }

    @PostMapping(value = "/tournamentSatelliteSatellite/process/updateExecutionStudentName")
    public void processUpdateExecutionName() {
        tournamentSatelliteSatelliteEventHandling.handleUpdateStudentNameEvent();
    }
}
