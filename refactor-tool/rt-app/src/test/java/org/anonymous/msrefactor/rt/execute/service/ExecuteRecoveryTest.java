package org.anonymous.msrefactor.rt.execute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteStatus;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployService;
import org.anonymous.msrefactor.rt.topology.DeployTopology;
import org.anonymous.msrefactor.rt.topology.HostPortInspector;
import org.anonymous.msrefactor.rt.topology.TopologyConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Recovery must be both correct and cheap: it has to restore the parent branch's runtime when the
 * stack was torn down, and it must not cold-start the whole system when it wasn't.
 */
class ExecuteRecoveryTest {

    private static final String PARENT = "refactor/v3-iter-3";
    private static final String PROJECT_ROOT = "/app/project";

    private StubProcessRunner runner;
    private ExecuteRefactorRepository executeRepo;
    private LoopIterationService loopIterationService;
    private CodeVersionService codeVersionService;
    private ComposeTopologyResolver topologyResolver;
    private ExecuteService service;
    private ClaudeCommandMutator commandMutator;
    private HostPortInspector hostPortInspector;

    @BeforeEach
    void setUp() {
        runner = new StubProcessRunner();
        hostPortInspector = mock(HostPortInspector.class);
        executeRepo = mock(ExecuteRefactorRepository.class);
        loopIterationService = mock(LoopIterationService.class);
        codeVersionService = mock(CodeVersionService.class);
        topologyResolver = mock(ComposeTopologyResolver.class);
        commandMutator = mock(ClaudeCommandMutator.class);

        ExecuteConfig config = new ExecuteConfig();
        config.setProjectRoot(PROJECT_ROOT);
        config.setComposeFile("/app/project/applications/quizzes/docker-compose.yml");
        config.setBaseBranch("tool/self-refactor");
        config.setDeployHealthTimeoutSeconds(1);

        when(topologyResolver.resolve(anyString(), anyBoolean(), any()))
                .thenReturn(topology("tournament-service", "user-service"));

        service = new ExecuteService(
                executeRepo,
                mock(org.anonymous.msrefactor.rt.plan.service.PlanService.class),
                runner, config, new AppTargetConfig(), new ObjectMapper(),
                mock(BehavioralPreservationChecker.class),
                commandMutator,
                codeVersionService,
                mock(org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository.class),
                loopIterationService,
                topologyResolver, new TopologyConfig(),
                mock(org.anonymous.msrefactor.rt.analyze.service.ConstraintService.class),
                hostPortInspector);
        // Run the async pipeline inline so resume tests observe its whole effect.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "pipelineExecutor", (java.util.concurrent.Executor) Runnable::run);
    }

    private DeployTopology topology(String... names) {
        List<DeployService> services = new ArrayList<>();
        for (String n : names) {
            services.add(new DeployService(n, n, n, n.replace("-service", ""), List.of(8080), true));
        }
        return new DeployTopology("compose.yml", "quizzes:latest", services,
                Instant.now(), DeployTopology.Source.COMPOSE);
    }

    private ExecuteRefactorEntity run(boolean committed) {
        return ExecuteRefactorEntity.builder()
                .executeRefactorId("exec-4").versionNumber(4)
                .branchName("refactor/v4-iter-4").parentBranchName(PARENT)
                .iterationId("iter-4")
                .gitCommitSha(committed ? "abc1234" : null)
                .status(ExecuteStatus.FAILED).executeLog("")
                .startedAt(Instant.now()).build();
    }

    // ─── The decision table ──────────────────────────────────────────────────

    @Test
    @DisplayName("stack untouched: recovery is a git checkout and issues NO docker command")
    void noDockerWhenStackWasNeverTornDown() {
        service.recoverToParentBranch(run(false), true, false, PROJECT_ROOT,
                new StringBuilder(), "sagas gate failed");

        // This is the whole point of the stackTornDown flag: a mutation or test-gate failure never
        // stopped a container, so rebuilding and redeploying would be a pointless cold start.
        assertThat(runner.commandsStartingWith("docker"))
                .as("no docker work when the runtime was never disturbed").isEmpty();
        assertThat(runner.first("git", "checkout")).containsSequence("checkout", "--force", PARENT);
    }

