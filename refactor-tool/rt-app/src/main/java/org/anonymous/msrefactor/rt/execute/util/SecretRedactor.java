package org.anonymous.msrefactor.rt.execute.util;

import java.util.regex.Pattern;

/**
 * Masks credentials in text bound for a log: the application log, the execute log persisted in
 * the database, or an exception message. Covers URL userinfo ({@code https://<token>@host}),
 * GitHub token formats, and the literal value of {@code GITHUB_TOKEN}.
 */
public final class SecretRedactor {

    public static final String MASK = "***";

    private static final Pattern URL_USERINFO = Pattern.compile("(https?://)[^/\\s@]+@");
    private static final Pattern GITHUB_TOKENS = Pattern.compile(
            "github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9]{20,}");

    private SecretRedactor() {}

    public static String redact(String text) {
        return redact(text, System.getenv("GITHUB_TOKEN"));
    }

    static String redact(String text, String literalSecret) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        if (literalSecret != null && !literalSecret.isBlank()) {
            out = out.replace(literalSecret, MASK);
        }
        out = URL_USERINFO.matcher(out).replaceAll("$1" + MASK + "@");
        return GITHUB_TOKENS.matcher(out).replaceAll(MASK);
    }
}
