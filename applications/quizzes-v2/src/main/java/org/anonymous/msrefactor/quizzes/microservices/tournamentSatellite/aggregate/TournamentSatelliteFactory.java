package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;

@Component
public interface TournamentSatelliteFactory {
    TournamentSatellite createTournamentSatellite(Integer aggregateId, Integer tournamentAggregateId, UserDto creatorDto,
            Integer courseExecutionAggregateId, Long courseExecutionVersion, LocalDateTime startTime, boolean cancelled);

    TournamentSatellite createTournamentSatelliteFromExisting(TournamentSatellite existingTournamentSatellite);

    TournamentSatelliteDto createTournamentSatelliteDto(TournamentSatellite tournamentSatellite);
}
