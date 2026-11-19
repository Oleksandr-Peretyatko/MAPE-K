package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteFactory;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.SagaTournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;

@Service
@Profile("sagas")
public class SagasTournamentSatelliteFactory implements TournamentSatelliteFactory {

    @Override
    public TournamentSatellite createTournamentSatellite(Integer aggregateId, Integer tournamentAggregateId,
            UserDto creatorDto, Integer courseExecutionAggregateId, Long courseExecutionVersion, LocalDateTime startTime,
            boolean cancelled) {
        return new SagaTournamentSatellite(aggregateId, tournamentAggregateId, creatorDto, courseExecutionAggregateId,
                courseExecutionVersion, startTime, cancelled);
    }

    @Override
    public TournamentSatellite createTournamentSatelliteFromExisting(TournamentSatellite existingTournamentSatellite) {
        return new SagaTournamentSatellite((SagaTournamentSatellite) existingTournamentSatellite);
    }

    @Override
    public TournamentSatelliteDto createTournamentSatelliteDto(TournamentSatellite tournamentSatellite) {
        return new TournamentSatelliteDto(tournamentSatellite);
    }
}
