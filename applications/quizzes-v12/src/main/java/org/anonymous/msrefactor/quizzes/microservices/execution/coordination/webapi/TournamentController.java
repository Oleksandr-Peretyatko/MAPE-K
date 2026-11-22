package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities;

import java.util.List;
import java.util.Set;

@RestController
public class TournamentController {

    @Autowired
    private ExecutionFunctionalities executionFunctionalities;

    @PostMapping(value = "/executions/{executionId}/tournaments/create")
    public TournamentDto createTournament(@PathVariable int executionId, @RequestParam Integer userId, @RequestParam List<Integer> topicsId, @RequestBody TournamentDto tournamentDto) throws Exception {
        return executionFunctionalities.createTournament(userId, executionId, topicsId, tournamentDto);
    }

    @PostMapping(value = "/tournaments/update")
    public void updateTournament(@RequestParam Set<Integer> topicsId, @RequestBody TournamentDto tournamentDto) throws Exception {
        executionFunctionalities.updateTournament(tournamentDto, topicsId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/join")
    public void joinTournament(@PathVariable Integer tournamentAggregateId, @RequestParam Integer executionAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        executionFunctionalities.addParticipant(tournamentAggregateId, executionAggregateId, userAggregateId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/leave")
    public void leaveTournament(@PathVariable Integer tournamentAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        executionFunctionalities.leaveTournament(tournamentAggregateId, userAggregateId);
    }   

    @GetMapping(value = "/tournaments/{tournamentAggregateId}")
    public TournamentDto findTournament(@PathVariable Integer tournamentAggregateId) {
        return executionFunctionalities.findTournament(tournamentAggregateId);
    }

    @PostMapping(value = "/tournaments/{tournamentAggregateId}/solveQuiz")
    public QuizDto solveQuiz(@PathVariable Integer tournamentAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        return executionFunctionalities.solveQuiz(tournamentAggregateId, userAggregateId);
    }

    @GetMapping(value = "/executions/{executionAggregateId}/tournaments")
    public List<TournamentDto> getTournamentsForCourseExecution(@PathVariable Integer executionAggregateId) {
        return executionFunctionalities.getTournamentsForCourseExecution(executionAggregateId);
    }

    @GetMapping(value = "/executions/{executionAggregateId}/tournaments/opened")
    public List<TournamentDto> getOpenedTournamentsForCourseExecution(@PathVariable Integer executionAggregateId) {
        return executionFunctionalities.getOpenedTournamentsForCourseExecution(executionAggregateId);
    }

    @GetMapping(value = "/executions/{executionAggregateId}/tournaments/closed")
    public List<TournamentDto> getClosedTournamentsForCourseExecution(@PathVariable Integer executionAggregateId) {
        return executionFunctionalities.getClosedTournamentsForCourseExecution(executionAggregateId);
    }

    @PostMapping("/tournaments/{tournamentAggregate}/remove")
    public void removeTournament(@PathVariable Integer tournamentAggregate) throws Exception {
        executionFunctionalities.removeTournament(tournamentAggregate);
    }

    @GetMapping(value = "/tournaments/{tournamentAggregateId}/user/{userAggregateId}")
    public void getTournamentAndUser(@PathVariable Integer tournamentAggregateId, @PathVariable Integer userAggregateId) {
        executionFunctionalities.getTournamentAndUser(tournamentAggregateId, userAggregateId);
    }
}
