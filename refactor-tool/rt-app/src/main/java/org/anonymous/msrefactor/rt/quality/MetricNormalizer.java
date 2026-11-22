package org.anonymous.msrefactor.rt.quality;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.quality.QualityMetricThresholds.Threshold;

import java.util.List;

/**
 * Stateless normalization utilities for quality attribute calculation.
 *
 * <p>Uses bounded threshold normalization (not min/max) so scores remain stable
 * when comparing different snapshot versions.
 */
@Component
public class MetricNormalizer {

    /**
     * Normalise a lower-is-better metric to [0..1].
     * <pre>score = 1 - clamp((value - good) / (bad - good), 0, 1)</pre>
     */
    public double normalizeLowerIsBetter(double value, Threshold t) {
        return normalizeLowerIsBetter(value, t.good(), t.bad());
    }

    public double normalizeLowerIsBetter(double value, double good, double bad) {
        if (bad <= good) return value <= good ? 1.0 : 0.0;
        return 1.0 - clamp01((value - good) / (bad - good));
    }

    /**
     * Normalise a higher-is-better metric to [0..1].
     * <pre>score = clamp((value - bad) / (good - bad), 0, 1)</pre>
     */
    public double normalizeHigherIsBetter(double value, Threshold t) {
        return normalizeHigherIsBetter(value, t.good(), t.bad());
    }

    public double normalizeHigherIsBetter(double value, double good, double bad) {
        if (good <= bad) return value >= good ? 1.0 : 0.0;
        return clamp01((value - bad) / (good - bad));
    }

    public double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Weighted average over available signals only — rebalances weights automatically
     * when some signals have {@code normalizedValue == null}.
     *
     * @return weighted average in [0..1], or 0.5 (neutral) when no signals are available
     */
    public double weightedAverage(List<MetricSignal> signals) {
        double weightSum = 0.0;
        double valueSum  = 0.0;
        for (MetricSignal s : signals) {
            if (s.getNormalizedValue() != null) {
                weightSum += s.getWeight();
                valueSum  += s.getNormalizedValue() * s.getWeight();
            }
        }
        return weightSum == 0.0 ? 0.5 : clamp01(valueSum / weightSum);
    }
}
