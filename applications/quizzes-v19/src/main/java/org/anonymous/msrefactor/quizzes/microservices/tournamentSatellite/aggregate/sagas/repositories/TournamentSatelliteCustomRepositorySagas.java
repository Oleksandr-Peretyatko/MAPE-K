package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatellite;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.TournamentSatelliteRepository;

import java.util.Optional;

@Service
@Profile("sagas")
public class TournamentSatelliteCustomRepositorySagas implements TournamentSatelliteCustomRepository {

    @Autowired
    private TournamentSatelliteRepository tournamentSatelliteRepository;

    @Override
    public Optional<Integer> findTournamentSatelliteIdByTournamentId(Integer tournamentAggregateId) {
        return tournamentSatelliteRepository.findTournamentSatelliteIdByTournamentAggregateId(tournamentAggregateId);
    }

    @Override
    public Optional<TournamentSatellite> findTournamentSatelliteById(Integer aggregateId) {
        return tournamentSatelliteRepository.findById(aggregateId);
    }
}
