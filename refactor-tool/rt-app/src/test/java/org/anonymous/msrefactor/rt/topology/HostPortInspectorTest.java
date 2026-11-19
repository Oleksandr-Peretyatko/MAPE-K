package org.anonymous.msrefactor.rt.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.topology.HostPortInspector.HostPortBinding;
import org.anonymous.msrefactor.rt.topology.HostPortInspector.PortConflict;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fixtures are the two outputs the inspector reads. {@code compose-config-split.json} is a split
 * that gave its satellite ({@code tournamentquiz-service}) host port 8090 — v6's mistake — and
 * {@code docker-ps-foreign.txt} is this host with the refactor tool's stack running.
 */
class HostPortInspectorTest {

    private static final List<String> SPLIT_ROOTS = List.of("gateway", "answer-service", "course-service",
            "execution-service", "question-service", "quiz-service", "topic-service",
            "tournament-service", "tournamentquiz-service", "user-service");

    private StubProcessRunner runner;
    private HostPortInspector inspector;

    @BeforeEach
    void setUp() {
        runner = new StubProcessRunner();
        runner.composeConfig = fixture("compose-config-split.json");
        runner.dockerPs = fixture("docker-ps-foreign.txt");
        TopologyConfig config = new TopologyConfig();
        config.setComposeFile("applications/quizzes/docker-compose.yml");
        inspector = new HostPortInspector(runner, config, new ObjectMapper());
    }

    @Test
    @DisplayName("a port another compose project publishes is a conflict — v6's 8090 against rt-app")
    void reportsThePortRtAppHolds() {
        List<PortConflict> conflicts = inspector.findConflicts(SPLIT_ROOTS);

        assertThat(conflicts).containsExactly(new PortConflict(
                "tournamentquiz-service", 8090, "tcp", "rt-app", "refactor-tool", false));
        assertThat(conflicts.get(0).describe()).isEqualTo("host port 8090/tcp of tournamentquiz-service is "
                + "already published by container 'rt-app' (compose project 'refactor-tool')");
    }

    @Test
    @DisplayName("the project's own containers are not a conflict — the redeploy takes them down first")
    void ownProjectIsNotAConflict() {
        List<PortConflict> conflicts = inspector.findConflicts(List.of("gateway", "tournament-service"));

        assertThat(conflicts).as("tournament-service holds 8087 today, and will again").isEmpty();
    }

    @Test
    @DisplayName("only what compose up starts is checked: roots and their depends_on, transitively")
    void checksOnlyWhatStarts() {
        // postgres publishes 5432 and quizzes-local 8080, but neither runs with the microservices.
        runner.dockerPs = "squatter\tother\t0.0.0.0:5432->5432/tcp";

        assertThat(inspector.findConflicts(SPLIT_ROOTS)).isEmpty();

        // jaeger is not a root, but every service depends on it.
        runner.dockerPs = "squatter\tother\t0.0.0.0:16686->16686/tcp";
        assertThat(inspector.findConflicts(List.of("gateway")))
                .extracting(PortConflict::composeService).containsExactly("jaeger");
    }

    @Test
    @DisplayName("the same port claimed twice within the project is a conflict too")
    void duplicateWithinTheProject() {
        runner.dockerPs = "";
        runner.composeConfig = """
                {"name":"microservices-simulator","services":{
                  "tournament-service":{"ports":[{"published":"8087","target":8087,"protocol":"tcp"}]},
                  "tournament-satellite-service":{"ports":[{"published":"8087","target":8087}]}}}""";

        assertThat(inspector.findConflicts(List.of("tournament-service", "tournament-satellite-service")))
                .containsExactly(new PortConflict("tournament-satellite-service", 8087, "tcp",
                        "tournament-service", "microservices-simulator", true));
    }

    @Test
    @DisplayName("docker ps: ranges expand, IPv4/IPv6 collapse, exposed-only and empty lines are skipped")
    void parsesDockerPs() {
        List<HostPortBinding> bindings = HostPortInspector.parseDockerPs(fixture("docker-ps-foreign.txt"));

        assertThat(bindings).contains(
                new HostPortBinding(8090, "tcp", "rt-app", "refactor-tool"),
                new HostPortBinding(5433, "tcp", "rt-db", "refactor-tool"),
                new HostPortBinding(4317, "tcp", "jaeger", "microservices-simulator"),
                new HostPortBinding(4318, "tcp", "jaeger", "microservices-simulator"),
                new HostPortBinding(9000, "tcp", "stray", ""),
                new HostPortBinding(9001, "tcp", "stray", ""));
        assertThat(bindings).filteredOn(b -> b.holder().equals("rt-app")).hasSize(1);
        assertThat(bindings).noneMatch(b -> b.protocol().equals("udp"));
        assertThat(bindings).noneMatch(b -> b.holder().startsWith("buildx_buildkit"));
    }

    @Test
    @DisplayName("foreign bindings are everything outside the project, unlabelled containers included")
    void foreignBindings() {
        assertThat(inspector.foreignBindings())
                .extracting(HostPortBinding::holder)
                .containsOnly("rt-app", "locust-service", "rt-db", "stray");
    }

    @Test
    @DisplayName("an inspection that cannot be done says so, rather than reporting no conflict")
    void failuresThrow() {
        runner.psExit = 1;
        assertThatThrownBy(() -> inspector.findConflicts(SPLIT_ROOTS))
                .isInstanceOf(HostPortInspector.InspectionException.class).hasMessageContaining("docker ps");

        runner.psExit = 0;
        runner.configExit = 1;
        assertThatThrownBy(() -> inspector.foreignBindings())
                .isInstanceOf(HostPortInspector.InspectionException.class).hasMessageContaining("compose config");

        runner.configExit = 0;
        runner.composeConfig = "{\"services\":{}}";
        assertThatThrownBy(() -> inspector.foreignBindings())
                .isInstanceOf(HostPortInspector.InspectionException.class).hasMessageContaining("project name");
    }

    @Test
    @DisplayName("the prompt section lists each reserved port once, in order, with its holders")
    void promptBlock() {
        assertThat(HostPortInspector.promptBlock(List.of())).isEmpty();

        String block = HostPortInspector.promptBlock(inspector.foreignBindings());
        assertThat(block).startsWith("## Reserved host ports\n")
                .endsWith("5433 (rt-db), 8090 (rt-app), 8100 (locust-service), 9000 (stray), 9001 (stray)\n");
    }

    private String fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/topology/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code docker compose … config} and {@code docker ps} answer from their own fixture. */
    private static class StubProcessRunner extends ProcessRunner {
        String composeConfig = "";
        String dockerPs = "";
        int configExit = 0;
        int psExit = 0;

        @Override
        public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv) {
            if (command.contains("config")) return new ProcessResult(configExit, composeConfig, "boom");
            if (command.size() > 1 && command.get(1).equals("ps")) {
                // ProcessRunner trims what it captures, as the real one does.
                return new ProcessResult(psExit, dockerPs.strip(), "boom");
            }
            throw new AssertionError("unexpected command " + command);
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }
    }
}
