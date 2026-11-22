package org.anonymous.msrefactor.rt.analyze.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "analysis_result")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResultEntity {

    @Id
    @Column(name = "analysis_id", length = 36)
    private String analysisId;

    /** Soft reference to the monitor_snapshot that was analysed. */
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    /** Comma-separated quality priorities used (e.g. "PERFORMANCE,CONSISTENCY,MAINTAINABILITY"). */
    @Column(name = "quality_priorities", length = 128)
    private String qualityPriorities;

    /** Full AnalysisResult serialised as JSON. */
    @Column(name = "result_json", columnDefinition = "TEXT", nullable = false)
    private String resultJson;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;
}
