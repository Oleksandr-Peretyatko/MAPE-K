package org.anonymous.msrefactor.rt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.constants.ClaudeConstants;

import java.util.List;

/**
 * Runtime view of {@link ClaudeConstants}.
 *
 * <p>Every field defaults to its constant and is overridable through the environment —
 * {@code RT_CLAUDE_MUTATOR_MAX_TURNS=300}, {@code RT_CLAUDE_CLI_MODEL=claude-opus-5} — via Spring
 * Boot relaxed binding. Nothing is declared in {@code application.yml} on purpose: the value must
 * appear exactly once in the repo, in the constant.
 */
@Component
@ConfigurationProperties(prefix = "rt.claude")
@Data
public class ClaudeConfig {

    /** Model for the claude CLI phases (discovery). */
    private String cliModel = ClaudeConstants.CLI_MODEL;

    /** Execute-phase mutator turn budget — the one that fails an iteration when exhausted. */
    private int mutatorMaxTurns = ClaudeConstants.MUTATOR_MAX_TURNS;

    private int discoveryMaxTurns = ClaudeConstants.DISCOVERY_MAX_TURNS;
    private int oneshotMaxTurns = ClaudeConstants.ONESHOT_MAX_TURNS;

    private List<String> allowedTools = ClaudeConstants.ALLOWED_TOOLS;
    private List<String> discoveryTools = ClaudeConstants.DISCOVERY_TOOLS;
    private List<String> binaryCandidatePaths = ClaudeConstants.BINARY_CANDIDATE_PATHS;

    private int statusCacheSeconds = ClaudeConstants.STATUS_CACHE_SECONDS;
    private int statusProbeTimeoutSeconds = ClaudeConstants.STATUS_PROBE_TIMEOUT_SECONDS;
}
