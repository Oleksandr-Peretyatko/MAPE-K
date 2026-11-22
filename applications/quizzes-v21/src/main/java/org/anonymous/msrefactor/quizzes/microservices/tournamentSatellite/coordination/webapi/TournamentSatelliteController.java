package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.webapi;

import org.springframework.web.bind.annotation.RestController;

/*
 * Empty stub: every participant-facing path moved to
 * TournamentSatelliteSatelliteController with the roster split, and the core
 * satellite is reached by command only. The two event-trigger paths live on
 * TournamentSatelliteEventController.
 */
@RestController
public class TournamentSatelliteController {
}
