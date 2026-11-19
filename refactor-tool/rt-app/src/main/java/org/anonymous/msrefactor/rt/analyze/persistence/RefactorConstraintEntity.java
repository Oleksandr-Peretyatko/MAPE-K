package org.anonymous.msrefactor.rt.analyze.persistence;

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
 * Architect-defined constraint that restricts which refactoring operations are
 * permitted on specific services or service pairs.
 *
 * <p>Stored once per rule. Null fields act as wildcards (match any value).
 * A blocking constraint moves a candidate to a "blocked" section in the UI;
 * a non-blocking constraint adds a warning label without changing rank.
 */
@Entity
@Table(name = "refactor_constraint")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefactorConstraintEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    /**
     * Application this rule belongs to — {@code AppTargetConfig.getName()}, e.g. {@code "quizzes"}.
     * Null = a global rule that applies to every project, mirroring {@code OperationQaScoreEntity}.
     */
    @Column(name = "project_id", length = 36)
    private String projectId;

    /** Operation type this rule applies to. Null = matches any operation. */
    @Column(name = "operation", length = 64)
    private String operation;

    /** Primary target service. Null = matches any service. */
    @Column(name = "target_service", length = 64)
    private String targetService;

    /** Secondary target (e.g., the other side of a merge). Null = matches any. */
    @Column(name = "secondary_target", length = 64)
    private String secondaryTarget;

    /**
     * Entity class name this rule applies to (e.g., {@code "AnsweredQuiz"}).
     * Null = matches any entity (wildcard). Matched against the candidate's
     * {@code affectedEntities} list (case-insensitive).
     */
    @Column(name = "target_entity", length = 128)
    private String targetEntity;

    /** Human-readable explanation displayed in the UI alongside the blocked/warned candidate. */
    @Column(name = "reason", length = 512, nullable = false)
    private String reason;

    /**
     * When true the candidate is moved out of the active ranking into a "blocked" section.
     * When false the candidate keeps its rank but receives a warning badge.
     */
    @Column(name = "blocking", nullable = false)
    private boolean blocking;

    /**
     * For a non-blocking constraint recorded by a case-4 (sibling) merge: the sequence of operations
     * that would make this pair mergeable, e.g. {@code "course<-topic, course<-question"}.
     *
     * <p>Null for architect-authored rules and for true refusals. When present, Analyze copies it
     * onto the matching candidate's {@code dependencies}, which the plan document and the candidate
     * list already render — so the loop shows a path forward instead of a dead end.
     */
    @Column(name = "prerequisite_chain", length = 512)
    private String prerequisiteChain;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
