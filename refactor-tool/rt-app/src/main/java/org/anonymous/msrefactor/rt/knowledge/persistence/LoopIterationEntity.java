package org.anonymous.msrefactor.rt.knowledge.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One complete MAPE-K loop iteration.
 *
 * <p>Acts as the top-level entry point that ties together all four phases:
 * Monitor (snapshot), Analyze (analysis_result), Plan (plan_document), Execute (execute_refactor).
 * All cross-phase references are soft (string IDs, no FK constraints) so each phase can evolve
 * independently.
 */
@Entity
@Table(name = "loop_iteration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoopIterationEntity {

    @Id
    @Column(name = "iteration_id", length = 36)
    private String iterationId;

    /** Sequential human-readable number (1, 2, 3…). */
    @Column(name = "iteration_number", nullable = false)
    private int iterationNumber;

    /** Human-friendly label, e.g. "Iteration 1" or a user-supplied name. */
    @Column(name = "name", length = 120)
    private String name;

    /**
     * Lifecycle status driven by the highest completed phase:
     * CREATED → MONITORING → ANALYZED → PLANNED → EXECUTING → DEPLOYED | FAILED
     */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    /** Soft ref to monitor_snapshot.snapshot_id */
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    /** Soft ref to analysis_result.analysis_id */
    @Column(name = "analysis_id", length = 36)
    private String analysisId;

    /** Soft ref to plan_document.plan_id */
    @Column(name = "plan_id", length = 36)
    private String planId;

    /** Soft ref to execute_refactor.execute_refactor_id */
    @Column(name = "execute_refactor_id", length = 36)
    private String executeRefactorId;

    /** Soft ref to code_version.version_id — the version this iteration is analyzing/refactoring. */
    @Column(name = "code_version_id", length = 36)
    private String codeVersionId;

    /** Git branch for this iteration's Execute runs. Pinned on first creation, reused on retry. */
    @Column(name = "branch_name", length = 120)
    private String branchName;

    /**
     * The branch this iteration branched FROM — the fallback target when its build fails.
     * Derived from the code version being refactored, so recovery returns to the last known-good
     * code rather than to the configured base branch.
     */
    @Column(name = "parent_branch_name", length = 120)
    private String parentBranchName;

    /**
     * Outcome of this iteration's branch, independent of the phase {@link #status}:
     * CREATED → EXECUTING_REFACTOR → BUILD_SUCCESS | BUILD_FAILED.
     */
    @Column(name = "branch_status", length = 32)
    private String branchStatus;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * The architect constraints in force when this iteration finished, as a JSON array of
     * {@code ConstraintSnapshot}.
     *
     * <p>Distinct from {@code AnalysisResult.activeConstraints}, which freezes the same list at
     * *analysis* time: a restriction the Execute phase discovers is created after that snapshot was
     * taken, so only this one records what the iteration actually left behind.
     */
    @Column(name = "constraints_json", columnDefinition = "TEXT")
    private String constraintsJson;
}