    @Test
    @DisplayName("stack torn down: rebuild runs BEFORE the up, and no docker tag is ever used")
    void rebuildsBeforeRedeployWhenStackWasTornDown() {
        service.recoverToParentBranch(run(true), true, true, PROJECT_ROOT,
                new StringBuilder(), "compose deploy failed");

        int build = runner.indexOf("docker", "build");
        int up = runner.indexOf("docker", "compose", "up");
        assertThat(build).as("a rebuild must happen").isNotNegative();
        assertThat(up).as("the parent must be redeployed").isNotNegative();
        // quizzes:latest points at the failed build; `up --no-build` would otherwise bring the
        // parent's topology back up running exactly the code that just failed.
        assertThat(build).as("rebuild must precede the up").isLessThan(up);
        assertThat(runner.indexOf("docker", "tag")).as("versioned images are never trusted").isNegative();
    }

    @Test
    @DisplayName("recovery targets the parent branch, never the configured base branch")
    void checksOutParentNotBase() {
        service.recoverToParentBranch(run(false), true, false, PROJECT_ROOT,
                new StringBuilder(), "boom");

        assertThat(runner.first("git", "checkout")).contains(PARENT).doesNotContain("tool/self-refactor");
    }

    @Test
    @DisplayName("a run with no parent recorded falls back to the base branch")
    void fallsBackToBaseBranchWhenNoParent() {
        ExecuteRefactorEntity exec = run(false);
        exec.setParentBranchName(null);

        service.recoverToParentBranch(exec, true, false, PROJECT_ROOT, new StringBuilder(), "boom");

        assertThat(runner.first("git", "checkout")).contains("tool/self-refactor");
    }

    @Test
    @DisplayName("a failed rebuild leaves the stack down rather than deploying the failed image")
    void abortsRedeployWhenRebuildFails() {
        runner.failCommandsContaining("build");

        service.recoverToParentBranch(run(true), true, true, PROJECT_ROOT,
                new StringBuilder(), "compose deploy failed");

        assertThat(runner.indexOf("docker", "build")).isNotNegative();
        assertThat(runner.indexOf("docker", "compose", "up"))
                .as("never deploy on an image we could not rebuild").isNegative();
    }

    // ─── Branch disposal and state ───────────────────────────────────────────

    @Test
    @DisplayName("an empty branch is deleted; one carrying a commit is kept for inspection")
    void branchDisposal() {
        service.recoverToParentBranch(run(false), true, false, PROJECT_ROOT,
                new StringBuilder(), "boom");
        assertThat(runner.indexOf("git", "branch", "-D")).isNotNegative();

        runner.reset();
        service.recoverToParentBranch(run(true), true, false, PROJECT_ROOT,
                new StringBuilder(), "boom");
        assertThat(runner.indexOf("git", "branch", "-D"))
                .as("a committed refactor is worth inspecting").isNegative();
    }

    @Test
    @DisplayName("nothing is deleted or cleaned when the branch was never checked out")
    void noBranchWorkBeforeCheckout() {
        service.recoverToParentBranch(run(false), false, false, PROJECT_ROOT,
                new StringBuilder(), "git fetch failed");

        assertThat(runner.indexOf("git", "branch", "-D")).isNegative();
        assertThat(runner.indexOf("git", "clean")).isNegative();
    }

    @Test
    @DisplayName("the branch is marked BUILD_FAILED and the failed candidate is recorded")
    void recordsBranchStateAndCandidate() {
        service.recoverToParentBranch(run(false), true, false, PROJECT_ROOT,
                new StringBuilder(), "sagas gate failed");

        verify(loopIterationService).markBranchStatus("iter-4", LoopIterationService.BRANCH_BUILD_FAILED);
        verify(loopIterationService).recordBuildFailure("iter-4", "sagas gate failed");
    }

    // ─── Success: the follow-up iteration that continues the loop ────────────

    private void stubVersion() {
        when(codeVersionService.createFromExecution(any())).thenReturn(
                CodeVersionService.CodeVersionSummary.builder()
                        .versionId("ver-4").versionNumber(4).versionLabel("v4")
                        .branchName("refactor/v4-iter-4").build());
    }

    private void stubNewIteration(String id) {
        when(loopIterationService.create(any(), anyString())).thenReturn(
                LoopIterationService.LoopIterationSummary.builder()
                        .iterationId(id).iterationNumber(5).name("Iteration 5")
                        .status(LoopIterationService.CREATED).codeVersionId("ver-4").build());
    }

