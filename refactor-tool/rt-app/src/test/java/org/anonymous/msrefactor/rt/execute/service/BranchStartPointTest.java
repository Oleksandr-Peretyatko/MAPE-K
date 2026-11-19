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
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.TopologyConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A refactoring version is a commit; the baseline is a branch.
 *
 * <p>That distinction is the whole of this test. Pinning v3's commit is what makes v4 compose on
 * exactly the code v3 produced. Pinning the *baseline's* commit is a category error: it names
 * wherever {@code base-branch} points, and any stored value is stale the moment it is written,
 * because writing it down is itself a commit.
 */
class BranchStartPointTest {

    private static final String PROJECT_ROOT = "/app/project";
    // Deliberately not the production default. The base branch is configuration
    // (rt.execute.base-branch / RT_EXECUTE_BASE_BRANCH); nothing in the resolution path may assume
    // a particular name, and a test that used the default could not tell the difference.
    private static final String BASE_BRANCH = "some/other-base";
    private static final String LOCAL_TIP = "1111aaaa2222bbbb3333cccc4444dddd5555eeee";
    private static final String ORIGIN_TIP = "6666ffff7777aaaa8888bbbb9999cccc0000dddd";

    private StubProcessRunner runner;
    private CodeVersionService codeVersionService;
    private ExecuteService service;

    @BeforeEach
    void setUp() {
        runner = new StubProcessRunner();
        codeVersionService = mock(CodeVersionService.class);

        ExecuteConfig config = new ExecuteConfig();
        config.setProjectRoot(PROJECT_ROOT);
        config.setBaseBranch(BASE_BRANCH);

        service = new ExecuteService(
                mock(ExecuteRefactorRepository.class),
                mock(org.anonymous.msrefactor.rt.plan.service.PlanService.class),
                runner, config, new AppTargetConfig(), new ObjectMapper(),
                mock(BehavioralPreservationChecker.class),
                mock(ClaudeCommandMutator.class),
                codeVersionService,
                mock(org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository.class),
                mock(LoopIterationService.class),
                mock(ComposeTopologyResolver.class), new TopologyConfig(),
                mock(org.anonymous.msrefactor.rt.analyze.service.ConstraintService.class),
                mock(org.anonymous.msrefactor.rt.topology.HostPortInspector.class));
    }

    private ExecuteRefactorEntity runAgainst(String sourceVersionId, String parentBranch) {
        return ExecuteRefactorEntity.builder()
                .executeRefactorId("exec-1").versionNumber(1)
                .branchName("refactor/v1-iter-1").parentBranchName(parentBranch)
                .iterationId("iter-1")
                .sourceVersionId(sourceVersionId)
                .status(ExecuteStatus.PENDING).executeLog("")
                .startedAt(Instant.now()).build();
    }

    private void version(String id, int number, String status, String sha, String branch) {
        when(codeVersionService.findById(id)).thenReturn(Optional.of(
                CodeVersionEntity.builder()
                        .versionId(id).versionNumber(number).status(status)
                        .gitCommitSha(sha).branchName(branch).build()));
    }

    private String resolve(ExecuteRefactorEntity exec) {
        return service.resolveBranchStartPoint(exec, PROJECT_ROOT, new StringBuilder());
    }

    // ─── The baseline is a branch ────────────────────────────────────────────

    @Test
    @DisplayName("a stored baseline commit is ignored — the branch tip is resolved instead")
    void baselineShaIsIgnoredInFavourOfTheLiveTip() {
        // A seeded or legacy row carrying a SHA. It is two commits behind the branch it claims to
        // represent, which is exactly the state this change exists to stop trusting.
        version("ver-0", 0, CodeVersionEntity.BASELINE,
                "abcd1234abcd1234abcd1234abcd1234abcd1234", BASE_BRANCH);

        String startPoint = resolve(runAgainst("ver-0", BASE_BRANCH));

        assertThat(startPoint).isEqualTo(LOCAL_TIP);
        assertThat(startPoint).isNotEqualTo("abcd1234abcd1234abcd1234abcd1234abcd1234");
        assertThat(runner.first("rev-parse", "--verify", BASE_BRANCH)).isNotNull();
    }

