package org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.Set;

@Component
public interface TournamentFactory {
    Tournament createTournament(Integer aggregateId, TournamentDto tournamentDto, UserDto creatorDto, CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos, QuizDto quizDto);
    Tournament createTournamentFromExisting(Tournament existingTournament);
    TournamentDto createTournamentDto(Tournament tournament);
}
