package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import java.util.Optional;

public interface TournamentSatelliteCustomRepository {
    Optional<Integer> findTournamentSatelliteIdByTournamentId(Integer tournamentAggregateId);

    Optional<TournamentSatellite> findTournamentSatelliteById(Integer aggregateId);
}
