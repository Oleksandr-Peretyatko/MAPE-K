package org.anonymous.msrefactor.rt.execute.dto;

import java.time.Instant;

/**
 * Result of {@code GET /claude/status}: whether the Claude session rt-app uses is usable.
 *
 * @param state      one of the {@code STATE_*} constants
 * @param message    human-readable detail; the trimmed CLI output when the check failed
 * @param authMethod as reported by {@code claude auth status} (e.g. {@code claude.ai}), null if unknown
 * @param account    account email reported by {@code claude auth status}, null if unknown
 * @param claudePath resolved claude binary, null when not found
 * @param checkedAt  when the CLI was actually asked
 * @param cached     true when this is a reused result younger than the cache TTL
 */
public record ClaudeAuthStatus(String state, String message, String authMethod,
                               String account, String claudePath, Instant checkedAt, boolean cached) {

    public static final String STATE_OK = "OK";
    public static final String STATE_NOT_LOGGED_IN = "NOT_LOGGED_IN";
    public static final String STATE_BINARY_MISSING = "BINARY_MISSING";
    public static final String STATE_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATE_ERROR = "ERROR";

    public ClaudeAuthStatus asCached() {
        return new ClaudeAuthStatus(state, message, authMethod, account, claudePath, checkedAt, true);
    }
}
