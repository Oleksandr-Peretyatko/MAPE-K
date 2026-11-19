package org.anonymous.msrefactor.rt.quality;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Quality attribute scores computed from a single monitor snapshot. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QualityScoreResult {

    private String snapshotId;

    /** Version label (e.g., "v1", "current"). */
    private String version;

    /** ISO-8601 timestamp of when these scores were calculated. */
    private String generatedAt;

    /** Pipeline status of the snapshot when the scores were computed; null for pre-pipeline snapshots. */
    private String pipelineStatus;

    private List<QualityAttributeScore> scores;
}
