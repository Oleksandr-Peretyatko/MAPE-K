package org.anonymous.msrefactor.rt.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class QualityAttributeCalculatorTest {

    private QualityAttributeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new QualityAttributeCalculator(new MetricNormalizer());
    }

    /** No saga metrics: the production method takes them as its 4th argument. */
    private List<QualityAttributeScore> calculateAll(Map<String, AggregateMetrics> jaeger,
                                                     List<LocustScenarioMetrics> locust, StaticMetrics statics) {
        return calculator.calculateAll(jaeger, locust, statics, Map.of());
    }

    // ─── calculateAll — smoke tests ───────────────────────────────────────────

    @Test
    void calculateAll_fullData_returns7Scores() {
        List<QualityAttributeScore> scores = calculateAll(
                goodJaeger(), goodLocust(), goodStatics());

        assertThat(scores).hasSize(7);
        List<QualityAttribute> attrs = scores.stream()
                .map(QualityAttributeScore::getAttribute).collect(Collectors.toList());
        assertThat(attrs).containsExactlyInAnyOrder(QualityAttribute.values());
    }

    @Test
    void calculateAll_allScoresInRange() {
        List<QualityAttributeScore> scores = calculateAll(
                goodJaeger(), goodLocust(), goodStatics());
        for (QualityAttributeScore s : scores) {
            assertThat(s.getScore()).isBetween(0.0, 1.0);
            assertThat(s.getPercentage()).isBetween(0, 100);
        }
    }

    @Test
    void calculateAll_noLocust_stillReturns7Scores() {
        List<QualityAttributeScore> scores = calculateAll(
                goodJaeger(), List.of(), goodStatics());
        assertThat(scores).hasSize(7);
    }

    @Test
    void calculateAll_noLocust_noJaeger_stillReturns7Scores() {
        List<QualityAttributeScore> scores = calculateAll(
                Map.of(), List.of(), goodStatics());
        assertThat(scores).hasSize(7);
    }

    @Test
    void calculateAll_allNull_returns7NeutralScores() {
        List<QualityAttributeScore> scores = calculateAll(null, null, null);
        assertThat(scores).hasSize(7);
        // All scores should be close to 0.5 neutral when no data
        for (QualityAttributeScore s : scores) {
            assertThat(s.getScore()).isBetween(0.0, 1.0);
        }
    }

    // ─── PERFORMANCE ─────────────────────────────────────────────────────────

    @Test
    void performance_goodData_scoreIsHigh() {
        List<QualityAttributeScore> scores = calculateAll(
                goodJaeger(), goodLocust(), goodStatics());
        double perf = scoreFor(scores, QualityAttribute.PERFORMANCE);
        assertThat(perf).isGreaterThan(0.6);
    }

    @Test
    void performance_highLatency_scoreIsLow() {
        Map<String, AggregateMetrics> badJaeger = Map.of(
                "svc", AggregateMetrics.builder()
                        .p95LatencyMs(2000).errorRate(0.15).operationFrequency(10)
                        .bandwidthBytesPerSec(5_000_000).readWriteRatio(1.0).build());

        List<LocustScenarioMetrics> badLocust = List.of(
                LocustScenarioMetrics.builder()
                        .requestCount(100).p95ResponseMs(3000).p99ResponseMs(5000)
                        .requestsPerSec(2).errorRate(0.20).build());

        List<QualityAttributeScore> scores = calculateAll(badJaeger, badLocust, goodStatics());
        double perf = scoreFor(scores, QualityAttribute.PERFORMANCE);
        assertThat(perf).isLessThan(0.4);
    }

    @Test
    void performance_locustAbsent_useJaegerFallback_noEstimatedFlag() {
        List<QualityAttributeScore> scores = calculateAll(
                goodJaeger(), List.of(), goodStatics());
        QualityAttributeScore perf = findScore(scores, QualityAttribute.PERFORMANCE);

        // Jaeger-only signals should not be marked estimated
        boolean anyEstimated = perf.getSignals().stream().anyMatch(MetricSignal::isEstimated);
        assertThat(anyEstimated).isFalse();
        // Should have jaeger source signals
        assertThat(perf.getSignals()).anyMatch(s -> "jaeger".equals(s.getSource()));
    }

    // ─── COHESION ────────────────────────────────────────────────────────────

    @Test
    void cohesion_completeTreesUsedWhole_scoreIsHigh() {
        // Two complete composition trees (μ = 2(N−1)), each workflow using every entity of the
        // aggregate it touches: the reachable maximum of every static cohesion signal.
        StaticMetrics sm = StaticMetrics.builder()
                .serviceCount(2)
                .services(Map.of(
                        "a", svcNamed("a", 3, 4, List.of("A", "A1", "A2")),
                        "b", svcNamed("b", 3, 4, List.of("B", "B1", "B2"))))
                .functionalities(List.of(
                        func("DoA", "a", 2, Map.of("a", List.of("A", "A1", "A2"))),
                        func("DoB", "b", 2, Map.of("b", List.of("B", "B1", "B2")))))
                .build();
        List<QualityAttributeScore> scores = calculateAll(Map.of(), List.of(), sm);
        assertThat(scoreFor(scores, QualityAttribute.COHESION)).isGreaterThan(0.85);
    }

    @Test
    void cohesion_looseMembersAndPartialUse_scoreIsLow() {
        // Members not wired to their root, every workflow touching one entity of five, and
        // replicas crossing both ways: low on all three cohesion signals.
        StaticMetrics sm = StaticMetrics.builder()
                .serviceCount(2)
                .services(Map.of(
                        "a", svcNamed("a", 5, 1, List.of("A", "A1", "A2", "A3", "A4"))
                                .toBuilder().externalRelations(Map.of("b", 10)).build(),
                        "b", svcNamed("b", 5, 1, List.of("B", "B1", "B2", "B3", "B4"))
                                .toBuilder().externalRelations(Map.of("a", 10)).build()))
                .functionalities(List.of(
                        func("DoA", "a", 2, Map.of("a", List.of("A1"))),
                        func("DoB", "b", 2, Map.of("b", List.of("B3")))))
                .build();
        List<QualityAttributeScore> scores = calculateAll(Map.of(), List.of(), sm);
        assertThat(scoreFor(scores, QualityAttribute.COHESION)).isLessThan(0.40);
    }

    @Test
    void cohesion_rawM1IsNotScored() {
        // A snapshot whose raw M1 is high but whose trees are incomplete must not score high:
        // the attribute reads the normalised signals, not sm.getM1().
        StaticMetrics sm = StaticMetrics.builder()
                .m1(0.90).serviceCount(2)
                .services(Map.of("a", svc(4, 0), "b", svc(4, 0)))
                .build();
        List<QualityAttributeScore> scores = calculateAll(Map.of(), List.of(), sm);
        QualityAttributeScore cohesion = findScore(scores, QualityAttribute.COHESION);
        assertThat(cohesion.getSignals()).noneMatch(s -> "m1".equals(s.getKey()));
        assertThat(cohesion.getScore()).isLessThan(0.5);
    }

    // ─── COUPLING ────────────────────────────────────────────────────────────

    @Test
    void coupling_lowM2_scoreIsHigh() {
        StaticMetrics sm = StaticMetrics.builder()
                .m1(0.85).m2(0.02).serviceCount(2)
                .services(Map.of("a", svc(3, 0), "b", svc(3, 0)))
                .build();
        List<QualityAttributeScore> scores = calculateAll(goodJaeger(), goodLocust(), sm);
        assertThat(scoreFor(scores, QualityAttribute.COUPLING)).isGreaterThan(0.6);
    }

    // ─── Entity imbalance ─────────────────────────────────────────────────────

    @Test
    void entityImbalance_balancedServices_isZero() {
        StaticMetrics sm = StaticMetrics.builder()
                .services(Map.of("a", svc(3, 0), "b", svc(3, 0)))
                .build();
        assertThat(calculator.entityImbalance(sm)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void entityImbalance_unbalancedServices_isPositive() {
        StaticMetrics sm = StaticMetrics.builder()
                .services(Map.of("a", svc(1, 0), "b", svc(9, 0)))
                .build();
        assertThat(calculator.entityImbalance(sm)).isGreaterThan(0.5);
    }

    // ─── signals present and annotated ───────────────────────────────────────

    @Test
    void allScores_haveNonNullFormula() {
        List<QualityAttributeScore> scores = calculateAll(goodJaeger(), goodLocust(), goodStatics());
        for (QualityAttributeScore s : scores) {
            assertThat(s.getFormula()).isNotNull();
            assertThat(s.getFormula().getFormula()).isNotBlank();
        }
    }

    @Test
    void allScores_haveAtLeastOneSignal() {
        List<QualityAttributeScore> scores = calculateAll(goodJaeger(), goodLocust(), goodStatics());
        for (QualityAttributeScore s : scores) {
            assertThat(s.getSignals()).isNotEmpty();
        }
    }

    // ─── Fixture builders ─────────────────────────────────────────────────────

    // ─── PERFORMANCE — unmeasured latency ─────────────────────────────────────

    /** Spans exist, but all of them are TraceManager scaffolding, so nothing is measurable. */
    private Map<String, AggregateMetrics> jaegerWithoutWorkSpans() {
        return Map.of("quiz-service", AggregateMetrics.builder()
                .p95LatencyMs(0).errorRate(0.0).operationFrequency(4)
                .bandwidthBytesPerSec(0).readWriteRatio(1.0)
                .totalSpanCount(8).measuredSpanCount(0).build());
    }

    private QualityAttributeScore performanceOf(Map<String, AggregateMetrics> jaeger) {
        return calculateAll(jaeger, List.of(), goodStatics()).stream()
                .filter(s -> s.getAttribute() == QualityAttribute.PERFORMANCE)
                .findFirst().orElseThrow();
    }

    private MetricSignal signalOf(QualityAttributeScore score, String key) {
        return score.getSignals().stream().filter(s -> key.equals(s.getKey()))
                .findFirst().orElseThrow();
    }

    @Test
    void performance_noWorkSpans_latencySignalIsMissingNotZero() {
        // p95 = 0 ms would normalise to 1.0 on a lower-is-better scale, so absent telemetry used to
        // read as the best possible latency. It must drop out instead.
        MetricSignal p95 = signalOf(performanceOf(jaegerWithoutWorkSpans()), "jaeger_self_p95");
        assertThat(p95.getNormalizedValue()).isNull();
        assertThat(p95.getWeight()).isGreaterThan(0.0);
    }

    @Test
    void performance_noWorkSpans_scoreIsNotPerfect() {
        assertThat(performanceOf(jaegerWithoutWorkSpans()).getScore()).isLessThan(1.0);
    }

    @Test
    void performance_missingSignalsRenormaliseOverTheRest() {
        // The score must equal the weighted average of the signals that survived — otherwise the
        // dropped weight would silently drag the result toward zero.
        QualityAttributeScore score = performanceOf(jaegerWithoutWorkSpans());
        double wSum = 0, vSum = 0;
        for (MetricSignal s : score.getSignals()) {
            if (s.getNormalizedValue() != null) {
                wSum += s.getWeight();
                vSum += s.getNormalizedValue() * s.getWeight();
            }
        }
        assertThat(score.getScore()).isCloseTo(vSum / wSum, within(1e-9));
    }

    @Test
    void performance_measuredLatency_isScored() {
        MetricSignal p95 = signalOf(performanceOf(goodJaeger()), "jaeger_self_p95");
        assertThat(p95.getNormalizedValue()).isNotNull();
        // Worst service across the map drives the signal: 200 ms, between good (100) and bad (1000).
        assertThat(p95.getRawValue()).isEqualTo(200.0);
    }

    @Test
    void performance_noWorkflows_workflowSignalIsMissing() {
        MetricSignal wf = signalOf(performanceOf(goodJaeger()), "saga_workflow_p95");
        assertThat(wf.getNormalizedValue()).isNull();
    }

    @Test
    void performance_workflowLatency_isScoredWhenPresent() {
        Map<String, SagaMetrics> saga = Map.of("CreateQuestion",
                SagaMetrics.builder().workflowName("CreateQuestion").p95TotalLatencyMs(900).build());

        QualityAttributeScore score = calculator
                .calculateAll(goodJaeger(), List.of(), goodStatics(), saga).stream()
                .filter(s -> s.getAttribute() == QualityAttribute.PERFORMANCE)
                .findFirst().orElseThrow();

        MetricSignal wf = signalOf(score, "saga_workflow_p95");
        assertThat(wf.getRawValue()).isEqualTo(900.0);
        assertThat(wf.getNormalizedValue()).isNotNull();
    }

    private Map<String, AggregateMetrics> goodJaeger() {
        return Map.of(
                "quiz-service", AggregateMetrics.builder()
                        .p95LatencyMs(150).avgReadLatencyMs(80).avgWriteLatencyMs(120)
                        .errorRate(0.001).operationFrequency(300).bandwidthBytesPerSec(50_000)
                        .readWriteRatio(3.0).totalSpanCount(500).measuredSpanCount(480).build(),
                "tournament-service", AggregateMetrics.builder()
                        .p95LatencyMs(200).avgReadLatencyMs(100).avgWriteLatencyMs(180)
                        .errorRate(0.002).operationFrequency(120).bandwidthBytesPerSec(30_000)
                        .readWriteRatio(2.0).totalSpanCount(200).measuredSpanCount(190).build());
    }

    private List<LocustScenarioMetrics> goodLocust() {
        return List.of(
                LocustScenarioMetrics.builder()
                        .requestCount(1000).p95ResponseMs(250).p99ResponseMs(500)
                        .requestsPerSec(50).errorRate(0.001).build(),
                LocustScenarioMetrics.builder()
                        .requestCount(500).p95ResponseMs(300).p99ResponseMs(600)
                        .requestsPerSec(25).errorRate(0.002).build());
    }

    private StaticMetrics goodStatics() {
        return StaticMetrics.builder()
                .m1(0.75).m2(0.15).serviceCount(2)
                .services(Map.of(
                        "quiz-service", svcWithExtRefs(4, Map.of("tournament-service", 1)),
                        "tournament-service", svcWithExtRefs(3, Map.of("quiz-service", 1))))
                .build();
    }

    private ServiceStaticMetrics svcNamed(String name, int entityCount, int internalRelations, List<String> entities) {
        return ServiceStaticMetrics.builder()
                .serviceName(name).entityCount(entityCount).internalRelations(internalRelations)
                .entityNames(entities).externalRelations(Map.of()).build();
    }

    private FunctionalityInfo func(String name, String owner, int steps, Map<String, List<String>> touched) {
        return FunctionalityInfo.builder()
                .name(name + "FunctionalitySagas").ownerService(owner)
                .touchedServices(new java.util.ArrayList<>(touched.keySet())).stepCount(steps)
                .touchedEntities(touched).build();
    }

    private ServiceStaticMetrics svc(int entityCount, int internalRelations) {
        return ServiceStaticMetrics.builder()
                .entityCount(entityCount).internalRelations(internalRelations)
                .externalRelations(Map.of()).build();
    }

    private ServiceStaticMetrics svcWithExtRefs(int entityCount, Map<String, Integer> extRefs) {
        return ServiceStaticMetrics.builder()
                .entityCount(entityCount).internalRelations(2)
                .externalRelations(extRefs).build();
    }

    private double scoreFor(List<QualityAttributeScore> scores, QualityAttribute attr) {
        return findScore(scores, attr).getScore();
    }

    private QualityAttributeScore findScore(List<QualityAttributeScore> scores, QualityAttribute attr) {
        return scores.stream().filter(s -> s.getAttribute() == attr).findFirst()
                .orElseThrow(() -> new AssertionError("Missing score for " + attr));
    }
}
