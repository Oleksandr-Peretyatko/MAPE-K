package org.anonymous.msrefactor.rt.execute;

import lombok.extern.slf4j.Slf4j;
import org.anonymous.msrefactor.rt.constants.ProtocolConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the restriction block a refactoring skill writes when it finds its operation inapplicable.
 *
 * <p>The skill appends the block to the target module's {@code CLAUDE.md}, between
 * {@link ProtocolConstants#RESTRICTION_START} and {@link ProtocolConstants#RESTRICTION_END}:
 *
 * <pre>
 * &lt;!-- REFACTOR_RESTRICTION --&gt;
 * operation: MERGE_AGGREGATES
 * targetService: question
 * secondaryTarget: course
 * blocking: true
 * reason: Course's lifecycle is created and owned by execution …
 * &lt;!-- END_REFACTOR_RESTRICTION --&gt;
 * </pre>
 *
 * <h2>Scope: the current run's entry only</h2>
 *
 * <p>{@code CLAUDE.md} accumulates one {@code <!-- refactor-log -->} entry per run, so the file
 * outlives any single execution. The search is therefore bounded to the <b>newest entry</b>, and a
 * block is honoured only if that entry does not report {@code Result: CHANGED}.
 *
 * <p>Both rules exist because of a real failure: a successful {@code merge-aggregate course question}
 * run emitted no block, an earlier {@code tournament answer} entry had one, and a whole-file
 * {@code lastIndexOf} handed that stale block back as the current verdict — persisting an unrelated
 * blocking restriction and recording a 137-file change as {@code NOT_APPLICABLE}. A block belongs to
 * the run whose entry contains it, and a run that changed code has no stop verdict to give.
 */
@Slf4j
public final class RestrictionParser {

    /** A restriction: the pair is not mergeable at all. Requires {@code reason}. */
    public static Optional<Map<String, String>> parse(String claudeMd) {
        return parseBlock(claudeMd, ProtocolConstants.RESTRICTION_START,
                ProtocolConstants.RESTRICTION_END, "reason");
    }

    /**
     * A prerequisite: the pair is not mergeable <i>yet</i>, but a sequence of other operations makes
     * it so. Requires {@code chain} — without the sequence the block says nothing a restriction does
     * not already say.
     */
    public static Optional<Map<String, String>> parsePrerequisite(String claudeMd) {
        return parseBlock(claudeMd, ProtocolConstants.PREREQUISITE_START,
                ProtocolConstants.PREREQUISITE_END, "chain");
    }

    /** Field values are single-line; a value spanning lines is joined with spaces. */
    private static Optional<Map<String, String>> parseBlock(String claudeMd, String startMarker,
                                                            String endMarker, String requiredField) {
        if (claudeMd == null || claudeMd.isBlank()) return Optional.empty();

        String entry = currentEntry(claudeMd);
        if (entry == null) return Optional.empty();

        int start = entry.lastIndexOf(startMarker);
        if (start < 0) return Optional.empty();
        int end = entry.indexOf(endMarker, start);
        if (end < 0) return Optional.empty();

        // A run that changed code has no stop verdict to give. Loud, because it means the skill
        // contradicted itself rather than the pipeline mis-reading an old entry.
        if (declaresChange(entry)) {
            log.warn("[restriction] ignoring a {} block in a '{}' log entry — a run that changed code "
                     + "cannot also declare its operation inapplicable", startMarker,
                    ProtocolConstants.RESULT_CHANGED);
            return Optional.empty();
        }

        String body = entry.substring(start + startMarker.length(), end);

        Map<String, String> fields = new LinkedHashMap<>();
        String currentKey = null;
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            // A line without a colon, or whose prefix contains spaces, continues the previous value
            // — Claude wraps long `reason` text rather than emitting one very long line.
            boolean isNewField = colon > 0 && !line.substring(0, colon).contains(" ");
            if (isNewField) {
                currentKey = line.substring(0, colon).strip();
                fields.put(currentKey, line.substring(colon + 1).strip());
            } else if (currentKey != null) {
                fields.merge(currentKey, line, (a, b) -> (a + " " + b).strip());
            }
        }

        // A block missing its required field is unusable — `reason` is NOT NULL on refactor_constraint
        // and is the only part a human reads later; `chain` is the whole point of a prerequisite.
        if (!fields.containsKey(requiredField) || fields.get(requiredField).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(fields);
    }

    /**
     * The newest {@code <!-- refactor-log -->} entry — the one this run just appended.
     *
     * <p>Returns {@code null} when the file has no entry at all, which is itself a signal: the skill
     * is required to write one on every run, so its absence means the run did not complete its
     * protocol. Falling back to scanning the whole file there would resurrect the original bug.
     *
     * <p>An entry missing its closing marker (a truncated write) runs to end-of-file rather than
     * being discarded — the block, if any, is still this run's.
     */
    static String currentEntry(String claudeMd) {
        int start = claudeMd.lastIndexOf(ProtocolConstants.LOG_ENTRY_START);
        if (start < 0) return null;
        int end = claudeMd.indexOf(ProtocolConstants.LOG_ENTRY_END, start);
        return end < 0 ? claudeMd.substring(start) : claudeMd.substring(start, end);
    }

    /**
     * Whether the entry reports {@code Result: CHANGED}.
     *
     * <p>The skill's three outcomes are {@code CHANGED | NO_CHANGE | REJECTED}. Only {@code CHANGED}
     * is checked: a stop verdict is coherent on the other two, and an entry with no {@code Result:}
     * line at all is malformed in a way that should not by itself suppress the verdict.
     */
    private static boolean declaresChange(String entry) {
        for (String rawLine : entry.split("\\R")) {
            String line = rawLine.strip();
            if (!line.regionMatches(true, 0, "Result:", 0, "Result:".length())) continue;
            return line.substring("Result:".length()).strip()
                    .equalsIgnoreCase(ProtocolConstants.RESULT_CHANGED);
        }
        return false;
    }

    private RestrictionParser() {}
}
