package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable snapshot of a {@code RefactorConstraintEntity} captured at analysis time.
 * Stored inside {@link AnalysisResult#getActiveConstraints()} so per-iteration exports
 * always include the constraints that were active when the analysis ran, even if the
 * live constraint list is later modified or deleted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConstraintSnapshot {

    private String id;

    /** Operation type this rule targets; null = wildcard (matches any operation). */
    private String operation;

    /** Primary target service; null = wildcard. */
    private String targetService;

    /** Secondary target service (e.g., merge partner); null = wildcard. */
    private String secondaryTarget;

    /** Specific entity class name; null = wildcard. */
    private String targetEntity;

    /** Human-readable reason displayed in the UI and included in candidate annotations. */
    private String reason;

    /** True = candidate is moved to the blocked section; false = warning badge only. */
    private boolean blocking;

    /** When this constraint was originally created. */
    private Instant createdAt;
}