    @Test
    @DisplayName("the local ref is asked before origin, so unpushed commits are picked up")
    void prefersTheLocalRefOverOrigin() {
        version("ver-0", 0, CodeVersionEntity.BASELINE, null, BASE_BRANCH);

        String startPoint = resolve(runAgainst("ver-0", BASE_BRANCH));

        // Execute never pushes, so origin lags every commit made here. Resolving it first would
        // keep the staleness rather than remove it.
        assertThat(startPoint).isEqualTo(LOCAL_TIP).isNotEqualTo(ORIGIN_TIP);
        assertThat(runner.indexOf("rev-parse", "--verify", BASE_BRANCH))
                .isLessThan(runner.commands.size());
        assertThat(runner.indexOf("rev-parse", "--verify", "origin/" + BASE_BRANCH))
                .as("origin is not consulted once the local ref answers").isNegative();
    }

    @Test
    @DisplayName("a run with no source version at all still resolves the base branch tip")
    void noSourceVersionFallsBackToTheBaseBranch() {
        assertThat(resolve(runAgainst(null, null))).isEqualTo(LOCAL_TIP);
    }

    // ─── A version is a commit ───────────────────────────────────────────────

    @Test
    @DisplayName("a refactoring version's commit is used verbatim — that is the version tree")
    void nonBaselineUsesItsPinnedCommit() {
        String v3Sha = "feed5678feed5678feed5678feed5678feed5678";
        version("ver-3", 3, CodeVersionEntity.ACTIVE, v3Sha, "refactor/v3-iter-3");

        String startPoint = resolve(runAgainst("ver-3", "refactor/v3-iter-3"));

        assertThat(startPoint).isEqualTo(v3Sha);
        assertThat(runner.commandsContaining("rev-parse"))
                .as("a pinned commit needs no lookup").isEmpty();
    }

    @Test
    @DisplayName("a version with no commit falls back to its own branch, resolved locally")
    void nonBaselineWithoutAShaResolvesItsBranch() {
        // Refactor branches are never pushed, so the old "origin/<branch>" fallback named a ref that
        // cannot exist and checkout -B threw.
        version("ver-3", 3, CodeVersionEntity.ACTIVE, null, "refactor/v3-iter-3");
        runner.resolvable("refactor/v3-iter-3", "aaaa111");

        assertThat(resolve(runAgainst("ver-3", "refactor/v3-iter-3"))).isEqualTo("aaaa111");
    }

    // ─── Degraded environments ───────────────────────────────────────────────

    @Test
    @DisplayName("a fresh clone with only remote-tracking refs falls through to origin")
    void fallsBackToOriginWhenTheLocalRefIsMissing() {
        runner.onlyResolvable("origin/" + BASE_BRANCH, ORIGIN_TIP);
        version("ver-0", 0, CodeVersionEntity.BASELINE, null, BASE_BRANCH);

        assertThat(resolve(runAgainst("ver-0", BASE_BRANCH))).isEqualTo(ORIGIN_TIP);
    }

    @Test
    @DisplayName("when nothing resolves, the bare branch name is handed to git rather than invented")
    void fallsBackToTheBareNameWhenNothingResolves() {
        runner.onlyResolvable("nothing-matches", "");
        version("ver-0", 0, CodeVersionEntity.BASELINE, null, BASE_BRANCH);

        assertThat(resolve(runAgainst("ver-0", BASE_BRANCH))).isEqualTo(BASE_BRANCH);
    }

    // ─── Stub ────────────────────────────────────────────────────────────────

    private static class StubProcessRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        private final Map<String, String> refs = new java.util.HashMap<>(Map.of(
                BASE_BRANCH, LOCAL_TIP,
                "origin/" + BASE_BRANCH, ORIGIN_TIP));

        /** Makes one extra ref resolvable, on top of the two base-branch refs. */
        void resolvable(String ref, String sha) { refs.put(ref, sha); }

        /** Replaces the ref table entirely — for clones and empty repos. */
        void onlyResolvable(String ref, String sha) {
            refs.clear();
            if (!sha.isEmpty()) refs.put(ref, sha);
        }

        List<List<String>> commandsContaining(String token) {
            return commands.stream().filter(c -> c.contains(token)).toList();
        }

        int indexOf(String... tokens) {
            for (int i = 0; i < commands.size(); i++) {
                if (commands.get(i).containsAll(Set.of(tokens))) return i;
            }
            return -1;
        }

        List<String> first(String... tokens) {
            int i = indexOf(tokens);
            return i < 0 ? null : commands.get(i);
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
            if (command.contains("rev-parse") && command.contains("--verify")) {
                String ref = command.get(command.size() - 1);
                String sha = refs.get(ref);
                return sha == null ? new ProcessResult(128, "", "fatal: Needed a single revision")
                                   : new ProcessResult(0, sha + "\n", "");
            }
            return new ProcessResult(0, "", "");
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }
    }
}
