package org.anonymous.msrefactor.rt.monitor.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Status DTO returned by {@code GET /monitor/collect/locust/status}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocustRunStatus {
    /** One of: IDLE, RUNNING, DONE, FAILED */
    private String  status;
    private Instant startedAt;
    private Instant finishedAt;
    /** Null while status is RUNNING. */
    private Integer exitCode;
    /** Captured stdout+stderr (last ~200 lines). */
    private String  log;
    /** Per-scenario step statuses derived from the log (7 entries). */
    private List<StepStatus> steps;
}
