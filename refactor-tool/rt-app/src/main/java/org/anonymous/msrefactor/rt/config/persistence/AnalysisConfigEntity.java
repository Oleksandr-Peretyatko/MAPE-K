package org.anonymous.msrefactor.rt.config.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Singleton JPA entity (always id=1) that persists the Analyze-phase configuration.
 *
 * <p>The JSON columns each map to a field of {@link org.anonymous.msrefactor.rt.analyze.AnalysisConfig}.
 * They are serialised/deserialised by {@link org.anonymous.msrefactor.rt.config.service.AnalysisConfigService}.
 */
@Entity
@Table(name = "analysis_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisConfigEntity {

    /** Always 1 — there is exactly one analysis config row. */
    @Id
    @Column(name = "id")
    private Integer id;

    /** JSON-serialised {@code Map<HotspotType, List<OperationType>>}. */
    @Column(name = "hotspot_rules_json", columnDefinition = "TEXT", nullable = false)
    private String hotspotRulesJson;

    /** JSON-serialised {@code Map<QualityAttribute, List<HotspotType>>} — QA → hotspot routing. */
    @Column(name = "qa_to_hotspot_types_json", columnDefinition = "TEXT")
    private String qaToHotspotTypesJson;

    /** JSON-serialised {@code List<String>} — ordered default QA priorities; null → built-in defaults. */
    @Column(name = "quality_priorities_json", columnDefinition = "TEXT")
    private String qualityPrioritiesJson;

    /** Timestamp of the last configuration update. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
