package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

@Component
public interface TournamentSatelliteFactory {
    TournamentSatellite createTournamentSatellite(Integer aggregateId, Integer tournamentAggregateId,
            TournamentDto tournamentDto, UserDto creatorDto, CourseExecutionDto courseExecutionDto);

    TournamentSatellite createTournamentSatelliteFromExisting(TournamentSatellite existingSatellite);

    TournamentSatelliteDto createTournamentSatelliteDto(TournamentSatellite tournamentSatellite);
}
