package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.functionalities.TournamentSatelliteFunctionalities;

/*
 * The participant-facing routes carved out of TournamentController by the split.
 * The paths do not change — only the service answering them does.
 */
@RestController
public class TournamentSatelliteController {

    @Autowired
    private TournamentSatelliteFunctionalities tournamentSatelliteFunctionalities;

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/join")
    public void joinTournament(@PathVariable Integer tournamentAggregateId, @RequestParam Integer executionAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        tournamentSatelliteFunctionalities.addParticipant(tournamentAggregateId, executionAggregateId, userAggregateId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/leave")
    public void leaveTournament(@PathVariable Integer tournamentAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        tournamentSatelliteFunctionalities.leaveTournament(tournamentAggregateId, userAggregateId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/solveQuiz")
    public QuizDto solveQuiz(@PathVariable Integer tournamentAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        return tournamentSatelliteFunctionalities.solveQuiz(tournamentAggregateId, userAggregateId);
    }

    @GetMapping(value = "/tournaments/{tournamentAggregateId}/user/{userAggregateId}")
    public void getTournamentAndUser(@PathVariable Integer tournamentAggregateId, @PathVariable Integer userAggregateId) {
        tournamentSatelliteFunctionalities.getTournamentAndUser(tournamentAggregateId, userAggregateId);
    }
}
