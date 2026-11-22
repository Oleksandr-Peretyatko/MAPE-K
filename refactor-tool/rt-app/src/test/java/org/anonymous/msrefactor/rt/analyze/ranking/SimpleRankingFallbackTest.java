package org.anonymous.msrefactor.rt.analyze.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the catalogue narrowed to merge and split, PERFORMANCE first leaves SPLIT_AGGREGATE (−1)
 * below the primary-QA cut. The ranking must still return it, flagged, rather than an empty list.
 */
class SimpleRankingFallbackTest {

    private final SimpleRankingStrategy strategy = new SimpleRankingStrategy();

    private static final QualityAttributeEntity PERFORMANCE =
            QualityAttributeEntity.builder().id("qa-perf").name("PERFORMANCE").build();
    private static final QualityAttributeEntity COHESION =
            QualityAttributeEntity.builder().id("qa-coh").name("COHESION").build();

    /** The seeded matrix, restricted to the rows this test needs. */
    private static final Map<String, Map<String, Integer>> SCORES = Map.of(
            "SPLIT_AGGREGATE",  Map.of("qa-perf", -1, "qa-coh", 2),
            "MERGE_AGGREGATES", Map.of("qa-perf", 1,  "qa-coh", -1));

    private static CandidateOperation split(String service) {
        return CandidateOperation.builder()
                .operation(OperationType.SPLIT_AGGREGATE).targetService(service).build();
    }

    private static Hotspot entityCount(String service, double severity) {
        return Hotspot.builder().type(HotspotType.HIGH_ENTITY_COUNT)
                .qualityAttribute(QualityAttribute.COHESION)
                .affectedServices(List.of(service)).severity(severity).evidence("e").build();
    }

    @Test
    @DisplayName("nothing improves the primary QA → all candidates ranked, each flagged")
    void ranksAllAsFallback() {
        List<CandidateOperation> ranked = strategy.rank(
                List.of(split("quiz"), split("tournament")),
                List.of(PERFORMANCE, COHESION), SCORES,
                List.of(entityCount("tournament", 0.9), entityCount("quiz", 0.4)));

        assertThat(ranked).extracting(CandidateOperation::getTargetService)
                .as("still ordered by hotspot severity").containsExactly("tournament", "quiz");
        assertThat(ranked).allSatisfy(c -> {
            assertThat(c.getPrimaryQaScore()).isEqualTo(-1);
            assertThat(c.getRankReason()).startsWith("[fallback]");
            assertThat(c.getWarnings()).anyMatch(w ->
                    w.startsWith(SimpleRankingStrategy.FALLBACK_WARNING_PREFIX + "PERFORMANCE"));
        });
    }

    @Test
    @DisplayName("the deficit path falls back the same way instead of ranking every score as 0")
    void deficitPathFallsBack() {
        List<CandidateOperation> ranked = strategy.rank(
                List.of(split("quiz")), List.of(PERFORMANCE, COHESION), SCORES,
                List.of(entityCount("quiz", 0.5)), Map.of(QualityAttribute.PERFORMANCE, 0.4));

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).getWarnings()).anyMatch(w -> w.startsWith(SimpleRankingStrategy.FALLBACK_WARNING_PREFIX));
    }

    @Test
    @DisplayName("when some candidate improves the primary QA, the cut applies and nothing is flagged")
    void noFallbackWhenSomethingImproves() {
        CandidateOperation merge = CandidateOperation.builder()
                .operation(OperationType.MERGE_AGGREGATES).targetService("course").secondaryTarget("question").build();

        List<CandidateOperation> ranked = strategy.rank(
                List.of(split("quiz"), merge), List.of(PERFORMANCE, COHESION), SCORES, List.of());

        assertThat(ranked).extracting(CandidateOperation::getOperation).containsExactly(OperationType.MERGE_AGGREGATES);
        assertThat(ranked.get(0).getWarnings()).isNullOrEmpty();
        assertThat(ranked.get(0).getRankReason()).doesNotStartWith("[fallback]");
    }
}
