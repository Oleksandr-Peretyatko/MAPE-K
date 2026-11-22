package org.anonymous.msrefactor.rt.plan.dto;

public record PlanSummary(
        String planId,
        String operation,
        String targetService,
        String secondaryTarget,
        String snapshotId,
        String status,
        String createdAt,
        /** The canonical CLI command — never mutated after plan generation. */
        String skillInvocation
) {}
