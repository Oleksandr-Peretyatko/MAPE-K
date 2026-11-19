package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.Set;

public interface CourseExecutionFactory {
    Execution createCourseExecution(Integer aggregateId, CourseExecutionDto courseExecutionDto, CourseExecutionCourse courseExecutionCourse);
    Execution createCourseExecutionFromExisting(Execution existingExecution);
    CourseExecutionDto createCourseExecutionDto(Execution execution);
    Quiz createQuiz(Integer quizId, Set<QuizQuestion> quizQuestions, QuizDto quizDto, QuizType quizType);
    QuizDto createQuizDto(Quiz quiz);
    Tournament createTournament(Integer tournamentId, TournamentDto tournamentDto, UserDto creatorDto, Set<TopicDto> topicDtos, QuizDto quizDto);
    TournamentDto createTournamentDto(Tournament tournament);
}
