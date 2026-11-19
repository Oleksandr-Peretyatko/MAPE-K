package org.anonymous.msrefactor.rt.execute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.config.ClaudeConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.dto.ClaudeAuthStatus;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeAuthServiceTest {

    private static final String LOGGED_IN =
            "{\"loggedIn\": true, \"authMethod\": \"claude.ai\", \"email\": \"dev@example.com\"}";
    private static final String PROBE_OK =
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"OK\"}";

    private StubProcessRunner runner;
    private ClaudeAuthService service;

    @BeforeEach
    void setUp() {
        runner = new StubProcessRunner();
        ClaudeConfig claudeConfig = new ClaudeConfig();
        ClaudeCommandMutator mutator = new ClaudeCommandMutator(
                claudeConfig, runner, new AppTargetConfig(), new ExecuteConfig(),
                org.mockito.Mockito.mock(org.anonymous.msrefactor.rt.topology.HostPortInspector.class));
        service = new ClaudeAuthService(claudeConfig, mutator, runner, new ObjectMapper());
        service.clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("logged in and the probe answers → OK, with the account from auth status")
    void ok() {
        runner.auth = ok(LOGGED_IN);
        runner.probe = ok(PROBE_OK);

        ClaudeAuthStatus s = service.check(false);

        assertThat(s.state()).isEqualTo(ClaudeAuthStatus.STATE_OK);
        assertThat(s.authMethod()).isEqualTo("claude.ai");
        assertThat(s.account()).isEqualTo("dev@example.com");
        assertThat(s.cached()).isFalse();
        assertThat(runner.commands).contains(ClaudeAuthService.probeCommand("claude"));
    }

    @Test
    @DisplayName("auth status says loggedIn=false → NOT_LOGGED_IN without spending a probe")
    void loggedOutShortCircuits() {
        runner.auth = new ProcessResult(1, "{\"loggedIn\": false}", "");

        ClaudeAuthStatus s = service.check(false);

        assertThat(s.state()).isEqualTo(ClaudeAuthStatus.STATE_NOT_LOGGED_IN);
        assertThat(runner.probeCalls).isZero();
    }

    @Test
    @DisplayName("credentials present but the token is rejected → NOT_LOGGED_IN")
    void expiredToken() {
        runner.auth = ok(LOGGED_IN);
        runner.probe = new ProcessResult(1,
                "{\"type\":\"result\",\"is_error\":true,\"result\":\"Invalid API key · Please run /login\"}", "");

        ClaudeAuthStatus s = service.check(false);

        assertThat(s.state()).isEqualTo(ClaudeAuthStatus.STATE_NOT_LOGGED_IN);
        assertThat(s.message()).contains("Please run /login");
    }

    @Test
    @DisplayName("CLI without `auth status` falls back to the probe")
    void olderCliFallsBackToProbe() {
        runner.auth = new ProcessResult(1, "error: unknown command 'auth'", "");
        runner.probe = ok(PROBE_OK);

        assertThat(service.check(false).state()).isEqualTo(ClaudeAuthStatus.STATE_OK);
    }

    @Test
    @DisplayName("overloaded API → UNAVAILABLE; a hung CLI → UNAVAILABLE")
    void unavailable() {
        runner.auth = ok(LOGGED_IN);
        runner.probe = new ProcessResult(1, "API Error: 529 Overloaded", "");
        assertThat(service.check(true).state()).isEqualTo(ClaudeAuthStatus.STATE_UNAVAILABLE);

        runner.probe = new ProcessResult(ProcessResult.TIMEOUT_EXIT_CODE, "", "[TIMEOUT]");
        assertThat(service.check(true).state()).isEqualTo(ClaudeAuthStatus.STATE_UNAVAILABLE);
    }

    @Test
    @DisplayName("no claude binary → BINARY_MISSING")
    void binaryMissing() {
        runner.versionExit = 127;

        assertThat(service.check(false).state()).isEqualTo(ClaudeAuthStatus.STATE_BINARY_MISSING);
    }

    @Test
    @DisplayName("a second call inside the TTL is served from cache; refresh=true asks the CLI again")
    void cacheAndRefresh() {
        runner.auth = ok(LOGGED_IN);
        runner.probe = ok(PROBE_OK);

        service.check(false);
        ClaudeAuthStatus second = service.check(false);
        assertThat(second.cached()).isTrue();
        assertThat(runner.probeCalls).isEqualTo(1);

        ClaudeAuthStatus refreshed = service.check(true);
        assertThat(refreshed.cached()).isFalse();
        assertThat(runner.probeCalls).isEqualTo(2);
    }


    private static ProcessResult ok(String stdout) {
        return new ProcessResult(0, stdout, "");
    }

    // ─── Stub ────────────────────────────────────────────────────────────────

    private static class StubProcessRunner extends ProcessRunner {
        int versionExit = 0;
        ProcessResult auth = new ProcessResult(1, "", "");
        ProcessResult probe = new ProcessResult(1, "", "");
        int probeCalls = 0;
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv) {
            commands.add(command);
            return new ProcessResult(versionExit, "2.1.272 (Claude Code)", "");
        }

        @Override
        public ProcessResult runStreaming(List<String> command, String workingDir, Map<String, String> extraEnv,
                                          Duration timeout, Consumer<String> lineConsumer) {
            commands.add(command);
            if (command.contains("auth")) return auth;
            probeCalls++;
            return probe;
        }
    }
}
