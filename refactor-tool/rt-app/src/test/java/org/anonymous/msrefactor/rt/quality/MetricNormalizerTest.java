package org.anonymous.msrefactor.rt.quality;

import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.quality.QualityMetricThresholds.Threshold;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricNormalizerTest {

    private final MetricNormalizer norm = new MetricNormalizer();

    // ─── lower-is-better ─────────────────────────────────────────────────────

    @Test
    void lowerIsBetter_atGoodThreshold_returns1() {
        assertThat(norm.normalizeLowerIsBetter(200, 200, 1200)).isEqualTo(1.0);
    }

    @Test
    void lowerIsBetter_atBadThreshold_returns0() {
        assertThat(norm.normalizeLowerIsBetter(1200, 200, 1200)).isEqualTo(0.0);
    }

    @Test
    void lowerIsBetter_midpoint_returns0dot5() {
        double mid = 200 + (1200 - 200) / 2.0; // 700
        assertThat(norm.normalizeLowerIsBetter(mid, 200, 1200)).isCloseTo(0.5, within(0.001));
    }

    @Test
    void lowerIsBetter_beyondBad_clampedTo0() {
        assertThat(norm.normalizeLowerIsBetter(9999, 200, 1200)).isEqualTo(0.0);
    }

    @Test
    void lowerIsBetter_belowGood_clampedTo1() {
        assertThat(norm.normalizeLowerIsBetter(0, 200, 1200)).isEqualTo(1.0);
    }

    @Test
    void lowerIsBetter_withThresholdRecord() {
        Threshold t = new Threshold(200, 1200);
        assertThat(norm.normalizeLowerIsBetter(200, t)).isEqualTo(1.0);
    }

    // ─── higher-is-better ────────────────────────────────────────────────────

    @Test
    void higherIsBetter_atGoodThreshold_returns1() {
        assertThat(norm.normalizeHigherIsBetter(100, 100, 1)).isEqualTo(1.0);
    }

    @Test
    void higherIsBetter_atBadThreshold_returns0() {
        assertThat(norm.normalizeHigherIsBetter(1, 100, 1)).isEqualTo(0.0);
    }

    @Test
    void higherIsBetter_midpoint_returns0dot5() {
        double mid = 1 + (100 - 1) / 2.0; // 50.5
        assertThat(norm.normalizeHigherIsBetter(mid, 100, 1)).isCloseTo(0.5, within(0.001));
    }

    @Test
    void higherIsBetter_aboveGood_clampedTo1() {
        assertThat(norm.normalizeHigherIsBetter(999, 100, 1)).isEqualTo(1.0);
    }

    @Test
    void higherIsBetter_belowBad_clampedTo0() {
        assertThat(norm.normalizeHigherIsBetter(0, 100, 1)).isEqualTo(0.0);
    }

    // ─── weightedAverage ─────────────────────────────────────────────────────

    @Test
    void weightedAverage_allPresent_weightsCorrect() {
        List<MetricSignal> signals = List.of(
                signal(0.8, 0.6), // 0.8 × 0.6 = 0.48
                signal(0.2, 0.4)  // 0.2 × 0.4 = 0.08
        );
        double expected = (0.8 * 0.6 + 0.2 * 0.4) / (0.6 + 0.4); // 0.56
        assertThat(norm.weightedAverage(signals)).isCloseTo(expected, within(0.001));
    }

    @Test
    void weightedAverage_missingSignal_rebalances() {
        List<MetricSignal> signals = List.of(
                signal(0.8, 0.6),           // present
                signalMissing(0.4)          // normalizedValue = null — should be excluded
        );
        // Only the present signal counts: 0.8 × 0.6 / 0.6 = 0.8
        assertThat(norm.weightedAverage(signals)).isCloseTo(0.8, within(0.001));
    }

    @Test
    void weightedAverage_noSignals_returnsNeutral() {
        List<MetricSignal> signals = List.of(signalMissing(1.0));
        assertThat(norm.weightedAverage(signals)).isEqualTo(0.5);
    }

    @Test
    void weightedAverage_emptyList_returnsNeutral() {
        assertThat(norm.weightedAverage(List.of())).isEqualTo(0.5);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private MetricSignal signal(double normalizedValue, double weight) {
        return MetricSignal.builder()
                .key("test").label("Test").rawValue(normalizedValue)
                .normalizedValue(normalizedValue).weight(weight)
                .contribution(normalizedValue * weight).build();
    }

    private MetricSignal signalMissing(double weight) {
        return MetricSignal.builder()
                .key("missing").label("Missing").rawValue(null)
                .normalizedValue(null).weight(weight).contribution(0).build();
    }
}
