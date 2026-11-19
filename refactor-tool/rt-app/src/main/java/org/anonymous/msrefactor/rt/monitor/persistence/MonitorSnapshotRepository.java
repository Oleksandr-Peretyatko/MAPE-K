package org.anonymous.msrefactor.rt.monitor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MonitorSnapshotRepository extends JpaRepository<MonitorSnapshotEntity, String> {

    /** Returns the most recently collected snapshot. */
    @Query("SELECT e FROM MonitorSnapshotEntity e ORDER BY e.collectedAt DESC LIMIT 1")
    Optional<MonitorSnapshotEntity> findLatest();

    /** Returns all snapshots ordered by collection time descending (lightweight — no payload). */
    @Query("SELECT new MonitorSnapshotEntity(e.snapshotId, e.collectedAt, '') " +
           "FROM MonitorSnapshotEntity e ORDER BY e.collectedAt DESC")
    List<MonitorSnapshotEntity> findAllSummaries();
}
