package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

@Component
public interface TournamentSatelliteSatelliteFactory {
    TournamentSatelliteSatellite createTournamentSatelliteSatellite(Integer aggregateId, Integer tournamentAggregateId,
            Integer tournamentSatelliteAggregateId, TournamentDto tournamentDto, CourseExecutionDto courseExecutionDto);

    TournamentSatelliteSatellite createTournamentSatelliteSatelliteFromExisting(
            TournamentSatelliteSatellite existingSatellite);

    TournamentSatelliteSatelliteDto createTournamentSatelliteSatelliteDto(
            TournamentSatelliteSatellite tournamentSatelliteSatellite);
}
