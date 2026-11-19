package org.anonymous.msrefactor.rt.monitor.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity for persisting a {@link org.anonymous.msrefactor.rt.monitor.MonitorSnapshot}.
 *
 * <p>The full snapshot is stored as a JSON string in the {@code payload} column so we avoid
 * complex ORM mapping and can evolve the snapshot model without schema migrations.
 */
@Entity
@Table(name = "monitor_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    /** Full {@link org.anonymous.msrefactor.rt.monitor.MonitorSnapshot} serialized as JSON. */
    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;
}
