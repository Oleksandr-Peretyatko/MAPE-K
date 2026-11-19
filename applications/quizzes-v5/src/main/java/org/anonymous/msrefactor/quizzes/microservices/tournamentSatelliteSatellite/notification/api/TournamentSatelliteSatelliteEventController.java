package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.TournamentSatelliteSatelliteEventHandling;

@RestController
public class TournamentSatelliteSatelliteEventController {
    @Autowired
    private TournamentSatelliteSatelliteEventHandling tournamentSatelliteSatelliteEventHandling;

    @PostMapping(value = "/tournamentsatellitesatellite/process/anonymize")
    public void processAnonymize() {
        tournamentSatelliteSatelliteEventHandling.handleAnonymizeStudentEvents();
    }

    @PostMapping(value = "/tournamentsatellitesatellite/process/updateExecutionStudentName")
    public void processUpdateExecutionName() {
        tournamentSatelliteSatelliteEventHandling.handleUpdateStudentNameEvent();
    }
}
