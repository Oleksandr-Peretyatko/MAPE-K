package org.anonymous.msrefactor.rt.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The parent branch is what recovery falls back to, so resolving it wrongly would send a failed
 * iteration to the wrong code.
 */
class LoopIterationBranchTest {

    // Deliberately not the production default: the base branch is configuration, and nothing in the
    // resolution path may assume a particular branch name.
    private static final String BASE_BRANCH = "some/other-base";

    private LoopIterationRepository repository;
    private CodeVersionService codeVersionService;
    private LoopIterationService service;

    @BeforeEach
    void setUp() {
        repository = mock(LoopIterationRepository.class);
        codeVersionService = mock(CodeVersionService.class);
        var executeConfig = new org.anonymous.msrefactor.rt.execute.config.ExecuteConfig();
        executeConfig.setBaseBranch(BASE_BRANCH);
        service = new LoopIterationService(
                repository, mock(org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotRepository.class),
                mock(org.anonymous.msrefactor.rt.plan.persistence.PlanDocumentRepository.class),
                mock(org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository.class),
                mock(org.anonymous.msrefactor.rt.monitor.service.MonitorService.class),
                mock(org.anonymous.msrefactor.rt.analyze.service.AnalyzeService.class),
                mock(org.anonymous.msrefactor.rt.plan.service.PlanService.class),
                codeVersionService,
                mock(org.anonymous.msrefactor.rt.analyze.service.ConstraintService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                executeConfig);
        when(repository.save(any(LoopIterationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private LoopIterationEntity iteration(String id, String codeVersionId) {
        return LoopIterationEntity.builder()
                .iterationId(id).iterationNumber(4).name("Iteration 4")
                .status(LoopIterationService.CREATED).codeVersionId(codeVersionId)
                .startedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("the parent is the branch of the code version being refactored")
    void parentComesFromTheCodeVersion() {
        LoopIterationEntity it = iteration("iter-4", "ver-3");
        when(repository.findById("iter-4")).thenReturn(Optional.of(it));
        when(codeVersionService.findById("ver-3")).thenReturn(Optional.of(
                CodeVersionEntity.builder().versionId("ver-3").branchName("refactor/v3-iter-3").build()));

        var assignment = service.ensureBranch("iter-4", 4);

        assertThat(assignment.branchName()).isEqualTo("refactor/v4-iter-4");
        assertThat(assignment.parentBranchName()).isEqualTo("refactor/v3-iter-3");
        assertThat(it.getBranchStatus()).isEqualTo(LoopIterationService.BRANCH_CREATED);
        assertThat(it.getParentBranchName()).isEqualTo("refactor/v3-iter-3");
    }

    @Test
    @DisplayName("an iteration on the baseline falls back to the configured base branch")
    void baselineFallsBackToBaseBranch() {
        LoopIterationEntity it = iteration("iter-1", "ver-0");
        when(repository.findById("iter-1")).thenReturn(Optional.of(it));
        // The baseline carries no branch of its own.
        when(codeVersionService.findById("ver-0")).thenReturn(Optional.of(
                CodeVersionEntity.builder().versionId("ver-0").branchName(null).build()));

        var assignment = service.ensureBranch("iter-1", 1);

        assertThat(assignment.parentBranchName()).isEqualTo(BASE_BRANCH);
    }

    @Test
    @DisplayName("an iteration with no code version falls back to the base branch")
    void noCodeVersionFallsBackToBaseBranch() {
        LoopIterationEntity it = iteration("iter-x", null);
        when(repository.findById("iter-x")).thenReturn(Optional.of(it));

        assertThat(service.ensureBranch("iter-x", 2).parentBranchName()).isEqualTo(BASE_BRANCH);
        verify(codeVersionService, never()).findById(any());
    }

    @Test
    @DisplayName("a retry reuses the pinned branch and does not re-derive the parent")
    void retryReusesPinnedBranch() {
        LoopIterationEntity it = iteration("iter-4", "ver-3");
        it.setBranchName("refactor/v4-iter-4");
        it.setParentBranchName("refactor/v3-iter-3");
        when(repository.findById("iter-4")).thenReturn(Optional.of(it));

        // Version number differs: a retry runs as a later version but must keep the same branch,
        // otherwise one iteration would accumulate refactor/v4-…, refactor/v5-… branches.
        var assignment = service.ensureBranch("iter-4", 7);

        assertThat(assignment.branchName()).isEqualTo("refactor/v4-iter-4");
        assertThat(assignment.parentBranchName()).isEqualTo("refactor/v3-iter-3");
        verify(codeVersionService, never()).findById(any());
    }

    @Test
    @DisplayName("a run without an iteration gets a throwaway branch and persists nothing")
    void runWithoutIterationPersistsNothing() {
        var assignment = service.ensureBranch(null, 9);

        assertThat(assignment.branchName()).startsWith("refactor/v9-");
        assertThat(assignment.parentBranchName()).isEqualTo(BASE_BRANCH);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("an unknown iteration id still yields a usable branch")
    void unknownIterationStillResolves() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        var assignment = service.ensureBranch("ghost", 3);

        assertThat(assignment.branchName()).isEqualTo("refactor/v3-ghost");
        assertThat(assignment.parentBranchName()).isEqualTo(BASE_BRANCH);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("terminal status is set directly, since DEPLOYED and FAILED share a rank")
    void terminalStatusIsSetDirectly() {
        LoopIterationEntity it = iteration("iter-4", "ver-3");
        it.setStatus(LoopIterationService.EXECUTING);
        when(repository.findById("iter-4")).thenReturn(Optional.of(it));

        service.updateFromExecuteStatus("iter-4", "FAILED");
        assertThat(it.getStatus()).isEqualTo(LoopIterationService.FAILED);
        assertThat(it.getCompletedAt()).isNotNull();

        it.setStatus(LoopIterationService.EXECUTING);
        service.updateFromExecuteStatus("iter-4", "DEPLOYED");
        assertThat(it.getStatus()).isEqualTo(LoopIterationService.DEPLOYED);
    }

    @Test
    @DisplayName("the build-failure reason is appended to the iteration's notes")
    void recordsBuildFailureReason() {
        LoopIterationEntity it = iteration("iter-4", "ver-3");
        when(repository.findById("iter-4")).thenReturn(Optional.of(it));

        service.recordBuildFailure("iter-4", "sagas gate failed");

        assertThat(it.getNotes()).contains("[build_failed]").contains("sagas gate failed");
    }

    @Test
    @DisplayName("branch/iteration writes are no-ops when there is no iteration")
    void noIterationIsAlwaysSafe() {
        service.markBranchStatus(null, LoopIterationService.BRANCH_BUILD_FAILED);
        service.recordBuildFailure(null, "boom");
        service.updateFromExecuteStatus(null, "FAILED");

        verifyNoInteractions(repository);
    }
}
