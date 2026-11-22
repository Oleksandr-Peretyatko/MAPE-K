package org.anonymous.msrefactor.rt.monitor.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Singleton JPA entity (always id=1) that persists monitor configuration across restarts.
 * Currently tracks {@code switchedAt} so file-based collectors correctly ignore
 * result files produced before the last project switch even after an app restart.
 */
@Entity
@Table(name = "project_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectConfigEntity {

    /** Always 1 — there is exactly one project config row. */
    @Id
    @Column(name = "id")
    private Integer id;

    /** Instant of the last project switch; Instant.EPOCH means no switch has been recorded. */
    @Column(name = "switched_at", nullable = false)
    private Instant switchedAt;

    /** JSON-serialised {@code HotspotThresholds}; null means use defaults. */
    @Column(name = "thresholds_json", columnDefinition = "TEXT")
    private String thresholdsJson;

    /**
     * When true (default) the candidate generators select the strongest operand that complies
     * with architect constraints, instead of selecting blindly and demoting at Stage 9.
     * Null in rows predating this column → treated as true.
     */
    @Column(name = "constraint_aware_selection")
    private Boolean constraintAwareSelection;
}
