package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of static structural analysis for a single version of the quizzes source tree.
 *
 * <p>Produced by {@code StaticAnalysisCollector.collectVersion()} and aggregated into a
 * {@link MonitorSnapshot} via {@code collectAll()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VersionAnalysisResult {

    /** Version identifier — the subdirectory name under the root dir (e.g., {@code "v1"}). */
    private String version;

    /** Absolute path to the {@code src/main/java} directory that was analysed. */
    private String path;

    /** M1/M2 structural metrics derived from the source tree at {@link #path}. */
    private StaticMetrics metrics;
}
