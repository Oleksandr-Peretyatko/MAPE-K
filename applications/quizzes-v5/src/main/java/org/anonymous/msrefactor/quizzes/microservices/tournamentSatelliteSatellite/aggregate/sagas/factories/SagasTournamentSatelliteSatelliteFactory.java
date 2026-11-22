package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteFactory;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.SagaTournamentSatelliteSatellite;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;

@Service
@Profile("sagas")
public class SagasTournamentSatelliteSatelliteFactory implements TournamentSatelliteSatelliteFactory {

    @Override
    public TournamentSatelliteSatellite createTournamentSatelliteSatellite(Integer aggregateId,
            Integer tournamentAggregateId, UserDto creatorDto, Integer courseExecutionAggregateId,
            Long courseExecutionVersion, LocalDateTime startTime, boolean cancelled) {
        return new SagaTournamentSatelliteSatellite(aggregateId, tournamentAggregateId, creatorDto,
                courseExecutionAggregateId, courseExecutionVersion, startTime, cancelled);
    }

    @Override
    public TournamentSatelliteSatellite createTournamentSatelliteSatelliteFromExisting(
            TournamentSatelliteSatellite existingTournamentSatelliteSatellite) {
        return new SagaTournamentSatelliteSatellite(
                (SagaTournamentSatelliteSatellite) existingTournamentSatelliteSatellite);
    }

    @Override
    public TournamentSatelliteSatelliteDto createTournamentSatelliteSatelliteDto(
            TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        return new TournamentSatelliteSatelliteDto(tournamentSatelliteSatellite);
    }
}