    @Test
    @DisplayName("a successful run binds a new iteration to the version it produced")
    void createsFollowUpIterationBoundToTheNewVersion() {
        stubVersion();
        stubNewIteration("iter-5");
        ExecuteRefactorEntity exec = run(true);

        service.recordVersionAndFollowUp(exec);

        // Bound to the new version, so the next Monitor run measures the code just deployed.
        verify(loopIterationService).create(null, "ver-4");
        assertThat(exec.getNextIterationId()).isEqualTo("iter-5");
        verify(executeRepo).save(exec);
    }

    @Test
    @DisplayName("a run outside an iteration records the version but creates no iteration")
    void noFollowUpForRunsWithoutAnIteration() {
        stubVersion();
        ExecuteRefactorEntity exec = run(true);
        exec.setIterationId(null);          // test run / direct API call

        service.recordVersionAndFollowUp(exec);

        verify(codeVersionService).createFromExecution(exec);
        verify(loopIterationService, never()).create(any(), anyString());
        assertThat(exec.getNextIterationId()).isNull();
    }

    @Test
    @DisplayName("re-running the same row does not fork a second follow-up iteration")
    void followUpCreationIsIdempotent() {
        stubVersion();
        ExecuteRefactorEntity exec = run(true);
        exec.setNextIterationId("iter-5");   // already chained

        service.recordVersionAndFollowUp(exec);

        verify(loopIterationService, never()).create(any(), anyString());
        assertThat(exec.getNextIterationId()).isEqualTo("iter-5");
    }

