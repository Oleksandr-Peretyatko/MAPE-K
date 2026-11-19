package org.anonymous.msrefactor.rt.quality;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;

import java.util.List;
import java.util.Map;

/** Computed score for one quality attribute, including signal breakdown and formula metadata. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QualityAttributeScore {

    private QualityAttribute attribute;

    /** Score in [0..1]. */
    private double score;

    /** Score as integer percentage 0..100. */
    private int percentage;

    /** Band derived from {@link QualityAttributeStatus#of(double)}. */
    private String status;

    private FormulaExplanation formula;

    private List<MetricSignal> signals;

    /** True when one or more signals were estimated or missing. */
    @Builder.Default
    private boolean hasEstimatedData = false;

    /** Human-readable top driver, e.g. "High p95 latency is reducing performance". */
    private String topDriver;

    /**
     * Per-entity signal values keyed by service name (for Jaeger/static path) or Locust scenario
     * name (for Locust path). Null when no per-entity breakdown is available.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, List<MetricSignal>> perEntitySignals;

    /**
     * Discriminator for {@link #perEntitySignals}: {@code "service"} or {@code "scenario"}.
     * Null when perEntitySignals is absent.
     */
    private String perEntityType;
}
