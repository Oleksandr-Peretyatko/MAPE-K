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

@Entity
@Table(name = "plan_document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDocumentEntity {

    @Id
    @Column(name = "plan_id", length = 36, nullable = false)
    private String planId;

    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @Column(name = "operation", length = 64)
    private String operation;

    @Column(name = "target_service", length = 64)
    private String targetService;

    @Column(name = "secondary_target", length = 64)
    private String secondaryTarget;

    @Column(name = "skill_invocation", columnDefinition = "TEXT")
    private String skillInvocation;

    @Column(name = "skill_prompt", columnDefinition = "TEXT")
    private String skillPrompt;

    @Column(name = "document", columnDefinition = "TEXT", nullable = false)
    private String document;

    @Column(name = "blueprint_payload", columnDefinition = "TEXT")
    private String blueprintPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "enriched_document", columnDefinition = "TEXT")
    private String enrichedDocument;

    @Column(name = "enriched_skill_invocation", columnDefinition = "TEXT")
    private String enrichedSkillInvocation;
}
