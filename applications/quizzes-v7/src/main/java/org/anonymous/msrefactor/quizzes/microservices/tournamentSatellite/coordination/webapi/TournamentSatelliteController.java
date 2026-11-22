package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.webapi;

import org.springframework.web.bind.annotation.RestController;

/*
 * The participant-facing endpoints stay on the core TournamentController (case 6: kept on the core,
 * reaching the satellite by command). This controller is an intentionally empty stub, like
 * CourseController, so the satellite is still a complete deployment unit.
 */
@RestController
public class TournamentSatelliteController {
}
