package org.anonymous.msrefactor.rt.plan.dto;

import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;

public record PlanResponse(
        String planId,
        String status,
        String operation,
        String targetService,
        /**
         * The second operand of a paired operation — the source for CREATE_REPLICA/REMOVE_REPLICA,
         * the dissolved aggregate for MERGE_AGGREGATES. Null for SPLIT_AGGREGATE.
         *
         * <p>Carried all the way to {@code ExecuteRefactorEntity}, where the stop-verdict scope
         * check needs it: without it a skill's correctly-scoped {@code NOT_APPLICABLE} verdict is
         * compared against a half-pair and rejected.
         */
        String secondaryTarget,
        String snapshotId,
        String skillInvocation,
        String createdAt,
        RefactorBlueprint blueprint,
        String skillPrompt,
        String document,
        String skillContent,
        String enrichedDocument,
        String enrichedSkillInvocation,
        /** The active (approved, else latest) plan version this response reflects. */
        String activePlanVersionId,
        Integer activeVersionNumber,
        /** Approval state of the active version: PENDING | APPROVED | REJECTED. */
        String approvalState
) {}
