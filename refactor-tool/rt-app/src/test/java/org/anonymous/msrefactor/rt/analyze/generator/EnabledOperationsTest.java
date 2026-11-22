package org.anonymous.msrefactor.rt.analyze.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantics of {@code rt.analyze.enabled-operations}. Its enforcement at generation is covered in
 * {@link MergeCandidateOwnershipTest}, whose fixture produces real candidates.
 */
class EnabledOperationsTest {

    @Test
    @DisplayName("defaults to merge + split; replicas are off")
    void defaultsToMergeAndSplit() {
        AnalysisThresholdsConfig t = new AnalysisThresholdsConfig();

        assertThat(t.getEnabledOperations())
                .containsExactlyInAnyOrder(OperationType.MERGE_AGGREGATES, OperationType.SPLIT_AGGREGATE);
        assertThat(t.isEnabled(OperationType.CREATE_REPLICA)).isFalse();
        assertThat(t.isEnabled(OperationType.REMOVE_REPLICA)).isFalse();
        assertThat(t.isEnabled((OperationType) null)).isFalse();
    }

    @Test
    @DisplayName("an empty list falls back to merge + split instead of disabling everything")
    void emptyListFallsBackToDefault() {
        AnalysisThresholdsConfig t = new AnalysisThresholdsConfig();
        t.setEnabledOperations(EnumSet.noneOf(OperationType.class));

        assertThat(t.getEnabledOperations())
                .containsExactlyInAnyOrder(OperationType.MERGE_AGGREGATES, OperationType.SPLIT_AGGREGATE);
    }

    @Test
    @DisplayName("names are matched case-insensitively; unknown names are never enabled")
    void matchesNames() {
        AnalysisThresholdsConfig t = new AnalysisThresholdsConfig();
        t.setEnabledOperations(EnumSet.of(OperationType.SPLIT_AGGREGATE, OperationType.CREATE_REPLICA));

        assertThat(t.isEnabled("create_replica")).isTrue();
        assertThat(t.isEnabled(" SPLIT_AGGREGATE ")).isTrue();
        assertThat(t.isEnabled("MERGE_AGGREGATES")).isFalse();
        assertThat(t.isEnabled("NOT_AN_OPERATION")).isFalse();
        assertThat(t.isEnabled((String) null)).isFalse();
    }
}
