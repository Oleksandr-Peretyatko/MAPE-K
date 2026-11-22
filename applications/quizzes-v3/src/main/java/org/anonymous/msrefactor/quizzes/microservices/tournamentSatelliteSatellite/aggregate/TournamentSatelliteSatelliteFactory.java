package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;

@Component
public interface TournamentSatelliteSatelliteFactory {
    TournamentSatelliteSatellite createTournamentSatelliteSatellite(Integer aggregateId, Integer tournamentAggregateId,
            UserDto creatorDto, Integer courseExecutionAggregateId, Long courseExecutionVersion, LocalDateTime startTime,
            boolean cancelled);

    TournamentSatelliteSatellite createTournamentSatelliteSatelliteFromExisting(
            TournamentSatelliteSatellite existingTournamentSatelliteSatellite);

    TournamentSatelliteSatelliteDto createTournamentSatelliteSatelliteDto(
            TournamentSatelliteSatellite tournamentSatelliteSatellite);
}
