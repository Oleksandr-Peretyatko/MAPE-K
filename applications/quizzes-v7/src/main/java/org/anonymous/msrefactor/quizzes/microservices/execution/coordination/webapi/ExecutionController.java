package org.anonymous.msrefactor.quizzes.microservices.execution.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.List;
import java.util.Set;

@RestController
public class ExecutionController {
    @Autowired
    private ExecutionFunctionalities executionFunctionalities;

    @PostMapping(value = "/executions/create")
    public CourseExecutionDto createCourseExecution(@RequestBody CourseExecutionDto executionDto) throws Exception {
        CourseExecutionDto courseExecutionDto = executionFunctionalities.createCourseExecution(executionDto);
        return courseExecutionDto;
    }

    @GetMapping(value = "/executions/{executionAggregateId}")
    public CourseExecutionDto getCourseExecutionByAggregateId(@PathVariable Integer executionAggregateId) {
        return executionFunctionalities.getCourseExecutionByAggregateId(executionAggregateId);
    }

    @GetMapping(value = "/executions")
    public List<CourseExecutionDto> getCourseExecutions() {
        System.out.println("getCourseExecutions");
        return executionFunctionalities.getCourseExecutions();
    }

    @PostMapping("/executions/{executionAggregateId}/delete")
    public void removeCourseExecution(@PathVariable Integer executionAggregateId) throws Exception {
        executionFunctionalities.removeCourseExecution(executionAggregateId);
    }

    @PostMapping("/executions/{executionAggregateId}/students/add")
    public void enrollStudent(@PathVariable Integer executionAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        executionFunctionalities.addStudent(executionAggregateId, userAggregateId);
    }

    @GetMapping("/users/{userAggregateId}/executions")
    public Set<CourseExecutionDto> getUserCourseExecutions(@PathVariable Integer userAggregateId) {
        return executionFunctionalities.getCourseExecutionsByUser(userAggregateId);
    }

    @PostMapping("/executions/{executionAggregateId}/students/remove")
    public void removeStudentFromCourseExecution(@PathVariable Integer executionAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        executionFunctionalities.removeStudentFromCourseExecution(executionAggregateId, userAggregateId);
    }

    @PostMapping("/executions/{executionAggregateId}/anonymize")
    public void anonymizeExecutionStudent(@PathVariable Integer executionAggregateId, @RequestParam Integer userAggregateId) throws Exception {
        executionFunctionalities.anonymizeStudent(executionAggregateId, userAggregateId);
    }

    @PostMapping("/executions/{executionAggregateId}/students/{userAggregateId}/update/name")
    public void updateExecutionStudentName(@PathVariable Integer executionAggregateId, @PathVariable Integer userAggregateId, @RequestBody UserDto userDto) throws Exception {
        executionFunctionalities.updateStudentName(executionAggregateId, userAggregateId, userDto);
    }

    /* Quiz endpoints (merged from the former Quiz aggregate) */

    @PostMapping("/executions/{courseExecutionId}")
    public QuizDto createQuiz(@PathVariable Integer courseExecutionId, @RequestBody QuizDto quizDto) throws Exception {
        return executionFunctionalities.createQuiz(courseExecutionId, quizDto);
    }

    @PostMapping("/quizzes/update")
    public QuizDto updateQuiz(@RequestBody QuizDto quizDto) throws Exception {
        return executionFunctionalities.updateQuiz(quizDto);
    }

    @GetMapping("/quizzes/{quizAggregateId}")
    public QuizDto findQuiz(@PathVariable Integer quizAggregateId) {
        return executionFunctionalities.findQuiz(quizAggregateId);
    }

    /* Tournament endpoints (merged from the former Tournament aggregate) */

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
