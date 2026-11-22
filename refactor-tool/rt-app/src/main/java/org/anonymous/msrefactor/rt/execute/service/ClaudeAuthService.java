package org.anonymous.msrefactor.rt.execute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.config.ClaudeConfig;
import org.anonymous.msrefactor.rt.execute.dto.ClaudeAuthStatus;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.execute.util.SecretRedactor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.anonymous.msrefactor.rt.execute.dto.ClaudeAuthStatus.*;

/**
 * Tells whether the Claude session rt-app depends on is usable, without side effects.
 *
 * <p>Two steps. {@code claude auth status} reads the mounted credentials and costs nothing, so a
 * missing login is caught there. It cannot tell an expired or revoked token from a live one, and
 * {@code ~/.claude} is mounted read-only so the container can never refresh it; a one-turn
 * {@code claude -p} probe therefore follows and settles it.
 *
 * <p>Results are cached for {@code rt.claude.status-cache-seconds} so the navbar can ask on every
 * page load; {@code refresh=true} bypasses the cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeAuthService {

    static final String PROBE_PROMPT = "Reply with exactly: OK";
    static final String PROBE_MODEL = "haiku";
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final List<String> AUTH_MARKERS = List.of(
            "not logged in", "/login", "login", "oauth", "unauthorized", "401",
            "invalid api key", "authentication", "expired", "credential");
    private static final List<String> OVERLOAD_MARKERS = List.of("529", "overloaded", "rate limit");

    private final ClaudeConfig claudeConfig;
    private final ClaudeCommandMutator claudeCommandMutator;
    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;

    /** Test seam. */
    Clock clock = Clock.systemUTC();

    private volatile ClaudeAuthStatus last;

    public ClaudeAuthStatus check(boolean refresh) {
        ClaudeAuthStatus cached = last;
        if (!refresh && cached != null
                && clock.instant().isBefore(cached.checkedAt().plusSeconds(claudeConfig.getStatusCacheSeconds()))) {
            return cached.asCached();
        }
        synchronized (this) {
            ClaudeAuthStatus fresh = checkCommand();
            log.info("[claude-status] state={}", fresh.state());
            last = fresh;
            return fresh;
        }
    }

    private ClaudeAuthStatus checkCommand() {
        String bin;
        try {
            bin = claudeCommandMutator.resolveBinary();
        } catch (IllegalStateException e) {
            return status(STATE_BINARY_MISSING, e.getMessage(), null, null, null);
        }

        String authMethod = null;
        String account = null;
        try {
            ProcessResult auth = runCli(List.of(bin, "auth", "status"));
            JsonNode json = parseJson(auth.stdout());
            if (json != null && json.has("loggedIn")) {
                authMethod = text(json, "authMethod");
                account = text(json, "email");
                if (!json.get("loggedIn").asBoolean()) {
                    return status(STATE_NOT_LOGGED_IN,
                            "claude is not logged in. Run `claude login` on the host, then check again.",
                            authMethod, account, bin);
                }
            }
            // No parsable answer (older CLI without `auth status`): the probe below decides.
        } catch (Exception e) {
            log.warn("[claude-status] `claude auth status` failed: {}", e.getMessage());
        }

        ProcessResult probe;
        try {
            probe = runCli(probeCommand(bin));
        } catch (Exception e) {
            return status(STATE_ERROR, "Could not run claude: " + e.getMessage(), authMethod, account, bin);
        }
        if (probe.timedOut()) {
            return status(STATE_UNAVAILABLE,
                    "claude did not answer within " + claudeConfig.getStatusProbeTimeoutSeconds() + "s.",
                    authMethod, account, bin);
        }

        JsonNode json = parseJson(probe.stdout());
        boolean isError = json == null || !json.has("is_error") || json.get("is_error").asBoolean();
        if (probe.succeeded() && !isError) {
            return status(STATE_OK, "claude answered the probe.", authMethod, account, bin);
        }
        String detail = json != null && json.hasNonNull("result") ? json.get("result").asText() : probe.combined();
        return status(classify(detail), detail, authMethod, account, bin);
    }

    /**
     * The default system prompt, tools and MCP servers add ~37k input tokens to a one-word reply;
     * stripped of them the probe costs ~600 on the smallest model. {@code --bare} would be cheaper
     * still but ignores OAuth, which is what is being checked.
     */
    static List<String> probeCommand(String bin) {
        return List.of(bin, "-p", PROBE_PROMPT,
                "--output-format", "json",
                "--max-turns", "1",
                "--model", PROBE_MODEL,
                "--tools", "",
                "--system-prompt", "You are a health check.",
                "--strict-mcp-config");
    }

    private ProcessResult runCli(List<String> cmd) throws Exception {
        return processRunner.runStreaming(cmd, System.getProperty("user.dir"), Map.of(),
                Duration.ofSeconds(claudeConfig.getStatusProbeTimeoutSeconds()), null);
    }

    static String classify(String output) {
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (AUTH_MARKERS.stream().anyMatch(lower::contains)) return STATE_NOT_LOGGED_IN;
        if (OVERLOAD_MARKERS.stream().anyMatch(lower::contains)) return STATE_UNAVAILABLE;
        return STATE_ERROR;
    }

    /** runStreaming merges stderr into stdout, so warnings may surround the JSON object. */
    private JsonNode parseJson(String output) {
        if (output == null) return null;
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readTree(output.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    private ClaudeAuthStatus status(String state, String message,
                                    String authMethod, String account, String claudePath) {
        String msg = SecretRedactor.redact(message == null ? "" : message.trim());
        if (msg.length() > MAX_MESSAGE_CHARS) msg = msg.substring(0, MAX_MESSAGE_CHARS) + "…";
        return new ClaudeAuthStatus(state, msg, authMethod, account, claudePath, clock.instant(), false);
    }
}
