package org.anonymous.msrefactor.rt.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resolver is what makes the deploy topology operation-agnostic, so the fixtures cover the
 * three shapes a refactoring produces: unchanged, one service fewer (merge), one more (split).
 */
class ComposeTopologyResolverTest {

    private static final List<String> BASELINE_8 = List.of(
            "answer-service", "course-service", "execution-service", "question-service",
            "quiz-service", "topic-service", "tournament-service", "user-service");

    private StubProcessRunner runner;
    private TopologyConfig config;
    private ComposeTopologyResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        runner = new StubProcessRunner();
        config = new TopologyConfig();
        config.setComposeFile("applications/quizzes/docker-compose.yml");
        // Point resource lookup at a directory with no application-*.yaml, so the Jaeger-name
        // tests below exercise the documented suffix-strip fallback deterministically.
        config.setProjectRoot(Files.createTempDirectory("topology-test").toString());
        resolver = new ComposeTopologyResolver(runner, config, new ObjectMapper());
    }

    // ─── Discovery ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("discovers exactly the 8 microservices, excluding monoliths, DBs, tests and infra")
    void discoversBaseline() {
        runner.stdout = fixture("compose-config-v0.json");

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(topology.serviceNames()).containsExactlyElementsOf(BASELINE_8);
        assertThat(topology.source()).isEqualTo(DeployTopology.Source.COMPOSE);
        // The image alone matches 10 services; START_CLASS is what excludes the two monoliths.
        assertThat(topology.serviceNames())
                .doesNotContain("quizzes-local", "quizzes-remote",
                        "gateway", "version-service", "tournament-db", "postgres",
                        "eureka-server", "rabbitmq", "jaeger",
                        "test-quizzes-sagas", "test-simulator-sagas");
    }

    @Test
    @DisplayName("a merge that removes a service is picked up with no config change")
    void discoversMergedTopology() {
        runner.stdout = fixture("compose-config-merged.json");

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(topology.serviceNames()).containsExactly(
                "course-service", "execution-service", "question-service", "quiz-service",
                "topic-service", "tournamentanswer-service", "user-service");
        assertThat(topology.serviceNames()).doesNotContain("answer-service", "tournament-service");
    }

    @Test
    @DisplayName("a split that adds a service is picked up with no config change")
    void discoversSplitTopology() {
        runner.stdout = fixture("compose-config-split.json");

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(topology.serviceNames()).hasSize(9)
                .containsAll(BASELINE_8)
                .contains("tournamentquiz-service");
    }

    @Test
    @DisplayName("captures container name, published port and healthcheck presence")
    void capturesServiceMetadata() {
        runner.stdout = fixture("compose-config-v0.json");

        DeployService tournament = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of)
                .services().stream().filter(s -> s.composeService().equals("tournament-service")).findFirst().orElseThrow();

        assertThat(tournament.containerName()).isEqualTo("tournament-service");
        assertThat(tournament.springProfile()).isEqualTo("tournament-service");
        assertThat(tournament.ports()).containsExactly(8087);
        assertThat(tournament.hasHealthcheck()).isTrue();
    }

    @Test
    @DisplayName("Jaeger names are unsuffixed, so Monitor queries spring.application.name")
    void jaegerNamesAreUnsuffixed() {
        runner.stdout = fixture("compose-config-merged.json");

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(topology.jaegerServiceNames())
                .contains("tournamentanswer", "quiz", "user")
                .doesNotContain("tournamentanswer-service", "quiz-service");
    }

    @Test
    @DisplayName("reads spring.application.name from the real application-*.yaml files")
    void resolvesJaegerNamesFromRealResources() {
        Path repoRoot = Path.of("..", "..").toAbsolutePath().normalize();
        assertThat(repoRoot.resolve(config.getAppResourcesPath())).as("repo layout").exists();
        config.setProjectRoot(repoRoot.toString());
        runner.stdout = fixture("compose-config-v0.json");

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        // Suffix-stripping happens to agree for these, but the value must come from the file:
        // it is the only source that stays correct when a service is renamed.
        assertThat(topology.jaegerServiceNames())
                .containsExactlyInAnyOrder("answer", "course", "execution", "question",
                        "quiz", "topic", "tournament", "user");
    }

    @Test
    @DisplayName("the monolith reports the base application name, which suffix-stripping gets wrong")
    void monolithJaegerNameComesFromBaseApplicationYaml() {
        Path repoRoot = Path.of("..", "..").toAbsolutePath().normalize();
        config.setProjectRoot(repoRoot.toString());
        runner.stdout = fixture("compose-config-v0.json");

        DeployService monolith = resolver.resolve("quizzes-local", true, List::of)
                .services().stream().filter(s -> s.composeService().equals("quizzes-local")).findFirst().orElseThrow();

        // quizzes-local runs profiles "sagas,local" — no *-service profile — so the name falls
        // back to application.yaml. Stripping "-service" would have yielded "quizzes-local".
        assertThat(monolith.jaegerServiceName()).isEqualTo("quizzes");
    }

    // ─── Explicit deploy-target ──────────────────────────────────────────────

    @Test
    @DisplayName("an explicit monolith target is enriched from compose and needs no healthcheck")
    void explicitMonolithTarget() {
        runner.stdout = fixture("compose-config-v0.json");

        DeployTopology topology = resolver.resolve("quizzes-local", true, List::of);

        assertThat(topology.source()).isEqualTo(DeployTopology.Source.EXPLICIT);
        assertThat(topology.serviceNames()).containsExactly("quizzes-local");
        // quizzes-local declares no healthcheck, so the deploy wait must accept "running"
        // instead of demanding "healthy" — which it could never report.
        assertThat(topology.services().stream().filter(s -> s.composeService().equals("quizzes-local")).findFirst().orElseThrow().hasHealthcheck()).isFalse();
    }

    @Test
    @DisplayName("an unknown explicit target fails fast instead of timing out in the health wait")
    void explicitUnknownTargetFailsFast() {
        runner.stdout = fixture("compose-config-v0.json");

        // Must NOT be absorbed by the fallback ladder: re-adding the bogus name would only defer
        // the failure to `docker compose ps`, which reports it on stderr and leaves the health
        // wait spinning for its full timeout with no explanation.
        assertThatThrownBy(() -> resolver.resolve("no-such-service", true, List::of))
                .isInstanceOf(ComposeTopologyResolver.UnknownComposeServiceException.class)
                .hasMessageContaining("no-such-service");
    }

    // ─── Failure handling ────────────────────────────────────────────────────

    @Test
    @DisplayName("a compose failure degrades to the last deployed list, never to empty")
    void fallsBackToPersistedList() {
        runner.exitCode = 1;
        runner.stderr = "no configuration file provided";

        DeployTopology topology = resolver.resolve(
                ComposeTopologyResolver.AUTO, true, () -> List.of("tournamentanswer-service", "user-service"));

        assertThat(topology.source()).isEqualTo(DeployTopology.Source.FALLBACK);
        assertThat(topology.serviceNames()).containsExactly("tournamentanswer-service", "user-service");
    }

    @Test
    @DisplayName("with no prior topology at all, the configured fallback keeps the list non-empty")
    void fallsBackToConfiguredList() {
        runner.exitCode = 1;

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        // An empty list passed to `docker compose up` would start the ENTIRE project, including
        // both monoliths on port 8080 and every test-* container running `mvn test`.
        assertThat(topology.isEmpty()).isFalse();
        assertThat(topology.serviceNames()).containsExactlyElementsOf(BASELINE_8);
    }

    @Test
    @DisplayName("a previously resolved topology survives a later compose failure")
    void reusesCacheOnFailure() {
        runner.stdout = fixture("compose-config-merged.json");
        List<String> merged = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of).serviceNames();

        runner.exitCode = 1;
        runner.stdout = "";
        DeployTopology afterFailure = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(afterFailure.serviceNames()).isEqualTo(merged);
    }

    @Test
    @DisplayName("a fallback topology is returned but never cached, so the next read retries discovery")
    void fallbackIsNotCached() {
        runner.exitCode = 1;
        assertThat(resolver.current().serviceNames())
                .as("configured fallback — still lists question-service").containsExactlyElementsOf(BASELINE_8);

        // compose recovers; the merged tree must be read, not the guessed list served from cache
        runner.exitCode = 0;
        runner.stdout = fixture("compose-config-merged.json");
        assertThat(resolver.current().serviceNames()).contains("tournamentanswer-service");
        assertThat(runner.invocations).hasSize(2);
    }

    // ─── Caching ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("current() caches, and invalidate() forces a re-read after the tree changes")
    void cacheAndInvalidate() {
        runner.stdout = fixture("compose-config-v0.json");
        assertThat(resolver.current().serviceNames()).containsExactlyElementsOf(BASELINE_8);
        assertThat(runner.invocations).hasSize(1);

        resolver.current();
        assertThat(runner.invocations).as("second read is served from cache").hasSize(1);

        // A checkout swapped the compose file underneath us.
        runner.stdout = fixture("compose-config-merged.json");
        resolver.invalidate();
        assertThat(resolver.current().serviceNames()).contains("tournamentanswer-service");
        assertThat(runner.invocations).hasSize(2);
    }

    @Test
    @DisplayName("discovery runs from the project root so compose resolves its relative include")
    void invokesComposeConfigCorrectly() {
        runner.stdout = fixture("compose-config-v0.json");

        resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(runner.invocations).singleElement().satisfies(call -> {
            assertThat(call.command()).containsExactly("docker", "compose",
                    "-f", "applications/quizzes/docker-compose.yml", "config", "--format", "json");
            assertThat(call.workingDir()).isEqualTo(config.getProjectRoot());
        });
    }

    @Test
    @DisplayName("throwing off the fallback supplier does not break resolution")
    void survivesFallbackSupplierFailure() {
        runner.exitCode = 1;

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, () -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThat(topology.serviceNames()).containsExactlyElementsOf(BASELINE_8);
    }

    @Test
    @DisplayName("an excluded service is dropped from an otherwise-discovered topology")
    void honoursExclude() {
        runner.stdout = fixture("compose-config-v0.json");
        config.setExclude("answer-service");

        DeployTopology topology = resolver.resolve(ComposeTopologyResolver.AUTO, true, List::of);

        assertThat(topology.serviceNames()).hasSize(7).doesNotContain("answer-service");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/topology/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns canned output instead of shelling out to docker. */
    private static class StubProcessRunner extends ProcessRunner {
        record Invocation(List<String> command, String workingDir) {}

        final List<Invocation> invocations = new ArrayList<>();
        String stdout = "";
        String stderr = "";
        int exitCode = 0;

        @Override
        public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv) {
            invocations.add(new Invocation(List.copyOf(command), workingDir));
            return new ProcessResult(exitCode, stdout, stderr);
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }
    }
}
