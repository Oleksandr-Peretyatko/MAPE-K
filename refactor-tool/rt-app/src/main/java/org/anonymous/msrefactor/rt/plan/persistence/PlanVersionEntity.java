package org.anonymous.msrefactor.rt.plan.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * An immutable revision of a plan. Each {@code generate} seeds v1 ({@code ORIGINAL}); each
 * {@code enrich} appends an {@code ENRICHED} version; each manual save appends a {@code USER_EDIT}
 * version. Rows are never overwritten — history is preserved. Exactly one version per plan may be
 * {@code APPROVED}; the approved (else latest) version is what the Execute phase runs.
 */
@Entity
@Table(name = "plan_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanVersionEntity {

    @Id
    @Column(name = "plan_version_id", length = 36)
    private String planVersionId;

    /** The logical plan group this version belongs to (→ plan_document.plan_id). */
    @Column(name = "plan_id", length = 36, nullable = false)
    private String planId;

    /** Sequential revision number within the plan (1, 2, 3…). */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    // ─── Traceability refs (copied from plan_document at creation) ──────────────

    @Column(name = "iteration_id", length = 36)
    private String iterationId;

    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    /** ORIGINAL | ENRICHED | USER_EDIT. */
    @Column(name = "kind", length = 24, nullable = false)
    private String kind;

    /** The skill invocation this version would execute. */
    @Column(name = "skill_invocation", columnDefinition = "TEXT")
    private String skillInvocation;

    /** The plan document content at this version (immutable). */
    @Column(name = "document", columnDefinition = "TEXT", nullable = false)
    private String document;

    /** LLM model that produced this version (null/"deterministic" for ORIGINAL, "user" for USER_EDIT). */
    @Column(name = "model", length = 64)
    private String model;

    /** Prompt/skill version that produced this version. */
    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    /** PENDING | APPROVED | REJECTED. */
    @Column(name = "approval_state", length = 16, nullable = false)
    private String approvalState;

    /** Optional edit summary or approval note. */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
