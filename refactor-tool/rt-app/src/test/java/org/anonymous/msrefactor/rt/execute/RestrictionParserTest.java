package org.anonymous.msrefactor.rt.execute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract between a refactoring skill and the pipeline. The skill writes a block into the target
 * module's {@code CLAUDE.md}; everything the Execute phase then does — status, constraint, chain —
 * hangs off parsing it correctly.
 *
 * <p>Two blocks, two meanings: a <b>restriction</b> says the pair is not mergeable at all; a
 * <b>prerequisite</b> says it is not mergeable <i>yet</i> and names the sequence that would change
 * that. Confusing them would either bury a reachable pair or keep proposing an impossible one.
 */
class RestrictionParserTest {

    private static String log(String body) {
        return log("NO_CHANGE", body);
    }

    private static String log(String result, String body) {
        return "# CLAUDE.md\n\n<!-- refactor-log -->\nDate: 2026-08-14T18:40:00Z\n"
                + "Invocation: /refactor-merge-aggregate course question\nResult: " + result + "\n"
                + body + "\n<!-- end-refactor-log -->\n";
    }

    private static final String RESTRICTION = """
            <!-- REFACTOR_RESTRICTION -->
            operation: MERGE_AGGREGATES
            targetService: question
            secondaryTarget: user
            blocking: true
            reason: User is an aggregate root with no owner and owns no aggregate.
            <!-- END_REFACTOR_RESTRICTION -->""";

    private static final String PREREQUISITE = """
            <!-- REFACTOR_PREREQUISITE -->
            operation: MERGE_AGGREGATES
            targetService: question
            secondaryTarget: topic
            chain: course<-topic, course<-question
            reason: Topic and Question are both owned by Course.
            <!-- END_REFACTOR_PREREQUISITE -->""";

    @Nested
    @DisplayName("restriction")
    class Restrictions {

        @Test
        @DisplayName("fields are read from the block")
        void parsesFields() {
            Map<String, String> f = RestrictionParser.parse(log(RESTRICTION)).orElseThrow();
            assertThat(f).containsEntry("operation", "MERGE_AGGREGATES")
                         .containsEntry("targetService", "question")
                         .containsEntry("secondaryTarget", "user")
                         .containsEntry("blocking", "true");
            assertThat(f.get("reason")).startsWith("User is an aggregate root");
        }

        @Test
        @DisplayName("a wrapped reason is joined, not truncated at the line break")
        void joinsWrappedValues() {
            String wrapped = RESTRICTION.replace(
                    "reason: User is an aggregate root with no owner and owns no aggregate.",
                    "reason: User is an aggregate root with no owner\n and owns no aggregate.");
            assertThat(RestrictionParser.parse(log(wrapped)).orElseThrow().get("reason"))
                    .isEqualTo("User is an aggregate root with no owner and owns no aggregate.");
        }

        @Test
        @DisplayName("no reason means no usable restriction — the column is NOT NULL")
        void requiresReason() {
            String noReason = RESTRICTION.replace(
                    "reason: User is an aggregate root with no owner and owns no aggregate.\n", "");
            assertThat(RestrictionParser.parse(log(noReason))).isEmpty();
        }

        @Test
        @DisplayName("a prerequisite block is not mistaken for a restriction")
        void doesNotMatchPrerequisite() {
            assertThat(RestrictionParser.parse(log(PREREQUISITE))).isEmpty();
        }
    }

    @Nested
    @DisplayName("prerequisite")
    class Prerequisites {

        @Test
        @DisplayName("the chain is read verbatim, commas and all")
        void parsesChain() {
            Map<String, String> f = RestrictionParser.parsePrerequisite(log(PREREQUISITE)).orElseThrow();
            assertThat(f).containsEntry("chain", "course<-topic, course<-question")
                         .containsEntry("secondaryTarget", "topic");
        }

        @Test
        @DisplayName("no chain means no usable prerequisite — the chain is the whole point")
        void requiresChain() {
            String noChain = PREREQUISITE.replace("chain: course<-topic, course<-question\n", "");
            assertThat(RestrictionParser.parsePrerequisite(log(noChain))).isEmpty();
        }

