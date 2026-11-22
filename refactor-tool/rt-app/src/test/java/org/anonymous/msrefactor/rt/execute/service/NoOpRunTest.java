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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A run that changed no code did not perform its operation.
 *
 * <p>Every refactoring skill appends a {@code CLAUDE.md} entry, including when it refuses. Counting
 * that entry as a change is how a refused {@code CREATE_REPLICA} came to be committed as
 * {@code refactor(v1): …}, built, deployed, and recorded as a {@code CodeVersion} whose entire delta
 * was a note explaining why the refactoring could not be done.
 *
 * <p>The guard is deliberately independent of the stop block: it holds whether the block was
 * missing, malformed or judged out of scope, which is what makes it a floor rather than a second
 * special case.
 */
class NoOpRunTest {

    @TempDir Path projectRoot;

    private StubProcessRunner runner;
    private ExecuteRefactorRepository executeRepo;
    private CodeVersionService codeVersionService;
    private ExecuteService service;
    private ExecuteRefactorEntity exec;

    @BeforeEach
    void setUp() throws Exception {
        runner = new StubProcessRunner();
        executeRepo = mock(ExecuteRefactorRepository.class);
        codeVersionService = mock(CodeVersionService.class);
        AppTargetConfig appTarget = new AppTargetConfig();
        Files.createDirectories(Path.of(appTarget.absoluteDir(projectRoot.toString())));

        ExecuteConfig config = new ExecuteConfig();
        config.setProjectRoot(projectRoot.toString());
        config.setBaseBranch("some/other-base");
        config.setSkipTests(true);

        service = new ExecuteService(
                executeRepo,
                mock(org.anonymous.msrefactor.rt.plan.service.PlanService.class),
                runner, config, appTarget, new ObjectMapper(),
                mock(BehavioralPreservationChecker.class),
                mock(ClaudeCommandMutator.class),
                codeVersionService,
                mock(org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository.class),
                mock(LoopIterationService.class),
                mock(ComposeTopologyResolver.class), new TopologyConfig(),
                mock(org.anonymous.msrefactor.rt.analyze.service.ConstraintService.class),
                mock(org.anonymous.msrefactor.rt.topology.HostPortInspector.class));

        exec = ExecuteRefactorEntity.builder()
                .executeRefactorId("exec-1").versionNumber(1)
                .branchName("refactor/v1-iter-1").parentBranchName("some/other-base")
                .iterationId("iter-1")
                .operation("CREATE_REPLICA").targetService("tournament").secondaryTarget("answer")
                .planPayload("{}")
                .status(ExecuteStatus.PENDING).executeLog("")
                .startedAt(Instant.now()).build();
        when(executeRepo.findById("exec-1")).thenReturn(Optional.of(exec));
    }

    private PlanResponse plan() {
        return new PlanResponse("plan-1", "READY", "CREATE_REPLICA", "tournament", "answer",
                null, "create-replica answer QuizAnswer --to tournament",
                Instant.now().toString(), null, null, null, null, null, null,
                null, null, "APPROVED");
    }

    @Test
    @DisplayName("a tree containing only the skill's log entry is NOT_APPLICABLE, never a version")
    void claudeMdOnlyTreeNeverShips() {
        runner.changedFiles(" M applications/quizzes/CLAUDE.md\n");

        service.runPipeline("exec-1", plan());

        assertThat(exec.getStatus()).isEqualTo(ExecuteStatus.NOT_APPLICABLE);
        assertThat(exec.getCompletedAt()).isNotNull();

        // The three things that made the production failure damaging.
        assertThat(runner.commandsStartingWith("docker"))
                .as("no image is built and nothing is deployed").isEmpty();
        verify(codeVersionService, never()).createFromExecution(any());
        assertThat(runner.commitMessages())
                .as("the log entry is committed for the audit trail, but never as a refactoring")
                .allMatch(m -> m.contains("made no change"));
    }

    @Test
    @DisplayName("one real file changed and the run proceeds as before")
    void realChangeStillBuilds() {
        runner.changedFiles(" M applications/quizzes/src/main/java/…/Tournament.java\n");

        service.runPipeline("exec-1", plan());

        assertThat(exec.getStatus()).isNotEqualTo(ExecuteStatus.NOT_APPLICABLE);
        assertThat(runner.commitMessages())
                .anyMatch(m -> m.startsWith("refactor(v1): CREATE_REPLICA tournament"));
    }

    @Test
    @DisplayName("the commit names both operands, not just the target")
    void commitMessageNamesThePair() {
        runner.changedFiles(" M applications/quizzes/src/main/java/…/Tournament.java\n");

        service.runPipeline("exec-1", plan());

        // "CREATE_REPLICA tournament" names half an operation, and the half it names reads as the
        // source to anyone scanning git log.
        assertThat(runner.commitMessages()).anyMatch(m -> m.contains("tournament ← answer"));
    }

    // ─── Stub ────────────────────────────────────────────────────────────────

    private static class StubProcessRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        private String porcelain = "";

        /** What `git status --porcelain` reports, for both the plain and the CLAUDE.md-excluded call. */
        void changedFiles(String output) { this.porcelain = output; }

        List<List<String>> commandsStartingWith(String head) {
            return commands.stream().filter(c -> !c.isEmpty() && c.get(0).equals(head)).toList();
        }

        List<String> commitMessages() {
            List<String> out = new ArrayList<>();
            for (List<String> c : commands) {
                int i = c.indexOf("commit");
                int m = c.indexOf("-m");
                if (i >= 0 && m >= 0 && m + 1 < c.size()) out.add(c.get(m + 1));
            }
            return out;
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
            if (command.contains("--porcelain")) {
                // The CLAUDE.md-excluded variant sees nothing when CLAUDE.md is the only change.
                boolean excludesClaudeMd = command.stream().anyMatch(t -> t.startsWith(":(exclude)"));
                boolean onlyClaudeMd = porcelain.contains("CLAUDE.md") && !porcelain.contains(".java");
                return new ProcessResult(0, excludesClaudeMd && onlyClaudeMd ? "" : porcelain, "");
            }
            if (command.contains("rev-parse")) return new ProcessResult(0, "abc1234\n", "");
            return new ProcessResult(0, "", "");
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }
    }
}
