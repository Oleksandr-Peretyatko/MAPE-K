package org.anonymous.msrefactor.rt.knowledge.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "discovery_graph")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveryGraphEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Soft reference to the monitor_snapshot this discovery is linked to. */
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    /** Version label analysed (e.g. "current", "v1"). */
    @Column(name = "version", length = 32)
    private String version;

    /** Full DiscoveryGraph serialised as JSON. */
    @Column(name = "graph_json", columnDefinition = "TEXT")
    private String graphJson;

    /** Raw Claude stdout (for debugging). */
    @Column(name = "raw_output", columnDefinition = "TEXT")
    private String rawOutput;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    /** Soft ref to code_version.version_id — preferred linkage going forward. */
    @Column(name = "code_version_id", length = 36)
    private String codeVersionId;
}
