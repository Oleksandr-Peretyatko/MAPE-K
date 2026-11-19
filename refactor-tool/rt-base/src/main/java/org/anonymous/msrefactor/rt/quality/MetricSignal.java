package org.anonymous.msrefactor.rt.quality;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single metric input signal contributing to a quality attribute score. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricSignal {

    /** Machine-readable key (e.g., {@code locust_p95}). */
    private String key;

    /** Human-readable label (e.g., "Locust p95 latency"). */
    private String label;

    /** Raw observed value, or null when the metric is absent. */
    private Double rawValue;

    /** Normalised value in [0..1], or null when unavailable. */
    private Double normalizedValue;

    /** Weight of this signal in the weighted average for this QA. */
    private double weight;

    /** {@code normalizedValue × weight}, or 0 when the signal is missing. */
    private double contribution;

    /** Whether a higher raw value is better ({@code higher_is_better}) or worse ({@code lower_is_better}). */
    private String direction;

    /** Data source: {@code jaeger}, {@code locust}, {@code static}, or {@code derived}. */
    private String source;

    /** Description of what this signal measures and why it matters. */
    private String description;

    /**
     * True when the value was approximated from a proxy metric rather than
     * measured directly. Missing signals have {@code normalizedValue == null}.
     */
    @Builder.Default
    private boolean estimated = false;
}
