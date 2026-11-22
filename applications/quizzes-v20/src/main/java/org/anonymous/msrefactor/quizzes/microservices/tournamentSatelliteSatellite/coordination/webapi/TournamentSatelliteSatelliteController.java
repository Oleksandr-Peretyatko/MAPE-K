package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.functionalities.TournamentSatelliteSatelliteFunctionalities;

/*
 * The participant-facing routes carved out of TournamentSatelliteController by
 * the split. The paths do not change — only the service answering them does.
 */
@RestController
public class TournamentSatelliteSatelliteController {

    @Autowired
    private TournamentSatelliteSatelliteFunctionalities tournamentSatelliteSatelliteFunctionalities;

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/join")
    public void joinTournament(@PathVariable Integer tournamentAggregateId, @RequestParam Integer executionAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        tournamentSatelliteSatelliteFunctionalities.addParticipant(tournamentAggregateId, executionAggregateId, userAggregateId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/leave")
    public void leaveTournament(@PathVariable Integer tournamentAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        tournamentSatelliteSatelliteFunctionalities.leaveTournament(tournamentAggregateId, userAggregateId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/solveQuiz")
    public QuizDto solveQuiz(@PathVariable Integer tournamentAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        return tournamentSatelliteSatelliteFunctionalities.solveQuiz(tournamentAggregateId, userAggregateId);
    }

    @GetMapping(value = "/tournaments/{tournamentAggregateId}/user/{userAggregateId}")
    public void getTournamentAndUser(@PathVariable Integer tournamentAggregateId, @PathVariable Integer userAggregateId) {
        tournamentSatelliteSatelliteFunctionalities.getTournamentAndUser(tournamentAggregateId, userAggregateId);
    }
}