        @Test
        @DisplayName("a restriction block is not mistaken for a prerequisite")
        void doesNotMatchRestriction() {
            assertThat(RestrictionParser.parsePrerequisite(log(RESTRICTION))).isEmpty();
        }
    }

    /**
     * The regression. A successful {@code merge-aggregate course question} run emitted no block, an
     * earlier {@code tournament answer} entry in the same file had one, and a whole-file
     * {@code lastIndexOf} handed that stale block back — persisting an unrelated blocking restriction
     * and recording a 137-file change as {@code NOT_APPLICABLE}.
     */
    @Nested
    @DisplayName("entry scope")
    class EntryScope {

        @Test
        @DisplayName("a previous run's block is not this run's verdict")
        void ignoresPreviousEntry() {
            String twoRuns = log("NO_CHANGE", RESTRICTION)
                           + log("CHANGED", "Summary: merged course and question.");
            assertThat(RestrictionParser.parse(twoRuns)).isEmpty();
            assertThat(RestrictionParser.parsePrerequisite(
                    log("NO_CHANGE", PREREQUISITE) + log("CHANGED", "Summary: merged."))).isEmpty();
        }

        @Test
        @DisplayName("when both entries carry a block, the current one is read")
        void prefersTheCurrentEntry() {
            String twoRuns = log("NO_CHANGE", RESTRICTION)
                           + log("NO_CHANGE", RESTRICTION.replace("user", "quiz"));
            assertThat(RestrictionParser.parse(twoRuns).orElseThrow())
                    .containsEntry("secondaryTarget", "quiz");
        }

        @Test
        @DisplayName("a run that changed code has no stop verdict to give")
        void ignoresBlockOnChangedRun() {
            assertThat(RestrictionParser.parse(log("CHANGED", RESTRICTION))).isEmpty();
            assertThat(RestrictionParser.parsePrerequisite(log("CHANGED", PREREQUISITE))).isEmpty();
        }

        @Test
        @DisplayName("REJECTED still carries a verdict — only CHANGED contradicts one")
        void honoursRejectedRun() {
            assertThat(RestrictionParser.parse(log("REJECTED", RESTRICTION)).orElseThrow())
                    .containsEntry("secondaryTarget", "user");
        }

        @Test
        @DisplayName("no log entry at all yields nothing, rather than scanning the whole file")
        void requiresALogEntry() {
            String noEntry = "# CLAUDE.md\n\nSome hand-written notes.\n" + RESTRICTION + "\n";
            assertThat(RestrictionParser.parse(noEntry)).isEmpty();
        }

        @Test
        @DisplayName("an entry truncated before its closing marker is still read")
        void toleratesUnterminatedEntry() {
            String truncated = "# CLAUDE.md\n\n<!-- refactor-log -->\nResult: NO_CHANGE\n" + RESTRICTION;
            assertThat(RestrictionParser.parse(truncated).orElseThrow())
                    .containsEntry("secondaryTarget", "user");
        }
    }

    @Test
    @DisplayName("a log with neither block yields neither verdict")
    void plainLogYieldsNothing() {
        String plain = log("Summary: merged course and question.");
        assertThat(RestrictionParser.parse(plain)).isEmpty();
        assertThat(RestrictionParser.parsePrerequisite(plain)).isEmpty();
    }

    @Test
    @DisplayName("null and blank input are tolerated")
    void toleratesEmptyInput() {
        for (String input : new String[]{null, "", "   "}) {
            assertThat(RestrictionParser.parse(input)).isEmpty();
            assertThat(RestrictionParser.parsePrerequisite(input)).isEmpty();
        }
    }

    @Test
    @DisplayName("an unterminated block is ignored rather than half-read")
    void requiresClosingMarker() {
        String truncated = log(RESTRICTION.replace("<!-- END_REFACTOR_RESTRICTION -->", ""));
        Optional<Map<String, String>> parsed = RestrictionParser.parse(truncated);
        assertThat(parsed).isEmpty();
    }
}
