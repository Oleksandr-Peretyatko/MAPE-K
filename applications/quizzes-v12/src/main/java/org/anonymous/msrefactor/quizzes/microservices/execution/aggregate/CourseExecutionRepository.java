package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@Transactional
public interface CourseExecutionRepository extends JpaRepository<Execution, Integer> {
    @Query(value = "select ce1.aggregateId from Execution ce1 where ce1.aggregateId NOT IN (select ce2.aggregateId from Execution ce2 where ce2.state = 'DELETED' AND ce2.sagaState != org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState.NOT_IN_SAGA)")
    Set<Integer> findCourseExecutionIdsOfAllNonDeletedForSaga();

    @Query("SELECT e.aggregateId FROM Execution e")
    Set<Integer> findAllAggregateIds();

    // merge execution <- tournament: resolves the owning root of a tournament member
    // by the member's retained identity
    @Query(value = "select distinct e1.aggregateId from Execution e1 join e1.tournaments t1 where t1.tournamentId = :tournamentId AND e1.state = 'ACTIVE'")
    List<Integer> findExecutionIdsByTournamentId(Integer tournamentId);

    @Query(value = "select a1 from Execution a1 where a1.aggregateId = :aggregateId AND a1.state = 'ACTIVE' AND a1.version = (select max(a2.version) from Aggregate a2 where a2.aggregateId = :aggregateId)")
    Optional<Execution> findLastAggregateVersion(Integer aggregateId);
}
