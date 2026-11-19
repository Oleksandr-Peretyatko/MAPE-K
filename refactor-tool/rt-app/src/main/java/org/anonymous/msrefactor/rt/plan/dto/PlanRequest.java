package org.anonymous.msrefactor.rt.plan.dto;

import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;

import java.util.List;

public record PlanRequest(
        String operation,
        String targetService,
        String secondaryTarget,
        String snapshotId,
        List<String> qualityPriorities,
        boolean preconditionsVerified,
        boolean risksAcknowledged,
        String skillInvocation,
        RefactorBlueprint blueprint,
        String skillPrompt,
        /** Optional: LoopIteration to link this plan to after generation. */
        String iterationId
) {}
