package org.anonymous.msrefactor.rt.execute.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "execute_refactor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteRefactorEntity {

    @Id
    @Column(name = "execute_refactor_id", length = 36)
    private String executeRefactorId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "branch_name", length = 120)
    private String branchName;

    /**
     * Branch this run branched FROM, and the branch its recovery returns to when it fails.
     * Resolved once at start so the async pipeline never has to re-derive it.
     */
    @Column(name = "parent_branch_name", length = 120)
    private String parentBranchName;

    @Column(name = "operation", length = 64)
    private String operation;

    @Column(name = "target_service", length = 64)
    private String targetService;

    @Column(name = "secondary_target", length = 64)
    private String secondaryTarget;

    @Column(name = "plan_payload", columnDefinition = "TEXT")
    private String planPayload;

    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    /**
     * Soft ref to loop_iteration.iteration_id — the iteration this run belongs to.
     *
     * <p>The iteration→run link alone is not enough: the pipeline runs async with only its own id,
     * so without this it cannot report its outcome back to the iteration. Null for test runs and
     * direct API calls made outside an iteration.
     */
    @Column(name = "iteration_id", length = 36)
    private String iterationId;

    /**
     * Soft ref to the loop_iteration created to measure the version this run produced.
     *
     * <p>Null until the run reaches DEPLOYED. Persisted rather than derived so the chain survives
     * a page reload and so a re-run of the same row cannot fork a second follow-up iteration.
     */
    @Column(name = "next_iteration_id", length = 36)
    private String nextIterationId;

    /**
     * Soft ref to code_version.version_id this run branched from — the version being refactored.
     * Becomes the new version's parent, so refactorings compose into evolution trajectories.
     * Null for legacy rows, which fall back to the current ACTIVE version.
     */
    @Column(name = "source_version_id", length = 36)
    private String sourceVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ExecuteStatus status;

    @Column(name = "execute_log", columnDefinition = "TEXT")
    private String executeLog;

    @Column(name = "git_commit_sha", length = 40)
    private String gitCommitSha;

    @Column(name = "image_tag", length = 120)
    private String imageTag;

    /**
     * JSON array of the compose service names this version deployed, resolved from the compose
     * file after the mutation. Recorded so the topology of each version is auditable and so a
     * later run can degrade to the last known-good list when compose discovery fails.
     * Null for rows written before topology discovery existed.
     */
    @Column(name = "deployed_services", columnDefinition = "TEXT")
    private String deployedServices;

    /** Full DeployTopology JSON — container names, ports, Jaeger names, discovery source. */
    @Column(name = "deployed_topology", columnDefinition = "TEXT")
    private String deployedTopology;

    /** The plan version that was executed (approved, else latest). Null for legacy/test runs. */
    @Column(name = "executed_plan_version_id", length = 36)
    private String executedPlanVersionId;

    /** The version_number of the executed plan version. */
    @Column(name = "executed_version_number")
    private Integer executedVersionNumber;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
