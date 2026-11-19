package org.anonymous.msrefactor.rt.execute.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scans the Docker build log after the test gate passes and reports which
 * operation-relevant test categories were observed. This is a post-hoc log
 * scanner — it does not re-run tests. A missing category appends a warning
 * but does not fail the pipeline.
 */
@Component
@Slf4j
public class BehavioralPreservationChecker {

    public record PreservationReport(boolean allLayersCovered, List<String> missingCategories) {
        public String toLogBlock() {
            StringBuilder sb = new StringBuilder("\n[COVERAGE]\n");
            if (allLayersCovered) {
                sb.append("  status: FULL\n");
            } else {
                sb.append("  status: PARTIAL — missing categories:\n");
                for (String m : missingCategories) {
                    sb.append("    - ").append(m).append("\n");
                }
            }
            return sb.toString();
        }
    }

    private record RequiredPattern(String category, Pattern pattern) {}

    // Patterns derived from actual test class names in the quizzes test suite.
    private static final Map<String, List<RequiredPattern>> OPERATION_PATTERNS = Map.ofEntries(
        Map.entry("MERGE_AGGREGATES", List.of(
            new RequiredPattern("sagas:any", Pattern.compile(".*Test"))
        )),
        Map.entry("SPLIT_AGGREGATE", List.of(
            new RequiredPattern("sagas:any", Pattern.compile(".*Test"))
        )),
        Map.entry("CREATE_REPLICA", List.of(
            new RequiredPattern("sagas:UpdateStudentName", Pattern.compile("AddParticipantAndUpdateStudentName.*Test"))
        )),
        Map.entry("REMOVE_REPLICA", List.of(
            new RequiredPattern("sagas:any", Pattern.compile(".*Test"))
        ))
    );

    /**
     * Checks coverage for a given operation against the captured sagas build log.
     * Returns a report with any missing test categories.
     *
     * @param operation  the refactoring operation type (e.g., "MERGE_AGGREGATES")
     * @param sagasLog   stdout/stderr from the sagas Docker build
     */
    public PreservationReport check(String operation, String sagasLog) {
        String normalized = operation == null ? "" : operation.toUpperCase();
        List<RequiredPattern> required = OPERATION_PATTERNS.get(normalized);

        if (required == null) {
            log.debug("[preservation] no pattern map for operation '{}' — skipping coverage check", operation);
            return new PreservationReport(true, List.of());
        }

        List<String> missing = new ArrayList<>();
        for (RequiredPattern rp : required) {
            if (sagasLog == null || !rp.pattern().matcher(sagasLog).find()) {
                missing.add(rp.category());
            }
        }

        boolean allCovered = missing.isEmpty();
        if (!allCovered) {
            log.warn("[preservation] operation '{}' missing test coverage categories: {}", operation, missing);
        } else {
            log.info("[preservation] operation '{}' — all expected test categories observed", operation);
        }
        return new PreservationReport(allCovered, missing);
    }
}
