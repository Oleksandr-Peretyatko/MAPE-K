package org.anonymous.msrefactor.rt.execute.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.config.ClaudeConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.topology.HostPortInspector;
import org.anonymous.msrefactor.rt.topology.HostPortInspector.HostPortBinding;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An operation's prompt tells the skill which host ports other compose projects hold, so a new
 * service is never given one. The section comes from rt-app, not from the skill text, because a run
 * starting from an older version reads that version's skills.
 */
class ReservedPortsPromptTest {

    private PromptCapturingRunner runner;
    private HostPortInspector inspector;
    private ClaudeCommandMutator mutator;

    @BeforeEach
    void setUp() {
        runner = new PromptCapturingRunner();
        inspector = mock(HostPortInspector.class);
        when(inspector.foreignBindings()).thenReturn(List.of(
                new HostPortBinding(8090, "tcp", "rt-app", "refactor-tool"),
                new HostPortBinding(8100, "tcp", "locust-service", "refactor-tool")));
        mutator = new ClaudeCommandMutator(new ClaudeConfig(), runner, new AppTargetConfig(),
                new ExecuteConfig(), inspector);
    }

    @Test
    @DisplayName("an operation's prompt lists the reserved ports, outside the plan section")
    void operationPromptCarriesTheSection() throws Exception {
        mutator.apply("split-aggregate tournament --core Tournament --satellite TournamentParticipant",
                null, "the plan", "/app/project/applications/quizzes", new StringBuilder(), 7);

        assertThat(runner.prompt).contains("8090 (rt-app), 8100 (locust-service)");
        int plan = runner.prompt.indexOf("## Refactoring Plan");
        int workingDir = runner.prompt.indexOf("Working directory:");
        int reserved = runner.prompt.indexOf("## Reserved host ports");
        assertThat(reserved).as("after the plan, not inside it").isGreaterThan(workingDir).isGreaterThan(plan);
        assertThat(reserved).as("before the final-step reminder")
                .isLessThan(runner.prompt.indexOf("test-gate --project-path"));
    }

    @Test
    @DisplayName("a prerequisite's prompt has no section — domain skills do not allocate ports")
    void prerequisiteHasNoSection() throws Exception {
        mutator.applyPrerequisite("new-event TournamentUpdatedEvent tournament quiz",
                "/app/project/applications/quizzes", new StringBuilder(), 7);

        assertThat(runner.prompt).doesNotContain("Reserved host ports");
    }

    @Test
    @DisplayName("without docker the prompt goes out without the section, and the run goes on")
    void inspectorFailureOmitsTheSection() throws Exception {
        when(inspector.foreignBindings()).thenThrow(new HostPortInspector.InspectionException("no docker"));

        mutator.apply("split-aggregate tournament --core Tournament --satellite TournamentParticipant",
                null, null, "/app/project/applications/quizzes", new StringBuilder(), 7);

        assertThat(runner.prompt).doesNotContain("Reserved host ports").contains("test-gate --project-path");
    }

    /** Answers {@code claude --version} and captures the {@code -p} argument of the run itself. */
    private static class PromptCapturingRunner extends ProcessRunner {
        String prompt;

        @Override
        public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv) {
            return new ProcessResult(0, "2.0.0 (Claude Code)", "");
        }

        @Override
        public ProcessResult run(List<String> command, String workingDir) {
            return run(command, workingDir, Map.of());
        }

        @Override
        public ProcessResult runStreaming(List<String> command, String workingDir, Consumer<String> lineConsumer) {
            prompt = command.get(command.indexOf("-p") + 1);
            return new ProcessResult(0, "", "");
        }
    }
}
