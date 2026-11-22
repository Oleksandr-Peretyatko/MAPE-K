package org.anonymous.msrefactor.rt.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.topology.TopologyConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rendered block is pasted into a terminal at the repository root, so it must match what the
 * Execute phase runs (gateway first, same teardown) while using host paths, not container ones.
 */
class DeployCommandRendererTest {

    private StubProcessRunner runner;
    private TopologyConfig topologyConfig;
    private DeployCommandRenderer renderer;

    @BeforeEach
    void setUp() {
        runner = new StubProcessRunner();
        topologyConfig = new TopologyConfig();
        ExecuteConfig executeConfig = new ExecuteConfig();
        executeConfig.setBuildProfiles("sagas");
        executeConfig.setComposeFile("/app/project/applications/quizzes/docker-compose.yml");
        renderer = new DeployCommandRenderer(new AppTargetConfig(), topologyConfig, executeConfig, runner);
        renderer.setEnv(Map.of("VERSION_MODE", "distributed-version", "COMM_LAYER", "stream")::get);
    }

    @Test
    @DisplayName("renders checkout, env, builds, teardown and up with repo-relative paths")
    void rendersFullBlock() {
        String command = renderer.render("refactor/v2-abc", 2,
                List.of("answer-service", "course-service", "user-service"));

        assertThat(command.split("\n")).containsExactly(
                "git checkout refactor/v2-abc",
                "export VERSION_MODE=distributed-version COMM_LAYER=stream",
                "docker compose -f applications/quizzes/docker-compose.yml build gateway",
                "docker build -f applications/quizzes/Dockerfile --build-arg PROFILE=sagas"
                        + " -t quizzes:v2 -t quizzes:latest .",
                "docker compose -f applications/quizzes/docker-compose.yml down --remove-orphans",
                "docker compose -f applications/quizzes/docker-compose.yml up -d --no-build"
                        + " gateway answer-service course-service user-service");
        assertThat(command).doesNotContain("/app/project");
    }

    @Test
    @DisplayName("omits the export line when rt-app has no deploy env, and tags the baseline by name")
    void omitsEmptyEnvAndTagsBaseline() {
        renderer.setEnv(name -> null);

        String command = renderer.render("tool/self-refactor", 0, List.of("answer-service"));

        assertThat(command).doesNotContain("export");
        assertThat(command).contains("-t quizzes:baseline -t quizzes:latest");
    }

    @Test
    @DisplayName("an always-up service already in the list is not repeated")
    void upServicesDeduplicates() {
        assertThat(DeployCommandRenderer.upServices(List.of("gateway"), List.of("gateway", "quiz-service")))
                .containsExactly("gateway", "quiz-service");
        assertThat(DeployCommandRenderer.upServices(List.of("gateway"), List.of("quiz-service")))
                .containsExactly("gateway", "quiz-service");
    }

    @Test
    @DisplayName("baseline services come from the base branch's compose file: image + START_CLASS only")
    void baselineServicesFromGitShow() {
        runner.stdout = composeFixture(20);

        List<String> services = renderer.baselineServices("tool/self-refactor");

        assertThat(runner.invocations.get(0))
                .containsSequence("show", "tool/self-refactor:applications/quizzes/docker-compose.yml");
        // 20 microservices use 60 merge aliases — above SnakeYAML's default cap of 50.
        assertThat(services).hasSize(20).allMatch(s -> s.endsWith("-service"));
        assertThat(services).doesNotContain("quizzes-local", "svc00-db", "gateway");
    }

    @Test
    @DisplayName("falls back to the configured service list when git show fails")
    void baselineServicesFallback() {
        runner.exitCode = 128;

        assertThat(renderer.baselineServices("missing-branch"))
                .containsExactlyElementsOf(topologyConfig.getFallbackServicesList());
    }

    @Test
    @DisplayName("the checked-out application compose file parses and yields its microservices")
    void parsesRealComposeFile() throws Exception {
        Path compose = Path.of("../../applications/quizzes/docker-compose.yml");
        assumeTrue(Files.isReadable(compose), "run from refactor-tool/rt-app");

        List<String> services = renderer.selectDeployTargets(Files.readString(compose));

        assertThat(services).isNotEmpty().allMatch(s -> s.endsWith("-service"));
        assertThat(services).doesNotContain("quizzes-local", "quizzes-remote", "version-service");
    }

    /** A compose file shaped like the real one: anchors merged into every service. */
    private static String composeFixture(int microservices) {
        StringBuilder yaml = new StringBuilder("""
                x-env: &env
                  EUREKA_HOST: eureka-server
                x-deps: &deps
                  rabbitmq:
                    condition: service_healthy
                x-limits: &limits
                  mem_limit: 768m
                services:
                  gateway:
                    image: simulator:latest
                  quizzes-local:
                    image: quizzes:latest
                    environment:
                      SPRING_PROFILES_ACTIVE: sagas,local
                """);
        for (int i = 0; i < microservices; i++) {
            String name = String.format("svc%02d", i);
            yaml.append("  ").append(name).append("-db:\n")
                .append("    image: postgres:14-alpine\n")
                .append("  ").append(name).append("-service:\n")
                .append("    image: quizzes:latest\n")
                .append("    depends_on:\n      <<: *deps\n")
                .append("    environment:\n      <<: *env\n")
                .append("      START_CLASS: pt.example.").append(name).append(".App\n")
                .append("    <<: *limits\n");
        }
        return yaml.toString();
    }

    private static class StubProcessRunner extends ProcessRunner {
        final List<List<String>> invocations = new ArrayList<>();
        String stdout = "";
        int exitCode = 0;

        @Override
        public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv) {
            invocations.add(List.copyOf(command));
            return new ProcessResult(exitCode, stdout, exitCode == 0 ? "" : "fatal: invalid object name");
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }
    }
}
