package org.anonymous.msrefactor.rt.execute.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A skill may only record a verdict about the operation it was given.
 *
 * <p>Without this, a stop block naming any other pair is persisted as an architect restriction and
 * blocks that pair for every future run — which is how a successful {@code course ↔ question} merge
 * came to block {@code tournament ↔ answer} and stall the loop.
 */
class StopVerdictScopeTest {

    private static Map<String, String> block(String operation, String primary, String secondary) {
        Map<String, String> fields = new HashMap<>();
        if (operation != null) fields.put("operation", operation);
        if (primary != null) fields.put("targetService", primary);
        if (secondary != null) fields.put("secondaryTarget", secondary);
        fields.put("reason", "…");
        return fields;
    }

    private static boolean matchesCourseQuestionMerge(Map<String, String> fields) {
        return ExecuteService.stopMatchesRun(fields, "MERGE_AGGREGATES", "course", "question");
    }

    @Test
    @DisplayName("a block about another pair is not this run's verdict")
    void rejectsAnotherPair() {
        assertThat(matchesCourseQuestionMerge(
                block("MERGE_AGGREGATES", "tournament", "answer"))).isFalse();
    }

    @Test
    @DisplayName("the pair is unordered — merge is symmetric on its operands")
    void acceptsEitherOrdering() {
        assertThat(matchesCourseQuestionMerge(
                block("MERGE_AGGREGATES", "course", "question"))).isTrue();
        assertThat(matchesCourseQuestionMerge(
                block("MERGE_AGGREGATES", "question", "course"))).isTrue();
    }

    @Test
    @DisplayName("casing and stray whitespace do not make a pair foreign")
    void normalisesNames() {
        assertThat(matchesCourseQuestionMerge(
                block("merge_aggregates", " Question ", "COURSE"))).isTrue();
    }

    @Test
    @DisplayName("half a pair is a broader rule than the run, not the same one")
    void rejectsWildcardSecondary() {
        assertThat(matchesCourseQuestionMerge(
                block("MERGE_AGGREGATES", "course", null))).isFalse();
    }

    @Test
    @DisplayName("a different operation on the right pair is still a different verdict")
    void rejectsAnotherOperation() {
        assertThat(matchesCourseQuestionMerge(
                block("SPLIT_AGGREGATE", "course", "question"))).isFalse();
    }

    // ─── The run's own pair ──────────────────────────────────────────────────

    @Test
    @DisplayName("a block naming the run's own pair is accepted — the case that failed in production")
    void acceptsTheRunsOwnPair() {
        // /create-replica answer AnswerCourseExecution --to tournament refused as case 4 and wrote a
        // correctly-scoped block. It was discarded, and the run went on to commit a CLAUDE.md-only
        // tree as v1. Nothing here covered a *paired* operation's own verdict until now.
        assertThat(ExecuteService.stopMatchesRun(
                block("CREATE_REPLICA", "tournament", "answer"),
                "CREATE_REPLICA", "tournament", "answer")).isTrue();
    }

    @Test
    @DisplayName("a run that does not know its own secondary validates on the primary alone")
    void degradesToThePrimaryWhenTheRunHasNoSecondary() {
        // Rows written before secondaryTarget was carried through from the plan have a null
        // secondary. A half-pair can never equal a full one, so strict comparison would discard
        // every in-scope verdict for CREATE_REPLICA and MERGE_AGGREGATES — which is the bug.
        assertThat(ExecuteService.stopMatchesRun(
                block("CREATE_REPLICA", "tournament", "answer"),
                "CREATE_REPLICA", "tournament", null)).isTrue();
    }

    @Test
    @DisplayName("the degradation still rejects a genuinely foreign pair")
    void degradationDoesNotAcceptAnotherPrimary() {
        assertThat(ExecuteService.stopMatchesRun(
                block("CREATE_REPLICA", "quiz", "topic"),
                "CREATE_REPLICA", "tournament", null)).isFalse();
    }

    @Test
    @DisplayName("a block naming no pair inherits the run's own, so it matches")
    void acceptsUnnamedPair() {
        assertThat(matchesCourseQuestionMerge(block("MERGE_AGGREGATES", null, null))).isTrue();
        assertThat(matchesCourseQuestionMerge(block(null, null, null))).isTrue();
    }

    @Test
    @DisplayName("a single-target operation matches on its one service")
    void acceptsSingleTargetOperation() {
        assertThat(ExecuteService.stopMatchesRun(
                block("SPLIT_AGGREGATE", "execution", null), "SPLIT_AGGREGATE", "execution", null))
                .isTrue();
        assertThat(ExecuteService.stopMatchesRun(
                block("SPLIT_AGGREGATE", "tournament", null), "SPLIT_AGGREGATE", "execution", null))
                .isFalse();
    }
}
