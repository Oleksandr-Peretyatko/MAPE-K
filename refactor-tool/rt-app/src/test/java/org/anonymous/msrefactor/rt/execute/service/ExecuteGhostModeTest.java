package org.anonymous.msrefactor.rt.execute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteStatus;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.plan.dto.PlanResponse;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.TopologyConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Ghost mode exists so frontend work does not have to pay for a real iteration, which means its
 * whole contract is about what it must <b>not</b> do: no mutator, no test gate, no image, no
 * deploy. The tests below pin that, plus the one thing it does produce — a CLAUDE.md refactor-log
 * entry whose {@code Result} is the operator's verdict, written only after that verdict is in.
 */
class ExecuteGhostModeTest {

    private static final String PARENT = "refactor/v3-iter-3";

    @TempDir Path projectRoot;

    private StubProcessRunner runner;
    private ExecuteRefactorRepository executeRepo;
    private LoopIterationService loopIterationService;
    private CodeVersionService codeVersionService;
    private ClaudeCommandMutator commandMutator;
    private AppTargetConfig appTarget;
    private ExecuteConfig config;
    private ExecuteService service;

    @BeforeEach
    void setUp() throws Exception {
        runner = new StubProcessRunner();
        executeRepo = mock(ExecuteRefactorRepository.class);
        loopIterationService = mock(LoopIterationService.class);
        codeVersionService = mock(CodeVersionService.class);
        commandMutator = mock(ClaudeCommandMutator.class);
        appTarget = new AppTargetConfig();

        // The ghost log entry is appended to a real file, so the target module must exist on disk.
        Files.createDirectories(Path.of(appTarget.absoluteDir(projectRoot.toString())));

        config = new ExecuteConfig();
        config.setProjectRoot(projectRoot.toString());
        config.setBaseBranch("tool/self-refactor");
        config.setGhostMode(true);

        service = new ExecuteService(
                executeRepo,
                mock(org.anonymous.msrefactor.rt.plan.service.PlanService.class),
                runner, config, appTarget, new ObjectMapper(),
                mock(BehavioralPreservationChecker.class),
                commandMutator,
                codeVersionService,
                mock(org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository.class),
                loopIterationService,
                mock(ComposeTopologyResolver.class), new TopologyConfig(),
                mock(org.anonymous.msrefactor.rt.analyze.service.ConstraintService.class),
                mock(org.anonymous.msrefactor.rt.topology.HostPortInspector.class));
    }

    private PlanResponse plan() {
        return new PlanResponse("plan-1", "READY", "MERGE_AGGREGATES", "question-service",
                "course-service",
                null, "refactor-merge-aggregate question-service course-service",
                Instant.now().toString(), null, null, null, null, null, null,
                null, null, "APPROVED");
    }

    private ExecuteRefactorEntity parked() {
        ExecuteRefactorEntity exec = ExecuteRefactorEntity.builder()
                .executeRefactorId("exec-4").versionNumber(4)
                .branchName("refactor/v4-iter-4").parentBranchName(PARENT)
                .iterationId("iter-4")
                .operation("MERGE_AGGREGATES").targetService("question-service")
                .planPayload(toJson(plan()))
                .status(ExecuteStatus.AWAITING_VERDICT).executeLog("")
                .startedAt(Instant.now()).build();
        when(executeRepo.findById("exec-4")).thenReturn(Optional.of(exec));
        return exec;
    }