    @Test
    @DisplayName("bookkeeping failure never turns a good deploy into a failure")
    void followUpFailureIsNonFatal() {
        stubVersion();
        when(loopIterationService.create(any(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        ExecuteRefactorEntity exec = run(true);

        // Must not propagate: the deploy already succeeded.
        service.recordVersionAndFollowUp(exec);

        assertThat(exec.getNextIterationId()).isNull();
    }

    // ─── Redeploy: teardown, retry and diagnostics ────────────────────────────
    // Driven through the recovery path, which rebuilds the parent and calls redeploy + health wait.

    private static final String DB_FAILED =
            "dependency failed to start: container answer-db exited (1)";

    private StringBuilder recoverTornDownStack() {
        StringBuilder log = new StringBuilder();
        service.recoverToParentBranch(run(true), true, true, PROJECT_ROOT, log, "compose deploy failed");
        return log;
    }

    @Test
    @DisplayName("teardown removes anonymous volumes first, and down never deletes the named ones")
    void teardownRemovesAnonymousVolumesBeforeDown() {
        recoverTornDownStack();

        int rm = runner.indexOf("docker", "compose", "rm", "--stop", "--force", "-v");
        int down = runner.indexOf("docker", "compose", "down");
        assertThat(rm).as("the databases' anonymous volumes are removed").isNotNegative();
        assertThat(rm).as("rm must precede down, which forgets the volumes").isLessThan(down);
        // `down -v` would also delete m2_cache and rabbitmq_data.
        assertThat(runner.first("docker", "compose", "down")).doesNotContain("-v", "--volumes");
    }

    @Test
    @DisplayName("a database that fails to start is retried once on fresh containers, after diagnostics")
    void retriesDeployOnceWhenADatabaseFailsToStart() {
        runner.script("up", 1, 1, DB_FAILED);
        runner.psStdout = StubProcessRunner.ALL_HEALTHY.replace("]",
                ",{\"Service\":\"answer-db\",\"State\":\"exited\",\"Health\":\"\"}]");

        StringBuilder log = recoverTornDownStack();

        List<Integer> ups = runner.indexesOf("docker", "compose", "up");
        List<Integer> rms = runner.indexesOf("docker", "compose", "rm");
        List<Integer> downs = runner.indexesOf("docker", "compose", "down");
        int ps = runner.indexOf("docker", "compose", "ps", "--all");
        int logs = runner.indexOf("docker", "compose", "logs");
        assertThat(ups).as("one retry").hasSize(2);
        assertThat(runner.commands.get(logs)).as("the failed database's logs are captured").contains("answer-db");
        // Diagnostics run between the failed up and the teardown that deletes the evidence.
        assertThat(List.of(ups.get(0), ps, logs, rms.get(1), downs.get(1), ups.get(1))).isSorted();
        assertThat(log.toString())
                .contains("[diag] not running/healthy: [answer-db(exited)]", "=== diag-logs",
                          "retrying the deploy once on fresh containers", "redeployed and healthy");
    }

    @Test
    @DisplayName("a deterministic compose failure is not retried, but its diagnostics are captured")
    void doesNotRetryOtherComposeFailures() {
        runner.script("up", 1, 1, "Bind for 0.0.0.0:8091 failed: port is already allocated");

        StringBuilder log = recoverTornDownStack();

        assertThat(runner.indexesOf("docker", "compose", "up")).hasSize(1);
        assertThat(runner.indexOf("docker", "compose", "ps", "--all")).isNotNegative();
        assertThat(log.toString()).contains("[diag]", "[RECOVERY WARN]").doesNotContain("retrying");
    }

    @Test
    @DisplayName("a database that keeps failing is retried at most once")
    void retriesAtMostOnce() {
        runner.script("up", 99, 1, "dependency failed to start: container answer-db is unhealthy");

        StringBuilder log = recoverTornDownStack();

        assertThat(runner.indexesOf("docker", "compose", "up")).hasSize(2);
        assertThat(log.toString()).contains("[RECOVERY WARN]");
    }

    @Test
    @DisplayName("a health timeout captures the logs of the services that never became ready")
    void healthTimeoutCapturesLogsOfNotReadyServices() {
        runner.psStdout = StubProcessRunner.ALL_HEALTHY.replace(
                "\"user-service\",\"State\":\"running\",\"Health\":\"healthy\"",
                "\"user-service\",\"State\":\"running\",\"Health\":\"starting\"");

        StringBuilder log = recoverTornDownStack();

        assertThat(runner.first("docker", "compose", "logs")).contains("user-service");
        assertThat(log.toString())
                .contains("health timeout", "user-service(running/starting)", "[RECOVERY WARN]");
    }

    @Test
    @DisplayName("only a dependency that failed to start counts as a transient infrastructure failure")
    void infraDependencyFailureClassification() {
        assertThat(ExecuteService.isInfraDependencyFailure(DB_FAILED)).isTrue();
        assertThat(ExecuteService.isInfraDependencyFailure(
                " Container answer-db  Error\ndependency failed to start: container answer-db is unhealthy"))
                .isTrue();
        assertThat(ExecuteService.isInfraDependencyFailure("port is already allocated")).isFalse();
        assertThat(ExecuteService.isInfraDependencyFailure("")).isFalse();
        assertThat(ExecuteService.isInfraDependencyFailure(null)).isFalse();
    }

    // ─── Stub ────────────────────────────────────────────────────────────────

    // ─── Restart and build diagnostics ───────────────────────────────────────

    @Test
    @DisplayName("on startup, runs left mid-pipeline are FAILED so new runs are not blocked")
    void failsOrphanedRunsOnStartup() {
        ExecuteRefactorEntity orphan = run(true);
        orphan.setStatus(ExecuteStatus.BUILDING);
        orphan.setExecuteLog("step 5a…");
        when(executeRepo.findAllByStatusIn(any())).thenReturn(List.of(orphan));

        service.failOrphanedRuns();

        assertThat(orphan.getStatus()).isEqualTo(ExecuteStatus.FAILED);
        assertThat(orphan.getCompletedAt()).isNotNull();
        assertThat(orphan.getExecuteLog()).startsWith("step 5a…").contains("rt-app restarted", "BUILDING");
        verify(executeRepo).save(orphan);
        verify(loopIterationService).updateFromExecuteStatus("iter-4", ExecuteStatus.FAILED.name());
        assertThat(runner.commands).as("state-only: no git or docker work").isEmpty();
    }

    @Test
    @DisplayName("surefire summary reads the final totals line, not per-class lines")
    void surefireSummaryUsesTotalsLine() {
        String out = """
                #12 41.2 [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.2 s -- in a.FooTest
                #12 55.0 [ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.4 s -- in a.BarTest
                #12 55.1 [ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 1
                """;
        assertThat(ExecuteService.surefireSummary(out)).isEqualTo("tests=5 failures=1 errors=0 skipped=1");
        assertThat(ExecuteService.surefireSummary("BUILD FAILURE: compilation error")).isNull();
    }

    // ─── Resume from step 5a ─────────────────────────────────────────────────

    private ExecuteRefactorEntity failedCommittedRun() throws Exception {
        ExecuteRefactorEntity exec = run(true);
        exec.setExecuteLog("step 4 — committed sha=abc1234\n");
        exec.setPlanPayload(new ObjectMapper().writeValueAsString(new org.anonymous.msrefactor.rt.plan.dto.PlanResponse(
                "plan-1", "TEST", "MERGE_AGGREGATES", "course", null, null,
                "merge-aggregate course question", Instant.now().toString(),
                null, null, null, null, null, null, null, null, null)));
        when(executeRepo.findById("exec-4")).thenReturn(Optional.of(exec));
        return exec;
    }

    @Test
    @DisplayName("resume checks out the committed branch and re-enters at the test gate — no mutation")
    void resumeReentersAtTestGate() throws Exception {
        ExecuteRefactorEntity exec = failedCommittedRun();
        runner.sha = "abc1234";
        runner.failCommandsContaining("--target"); // the test gate fails, so nothing is deployed

        service.resumeFromBuild("exec-4");

        int checkout = runner.indexOf("git", "checkout", "refactor/v4-iter-4");
        int gate = runner.indexOf("docker", "build", "--target", "test");
        assertThat(checkout).as("the run's own branch is checked out").isNotNegative();
        assertThat(gate).as("the test gate runs after the checkout").isGreaterThan(checkout);
        assertThat(runner.commands.get(gate)).as("the gate runs on the shared builder, which has no 2 MiB log cap")
                .containsSequence("docker", "buildx", "build", "--builder", ExecuteService.TEST_GATE_BUILDER);
        assertThat(runner.indexOf("buildx", "create")).as("an existing builder is reused").isNegative();
        assertThat(runner.first("git", "checkout", "refactor/v4-iter-4")).doesNotContain("--force", "-B");
        verifyNoInteractions(commandMutator);
        verify(loopIterationService).reopenForResume("iter-4");

        assertThat(exec.getStatus()).isEqualTo(ExecuteStatus.FAILED);
        assertThat(exec.getExecuteLog())
                .startsWith("step 4 — committed sha=abc1234")
                .contains("[resume]", "=== test-gate (exit=1)", "Functional test gate failed");
        assertThat(runner.indexOf("git", "branch", "-D"))
                .as("the committed refactor is kept for another resume").isNegative();
    }

    @Test
    @DisplayName("resume is refused for a run that is not FAILED, never committed, or whose branch moved")
    void resumeGuards() throws Exception {
        ExecuteRefactorEntity exec = failedCommittedRun();

        runner.sha = "fff9999";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resumeFromBuild("exec-4"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not at the run's commit");

        runner.sha = "";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resumeFromBuild("exec-4"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer exists");

        exec.setGitCommitSha(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resumeFromBuild("exec-4"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("never committed");

        exec.setGitCommitSha("abc1234");
        exec.setStatus(ExecuteStatus.DEPLOYED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resumeFromBuild("exec-4"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Only a FAILED run");

        assertThat(runner.indexOf("docker", "build")).as("nothing built on a refused resume").isNegative();
    }

    @Test
    @DisplayName("a missing rt-test-gate builder is created before the gate runs on it")
    void createsTestGateBuilderWhenMissing() throws Exception {
        failedCommittedRun();
        runner.sha = "abc1234";
        runner.failCommandsContaining("inspect", "--target"); // no builder yet; the gate fails to stop there

        service.resumeFromBuild("exec-4");

        int create = runner.indexOf("docker", "buildx", "create", "--name", ExecuteService.TEST_GATE_BUILDER);
        int gate = runner.indexOf("docker", "buildx", "build", "--builder", ExecuteService.TEST_GATE_BUILDER,
                "--target", "test");
        assertThat(create).as("the builder is created").isNotNegative();
        assertThat(gate).as("and the gate then runs on it").isGreaterThan(create);
    }

    @Test
    @DisplayName("without the rt-test-gate builder the gate still runs, on the default builder, and says so")
    void fallsBackToDefaultBuilder() throws Exception {
        ExecuteRefactorEntity exec = failedCommittedRun();
        runner.sha = "abc1234";
        runner.failCommandsContaining("inspect", "create", "--target");

        service.resumeFromBuild("exec-4");

        assertThat(runner.first("docker", "build", "--target", "test"))
                .as("the gate is a plain docker build").doesNotContain("buildx");
        assertThat(exec.getExecuteLog())
                .contains("[WARN] builder 'rt-test-gate' unavailable", "=== test-gate (exit=1)");
    }

    // ─── Host-port preflight ─────────────────────────────────────────────────

    @Test
    @DisplayName("a host port held by another compose project fails the run before anything is built")
    void portConflictFailsBeforeTheTestGate() throws Exception {
        ExecuteRefactorEntity exec = failedCommittedRun();
        runner.sha = "abc1234";
        when(hostPortInspector.findConflicts(any())).thenReturn(List.of(new HostPortInspector.PortConflict(
                "tournament-satellite-service", 8090, "tcp", "rt-app", "refactor-tool", false)));

        service.resumeFromBuild("exec-4");

        assertThat(exec.getStatus()).isEqualTo(ExecuteStatus.FAILED);
        assertThat(exec.getExecuteLog()).contains(
                "host port 8090/tcp of tournament-satellite-service is already published by container 'rt-app'");
        // v6 found this out at `compose up`, after an hour: no gate, no image, no teardown here.
        assertThat(runner.commandsStartingWith("docker")).as("nothing built, nothing torn down").isEmpty();
        assertThat(runner.indexOf("git", "branch", "-D")).as("the committed refactor is kept").isNegative();
        verify(hostPortInspector).findConflicts(argThat(roots ->
                roots.containsAll(List.of("gateway", "tournament-service", "user-service"))));
    }

    @Test
    @DisplayName("a preflight that cannot ask docker never blocks the run")
    void inspectorFailureNeverBlocks() throws Exception {
        ExecuteRefactorEntity exec = failedCommittedRun();
        runner.sha = "abc1234";
        runner.failCommandsContaining("--target"); // stop at the gate: it ran, so the preflight let it
        when(hostPortInspector.findConflicts(any()))
                .thenThrow(new HostPortInspector.InspectionException("docker ps failed (exit=1)"));

        service.resumeFromBuild("exec-4");

        assertThat(runner.indexOf("docker", "build", "--target", "test")).isNotNegative();
        assertThat(exec.getExecuteLog()).contains("host-port preflight skipped — docker ps failed");
    }

    private static class StubProcessRunner extends ProcessRunner {
        static final String ALL_HEALTHY =
                "[{\"Service\":\"tournament-service\",\"State\":\"running\",\"Health\":\"healthy\"},"
              + "{\"Service\":\"user-service\",\"State\":\"running\",\"Health\":\"healthy\"}]";

        final List<List<String>> commands = new ArrayList<>();
        private List<String> failTokens = List.of();
        private final List<Scripted> scripted = new ArrayList<>();
        /** What `git rev-parse` reports — the branch tip and HEAD. */
        String sha = "";
        /** What every `compose ps` reports, filtered or `--all`. */
        String psStdout = ALL_HEALTHY;

        private static final class Scripted {
            final String token; int remaining; final int exit; final String stdout;
            Scripted(String token, int times, int exit, String stdout) {
                this.token = token; this.remaining = times; this.exit = exit; this.stdout = stdout;
            }
        }

        void reset() { commands.clear(); }
        /** Commands containing any of these tokens exit 1. */
        void failCommandsContaining(String... tokens) { this.failTokens = List.of(tokens); }
        /** The next {@code times} commands containing {@code token} return this, before any other rule. */
        void script(String token, int times, int exit, String stdout) {
            scripted.add(new Scripted(token, times, exit, stdout));
        }

        /** Indexes of every command containing all the given tokens in order. */
        List<Integer> indexesOf(String... tokens) {
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < commands.size(); i++) {
                if (containsInOrder(commands.get(i), tokens)) found.add(i);
            }
            return found;
        }

        List<List<String>> commandsStartingWith(String head) {
            return commands.stream().filter(c -> !c.isEmpty() && c.get(0).equals(head)).toList();
        }

        /** Index of the first command containing all the given tokens in order, else -1. */
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

        /** True when every token appears, in order, somewhere in the command. */
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
            for (Scripted s : scripted) {
                if (s.remaining > 0 && command.contains(s.token)) {
                    s.remaining--;
                    return new ProcessResult(s.exit, s.stdout, "");
                }
            }
            boolean fail = failTokens.stream().anyMatch(command::contains);
            return new ProcessResult(fail ? 1 : 0, stdoutFor(command), "");
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }

        /** `compose ps` reports healthy by default so the recovery health wait terminates. */
        private String stdoutFor(List<String> command) {
            if (command.contains("rev-parse")) return sha;
            if (command.contains("ps")) return psStdout;
            return "";
        }
    }
}
