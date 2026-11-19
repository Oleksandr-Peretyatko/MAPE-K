package org.anonymous.msrefactor.rt.constants;

/**
 * Domain and protocol values — collected here so the constants folder is the single place to
 * look, but <b>deliberately not configurable</b>: none of these has a {@code ClaudeConfig}-style
 * bean or an environment variable behind it.
 *
 * <p>They are persisted enum values and prompt-contract markers. Making them tunable would only
 * allow corrupting the database or desynchronising the parser from the prompt that produces the
 * text it parses. If you are here to "finish the job" by wiring these to config: don't.
 */
public final class ProtocolConstants {

    // ─── Persisted status values ──────────────────────────────────────────────
    // Written to and read from the DB. Changing one orphans every existing row.

    /** Plan document provenance. */
    public static final String KIND_ORIGINAL = "ORIGINAL";
    public static final String KIND_ENRICHED = "ENRICHED";
    public static final String KIND_USER_EDIT = "USER_EDIT";

    /** Plan approval state. */
    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_APPROVED = "APPROVED";
    public static final String APPROVAL_REJECTED = "REJECTED";

    // ─── Enricher output markers ──────────────────────────────────────────────
    // Must match the literals in the enricher prompt, which is code, not configuration.

    public static final String INVOCATION_START = "<!-- SKILL_INVOCATION -->";
    public static final String DOCUMENT_START = "<!-- PLAN_DOCUMENT -->";
    public static final String OUTPUT_END = "<!-- END -->";

    // ─── CLAUDE.md log-entry markers ──────────────────────────────────────────
    // Every skill run appends one entry to the target module's CLAUDE.md, so the file accumulates
    // across runs. These delimit a single entry, which is what bounds the search for the markers
    // below: a block outside the newest entry belongs to an earlier run and is not this run's verdict.

    public static final String LOG_ENTRY_START = "<!-- refactor-log -->";
    public static final String LOG_ENTRY_END = "<!-- end-refactor-log -->";

    /** Entry outcome that makes a stop verdict incoherent — the skill changed code. */
    public static final String RESULT_CHANGED = "CHANGED";

    // ─── Execute-phase restriction markers ────────────────────────────────────
    // Emitted by a refactoring skill into the target module's CLAUDE.md when the operation turns out
    // not to be applicable, and read back by ExecuteService to persist an architect restriction.
    // Must match the literals in refactor-merge-aggregate/SKILL.md.

    public static final String RESTRICTION_START = "<!-- REFACTOR_RESTRICTION -->";
    public static final String RESTRICTION_END = "<!-- END_REFACTOR_RESTRICTION -->";

    /**
     * Emitted instead of a restriction when an operation is not applicable <i>yet</i>: the pair is
     * reachable through a sequence of other operations. Carries a {@code chain} field naming that
     * sequence, which becomes the candidate's {@code dependencies}.
     */
    public static final String PREREQUISITE_START = "<!-- REFACTOR_PREREQUISITE -->";
    public static final String PREREQUISITE_END = "<!-- END_REFACTOR_PREREQUISITE -->";

    // ─── Singleton row ids ────────────────────────────────────────────────────

    /** Primary key of the single-row {@code analysis_config} and project config tables. */
    public static final int SINGLETON_ID = 1;

    private ProtocolConstants() {}
}
