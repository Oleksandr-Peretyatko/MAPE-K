package org.anonymous.msrefactor.rt.analyze.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stores the user-assigned score (-2..+2) for a refactoring operation relative
 * to a quality attribute, scoped to a specific project.
 *
 * <p>Rows with {@code projectId = null} are global defaults. Project-specific rows
 * (where {@code projectId} matches {@code project_config.id}) override the defaults.
 * The service layer merges both when returning scores to callers.
 */
@Entity
@Table(name = "operation_qa_score",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_op_qa_project",
                columnNames = {"project_id", "operation", "qa_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationQaScoreEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    /** Null = global default; non-null = project-specific override. */
    @Column(name = "project_id", length = 36)
    private String projectId;

    /** OperationType.name() */
    @Column(name = "operation", length = 64, nullable = false)
    private String operation;

    /** FK to quality_attribute.id */
    @Column(name = "qa_id", length = 36, nullable = false)
    private String qaId;

    /** Score in [-2, +2]: negative = harms QA, 0 = neutral, positive = improves QA. */
    @Column(name = "score", nullable = false)
    private int score;
}
