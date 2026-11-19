package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import java.util.Optional;

public interface TournamentSatelliteSatelliteCustomRepository {
    Optional<Integer> findTournamentSatelliteSatelliteIdByTournamentId(Integer tournamentAggregateId);
}
