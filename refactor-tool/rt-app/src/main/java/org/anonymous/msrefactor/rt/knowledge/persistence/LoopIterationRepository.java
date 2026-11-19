package org.anonymous.msrefactor.rt.knowledge.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LoopIterationRepository extends JpaRepository<LoopIterationEntity, String> {

    List<LoopIterationEntity> findAllByOrderByIterationNumberAsc();

    @Query("SELECT COALESCE(MAX(l.iterationNumber), 0) FROM LoopIterationEntity l")
    int findMaxIterationNumber();

    /** Checks if any iteration already references this snapshot. */
    boolean existsBySnapshotId(String snapshotId);

    /** Checks if any iteration already references this plan. */
    boolean existsByPlanId(String planId);

    /** Checks if any iteration already references this execute-refactor record. */
    boolean existsByExecuteRefactorId(String executeRefactorId);

    List<LoopIterationEntity> findAllByCodeVersionIdOrderByIterationNumberAsc(String codeVersionId);
}
