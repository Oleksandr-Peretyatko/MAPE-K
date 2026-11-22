package org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.Tournament;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentFactory;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.SagaTournament;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.Set;

@Service
@Profile("sagas")
public class SagasTournamentFactory implements TournamentFactory {

    @Override
    public Tournament createTournament(Integer aggregateId, TournamentDto tournamentDto, UserDto creatorDto,
            CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos, QuizDto quizDto) {
        return new SagaTournament(aggregateId, tournamentDto, creatorDto, courseExecutionDto, topicDtos, quizDto);
    }

    @Override
    public Tournament createTournamentFromExisting(Tournament existingTournament) {
        return new SagaTournament((SagaTournament) existingTournament);
    }

    @Override
    public TournamentDto createTournamentDto(Tournament tournament) {
        return new TournamentDto(tournament);
    }
}
