package org.anonymous.msrefactor.rt.execute.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ExecuteRefactorRepository extends JpaRepository<ExecuteRefactorEntity, String> {

    @Query("SELECT COALESCE(MAX(r.versionNumber), 0) FROM ExecuteRefactorEntity r")
    int findMaxVersionNumber();

    Optional<ExecuteRefactorEntity> findTopByOrderByVersionNumberDesc();

    Optional<ExecuteRefactorEntity> findTopByStatusOrderByVersionNumberDesc(ExecuteStatus status);

    /** The newest successfully deployed run before {@code versionNumber} — the rollback target. */
    Optional<ExecuteRefactorEntity> findTopByStatusAndVersionNumberLessThanOrderByVersionNumberDesc(
            ExecuteStatus status, int versionNumber);

    boolean existsByStatusIn(List<ExecuteStatus> statuses);

    List<ExecuteRefactorEntity> findAllByStatusIn(List<ExecuteStatus> statuses);

    List<ExecuteRefactorEntity> findAllByOrderByStartedAtAsc();
}
