package org.anonymous.msrefactor.rt.constants;

import java.util.List;

/**
 * Every Claude-related parameter used by the refactor tool, in one place.
 *
 * <p>These are the <b>defaults</b>. Each one is mirrored by a field on
 * {@code ClaudeConfig} ({@code @ConfigurationProperties(prefix = "rt.claude")}), so any of them
 * can be overridden at runtime through the matching environment variable — e.g.
 * {@code RT_CLAUDE_MUTATOR_MAX_TURNS=300} — with no rebuild and no entry in {@code application.yml}.
 * Spring Boot's relaxed binding does the name conversion.
 *
 * <p>Before this class existed the turn budget was written in five places with five different
 * values and the model id in two places that disagreed with each other.
 */
public final class ClaudeConstants {

    // ─── Models ───────────────────────────────────────────────────────────────

    /** Model used by the claude CLI phases (discovery). */
    public static final String CLI_MODEL = "claude-opus-4-8";

    // ─── Turn budgets ─────────────────────────────────────────────────────────

    /**
     * Execute-phase mutator. A skill that inventories the whole source tree before mutating
     * (merge-aggregate) needs well over a hundred turns; running out fails the iteration and
     * discards every edit made so far.
     */
    public static final int MUTATOR_MAX_TURNS = 200;

    /** Discovery scan (read-only tools over the source tree). */
    public static final int DISCOVERY_MAX_TURNS = 20;

    /** Health-check one-shot prompt. */
    public static final int ONESHOT_MAX_TURNS = 10;

    // ─── CLI invocation ───────────────────────────────────────────────────────

    /** Tools granted to the Execute-phase mutator run. */
    public static final List<String> ALLOWED_TOOLS =
            List.of("Bash", "Write", "Edit", "Read", "Glob", "Grep");

    /** Read-only tool set for the discovery run. */
    public static final List<String> DISCOVERY_TOOLS =
            List.of("Read", "Glob", "Grep", "Bash");

    /** Searched in order to locate the claude binary. */
    public static final List<String> BINARY_CANDIDATE_PATHS =
            List.of("claude", "/usr/local/bin/claude", "/usr/bin/claude");

    // ─── Session status check ─────────────────────────────────────────────────

    /** How long a {@code GET /claude/status} result is reused before the CLI is asked again. */
    public static final int STATUS_CACHE_SECONDS = 300;

    /** Kill the status probe after this long; a hung CLI must not hang the navbar. */
    public static final int STATUS_PROBE_TIMEOUT_SECONDS = 60;

    private ClaudeConstants() {}
}
