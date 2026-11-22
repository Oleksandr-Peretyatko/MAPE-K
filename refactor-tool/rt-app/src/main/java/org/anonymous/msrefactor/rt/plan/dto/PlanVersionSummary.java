package org.anonymous.msrefactor.rt.plan.dto;

/**
 * Lightweight plan-version row for the version switcher list.
 */
public record PlanVersionSummary(
        String planVersionId,
        int versionNumber,
        String kind,
        String model,
        String approvalState,
        String createdAt
) {}
