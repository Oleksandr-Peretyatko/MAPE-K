package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.TournamentSatelliteSatelliteRepository;

import java.util.Optional;

@Service
@Profile("sagas")
public class TournamentSatelliteSatelliteCustomRepositorySagas implements TournamentSatelliteSatelliteCustomRepository {

    @Autowired
    private TournamentSatelliteSatelliteRepository tournamentSatelliteSatelliteRepository;

    @Override
    public Optional<Integer> findTournamentSatelliteSatelliteIdByTournamentId(Integer tournamentAggregateId) {
        return tournamentSatelliteSatelliteRepository
                .findTournamentSatelliteSatelliteIdByTournamentId(tournamentAggregateId);
    }
}
