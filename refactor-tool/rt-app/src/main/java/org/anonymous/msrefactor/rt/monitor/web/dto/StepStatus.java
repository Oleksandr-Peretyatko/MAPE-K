package org.anonymous.msrefactor.rt.monitor.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Status of a single scenario step within a Locust collect.sh run.
 * Derived by scanning the merged log for step-marker lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepStatus {
    /** Short machine-readable key, e.g. {@code "teacherSetup"}. */
    private String id;

    /** Human-readable label shown in the UI, e.g. {@code "Teacher Course Setup"}. */
    private String label;

    /**
     * Substring that appears in the collected stdout when this step begins.
     * Must match the {@code echo} line in collect.sh exactly (without surrounding whitespace).
     */
    private String marker;

    /** One of: {@code PENDING}, {@code RUNNING}, {@code DONE}, {@code FAILED}. */
    private String status;
}
