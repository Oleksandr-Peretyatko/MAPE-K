package org.anonymous.msrefactor.rt.knowledge.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A named, stable state of the codebase on a git branch.
 *
 * <p>Version 0 (versionLabel="baseline") represents the original code before any refactoring.
 * Subsequent versions are created by successful Execute phase deployments.
 * Multiple LoopIterations can run against the same CodeVersion.
 */
@Entity
@Table(name = "code_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeVersionEntity {

    @Id
    @Column(name = "version_id", length = 36)
    private String versionId;

    /** Sequential integer: 0 = baseline, 1, 2, … */
    @Column(name = "version_number", nullable = false, unique = true)
    private int versionNumber;

    /** Label used with StaticAnalysisCollector: "baseline" (= "current"), "v1", "v2", … */
    @Column(name = "version_label", length = 64, nullable = false, unique = true)
    private String versionLabel;

    @Column(name = "branch_name", length = 120)
    private String branchName;

    @Column(name = "git_commit_sha", length = 40)
    private String gitCommitSha;

    /** e.g. "quizzes:v1" */
    @Column(name = "image_tag", length = 120)
    private String imageTag;

    /** BASELINE | ACTIVE | SUPERSEDED | FAILED */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** Soft ref to execute_refactor.execute_refactor_id that produced this version. Null for baseline. */
    @Column(name = "producing_execution_id", length = 36)
    private String producingExecutionId;

    /** Soft ref to code_version.version_id that this version was derived from. Null for baseline. */
    @Column(name = "parent_version_id", length = 36)
    private String parentVersionId;

    /**
     * CSV of the quality-priority names (highest first) the producing iteration's analysis ran
     * with — same format as {@code analysis_result.quality_priorities}. Null for the baseline
     * and for versions whose execution was not linked to an iteration.
     */
    @Column(name = "quality_priorities", length = 128)
    private String qualityPriorities;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Shell commands that deploy this version by hand, rendered by DeployCommandRenderer. */
    @Column(name = "deploy_command", columnDefinition = "TEXT")
    private String deployCommand;

    public static final String BASELINE   = "BASELINE";
    public static final String ACTIVE     = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String FAILED     = "FAILED";
}
