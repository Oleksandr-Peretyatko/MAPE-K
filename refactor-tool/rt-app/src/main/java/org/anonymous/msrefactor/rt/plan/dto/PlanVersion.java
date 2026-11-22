package org.anonymous.msrefactor.rt.plan.dto;

/**
 * A full immutable plan revision returned to the UI (version switcher / detail view).
 */
public record PlanVersion(
        String planVersionId,
        String planId,
        int versionNumber,
        String kind,
        String skillInvocation,
        String document,
        String model,
        String promptVersion,
        String approvalState,
        String note,
        String createdAt,
        String iterationId,
        String snapshotId
) {}
