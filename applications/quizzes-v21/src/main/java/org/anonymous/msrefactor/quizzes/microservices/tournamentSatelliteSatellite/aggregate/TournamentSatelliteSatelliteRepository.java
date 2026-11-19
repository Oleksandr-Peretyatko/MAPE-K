package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

@Repository
@Transactional
public interface TournamentSatelliteSatelliteRepository extends JpaRepository<TournamentSatelliteSatellite, Integer> {
    @Query("SELECT t.aggregateId FROM TournamentSatelliteSatellite t")
    Set<Integer> findAllAggregateIds();

    @Query(value = "select t1.aggregateId from TournamentSatelliteSatellite t1 where t1.tournamentAggregateId = :tournamentAggregateId AND t1.state <> 'DELETED' AND t1.version = (select max(t2.version) from TournamentSatelliteSatellite t2 where t2.aggregateId = t1.aggregateId)")
    Optional<Integer> findTournamentSatelliteSatelliteIdByTournamentAggregateId(Integer tournamentAggregateId);

    @Query(value = "select a1 from TournamentSatelliteSatellite a1 where a1.aggregateId = :aggregateId AND a1.state = 'ACTIVE' AND a1.version = (select max(a2.version) from Aggregate a2 where a2.aggregateId = :aggregateId)")
    Optional<TournamentSatelliteSatellite> findLastAggregateVersion(Integer aggregateId);
}
