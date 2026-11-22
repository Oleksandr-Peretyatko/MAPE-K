package org.anonymous.msrefactor.rt.execute.dto;

import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;

public record ExecuteResponse(
        String executeRefactorId,
        int versionNumber,
        String branchName,
        String operation,
        String targetService,
        String secondaryTarget,
        String snapshotId,
        String status,
        String imageTag,
        String gitCommitSha,
        String startedAt,
        String completedAt,
        String executeLog,
        String executedPlanVersionId,
        Integer executedVersionNumber,
        /** JSON array of the compose services this version deployed. */
        String deployedServices,
        /** Iteration created on success to measure the produced version; null unless DEPLOYED. */
        String nextIterationId
) {
    public static ExecuteResponse from(ExecuteRefactorEntity e) {
        return new ExecuteResponse(
                e.getExecuteRefactorId(),
                e.getVersionNumber(),
                e.getBranchName(),
                e.getOperation(),
                e.getTargetService(),
                e.getSecondaryTarget(),
                e.getSnapshotId(),
                e.getStatus().name(),
                e.getImageTag(),
                e.getGitCommitSha(),
                e.getStartedAt() != null ? e.getStartedAt().toString() : null,
                e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                e.getExecuteLog(),
                e.getExecutedPlanVersionId(),
                e.getExecutedVersionNumber(),
                e.getDeployedServices(),
                e.getNextIterationId()
        );
    }
}