    private String toJson(PlanResponse plan) {
        try { return new ObjectMapper().writeValueAsString(plan); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String claudeMd() throws Exception {
        Path p = Path.of(appTarget.absoluteClaudeMd(projectRoot.toString()));
        return Files.exists(p) ? Files.readString(p) : "";
    }

    // ─── The pipeline stops after the branch ─────────────────────────────────

    @Test
    @DisplayName("a ghost run branches, parks at AWAITING_VERDICT, and runs no mutator or docker")
    void parksAfterBranchingWithoutMutatingOrDeploying() throws Exception {
        ExecuteRefactorEntity exec = parked();
        exec.setStatus(ExecuteStatus.PENDING);

        service.runPipeline("exec-4", plan());

        assertThat(exec.getStatus()).isEqualTo(ExecuteStatus.AWAITING_VERDICT);
        assertThat(runner.indexOf("git", "checkout", "-B")).as("the branch is still created")
                .isNotNegative();
        // The point of the mode: everything expensive is skipped.
        assertThat(runner.commandsStartingWith("docker"))
                .as("no test gate, no image build, no deploy").isEmpty();
        verify(commandMutator, never()).apply(anyString(), anyString(), anyString(), anyString(),
                any(), anyInt());
        // The verdict decides the log entry's Result, so nothing may be written before it.
        assertThat(claudeMd()).as("the log entry waits for the verdict").isEmpty();
        assertThat(runner.indexOf("git", "commit")).as("nothing to commit yet").isNegative();
    }

    @Test
    @DisplayName("with ghost mode off the pipeline proceeds past the branch as usual")
    void offByDefaultLeavesThePipelineAlone() throws Exception {
        config.setGhostMode(false);
        ExecuteRefactorEntity exec = parked();
        exec.setStatus(ExecuteStatus.PENDING);

        service.runPipeline("exec-4", plan());

        assertThat(exec.getStatus()).isNotEqualTo(ExecuteStatus.AWAITING_VERDICT);
        verify(commandMutator).apply(anyString(), anyString(), any(), anyString(), any(), anyInt());
    }

    // ─── The verdict writes the entry it decides ─────────────────────────────

    @Test
    @DisplayName("SUCCESS writes Result: CHANGED, commits it, and records the version")
    void successWritesChangedAndRecordsTheVersion() throws Exception {
        ExecuteRefactorEntity exec = parked();
        when(codeVersionService.createFromExecution(any())).thenReturn(
                CodeVersionService.CodeVersionSummary.builder()
                        .versionId("ver-4").versionNumber(4).versionLabel("v4").build());
        when(loopIterationService.create(any(), anyString())).thenReturn(
                LoopIterationService.LoopIterationSummary.builder()
                        .iterationId("iter-5").iterationNumber(5).build());

        service.recordVerdict("exec-4", true, "merged question into course");

        assertThat(claudeMd())
                .contains("<!-- refactor-log -->")
                .contains("Result: CHANGED")
                .contains("Skill: refactor-merge-aggregate")
                .contains("Summary: [ghost] merged question into course")
                .contains("<!-- end-refactor-log -->")
                // Would otherwise teach Analyze that a real architectural stop was hit.
                .doesNotContain("REFACTOR_RESTRICTION");
        assertThat(runner.indexOf("git", "commit")).isNotNegative();
        assertThat(exec.getStatus()).isEqualTo(ExecuteStatus.DEPLOYED);
        assertThat(exec.getCompletedAt()).isNotNull();
        // No image was built, so the version must not claim one.
        assertThat(exec.getImageTag()).isNull();
        verify(loopIterationService).markBranchStatus("iter-4", LoopIterationService.BRANCH_BUILD_SUCCESS);
        verify(loopIterationService).updateFromExecuteStatus("iter-4", "DEPLOYED");
        verify(codeVersionService).createFromExecution(exec);
        assertThat(exec.getNextIterationId()).isEqualTo("iter-5");
        assertThat(runner.commandsStartingWith("docker")).as("still no docker work").isEmpty();
    }

    @Test
    @DisplayName("FAILED writes Result: NO_CHANGE, fails the iteration, and leaves docker alone")
    void failureWritesNoChangeAndRecovers() throws Exception {
        ExecuteRefactorEntity exec = parked();

        service.recordVerdict("exec-4", false, null);

        assertThat(claudeMd())
                .contains("Result: NO_CHANGE")
                .contains("Summary: [ghost] ");
        assertThat(exec.getStatus()).isEqualTo(ExecuteStatus.FAILED);
        verify(loopIterationService).updateFromExecuteStatus("iter-4", "FAILED");
        verify(loopIterationService).markBranchStatus("iter-4", LoopIterationService.BRANCH_BUILD_FAILED);
        // Recovery returns to the parent, and — because a ghost run never tore the stack down —
        // must not rebuild or redeploy anything.
        assertThat(runner.first("git", "checkout", "--force")).contains(PARENT);
        assertThat(runner.commandsStartingWith("docker"))
                .as("the parent's containers were never disturbed").isEmpty();
        verify(codeVersionService, never()).createFromExecution(any());
    }

    @Test
    @DisplayName("the committed entry keeps the branch, so the verdict stays inspectable")
    void failedGhostRunKeepsTheBranchItCommittedTo() {
        parked();

        service.recordVerdict("exec-4", false, null);

        assertThat(runner.indexOf("git", "branch", "-D"))
                .as("the branch carries the log commit and must survive").isNegative();
    }

    @Test
    @DisplayName("a verdict on a run that is not parked is rejected")
    void rejectsVerdictOnARunThatIsNotParked() {
        ExecuteRefactorEntity exec = parked();
        exec.setStatus(ExecuteStatus.DEPLOYED);

        assertThatThrownBy(() -> service.recordVerdict("exec-4", true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not awaiting a verdict");
    }

    @Test
    @DisplayName("a verdict on an unknown run is rejected")
    void rejectsVerdictOnAnUnknownRun() {
        when(executeRepo.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordVerdict("nope", true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a parked run counts as in flight, so no second execution can start behind it")
    void parkedRunBlocksAnotherExecution() {
        when(executeRepo.existsByStatusIn(argThat(l -> l.contains(ExecuteStatus.AWAITING_VERDICT))))
                .thenReturn(true);

        assertThat(service.isRunning()).isTrue();
    }

    // ─── Stub ────────────────────────────────────────────────────────────────

    private static class StubProcessRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();

        List<List<String>> commandsStartingWith(String head) {
            return commands.stream().filter(c -> !c.isEmpty() && c.get(0).equals(head)).toList();
        }

        int indexOf(String... tokens) {
            for (int i = 0; i < commands.size(); i++) {
                if (containsInOrder(commands.get(i), tokens)) return i;
            }
            return -1;
        }

        List<String> first(String... tokens) {
            int i = indexOf(tokens);
            assertThat(i).as("expected a command matching %s", List.of(tokens)).isNotNegative();
            return commands.get(i);
        }

        private static boolean containsInOrder(List<String> cmd, String[] tokens) {
            int from = 0;
            for (String token : tokens) {
                int found = -1;
                for (int i = from; i < cmd.size(); i++) {
                    if (cmd.get(i).equals(token)) { found = i; break; }
                }
                if (found < 0) return false;
                from = found + 1;
            }
            return true;
        }

        /** Streamed commands (builds, compose) go through the same canned table as {@link #run}. */
        @Override
        public ProcessResult runStreaming(List<String> command, String workingDir, Map<String, String> extraEnv,
                                          java.time.Duration timeout,
                                          java.util.function.Consumer<String> lineConsumer) {
            ProcessResult r = run(command, workingDir, extraEnv);
            if (lineConsumer != null && r.stdout() != null) r.stdout().lines().forEach(lineConsumer);
            return r;
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv) {
            commands.add(List.copyOf(command));
            return new ProcessResult(0, stdoutFor(command), "");
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }

        /** `status --porcelain` must report the appended CLAUDE.md so the log commit is not skipped. */
        private String stdoutFor(List<String> command) {
            if (command.contains("--porcelain")) return " M applications/quizzes/CLAUDE.md\n";
            if (command.contains("rev-parse")) return "ghost123\n";
            return "";
        }
    }
}
