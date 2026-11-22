package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Estimated before/after metric impact of applying a {@link RefactorBlueprint}.
 *
 * <p>All numeric fields are nullable boxed types: null means "no estimate available" for that
 * metric, and {@code @JsonInclude(NON_NULL)} silently omits absent fields from JSON. The UI
 * must skip any row where both the before and after values are null.
 *
 * <p>Values are <em>directional estimates</em>, not precision measurements. They are computed
 * from static structural data and runtime evidence using heuristic multipliers. See
 * {@link #estimationNote} for the caveat text displayed to the user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricImpact {

    // ─── Structural ───────────────────────────────────────────────────────────

    /** M1 intra-service cohesion before the operation. */
    private Double m1Before;

    /** Estimated M1 cohesion after the operation. */
    private Double m1After;

    /** M2 inter-service coupling before the operation. */
    private Double m2Before;

    /** Estimated M2 coupling after the operation. */
    private Double m2After;

    /**
     * Entity count summary before the operation.
     * Single aggregate: {@code "8"}; split result: {@code "5 / 3"}.
     */
    private String entityCountBefore;

    /**
     * Entity count summary after the operation.
     * Merged result: {@code "13"}; split result: {@code "4 / 4"}.
     */
    private String entityCountAfter;

    /** Total cross-service external relations (σ) before the operation. */
    private Integer externalRelsBefore;

    /** Estimated total cross-service external relations after the operation. */
    private Integer externalRelsAfter;

    // ─── Runtime ─────────────────────────────────────────────────────────────

    /** p95 latency (ms) of the target service before the operation. */
    private Double p95LatencyMsBefore;

    /** Estimated p95 latency (ms) after the operation. */
    private Double p95LatencyMsAfter;

    /** Average SAGA compensation rate before the operation. */
    private Double compensationRateBefore;

    /** Estimated average SAGA compensation rate after the operation. */
    private Double compensationRateAfter;

    // ─── Normalised signals (what the scores actually read) ───────────────────
    /** Cohesion signal of the target service(s) before: functional cohesion, else cluster factor. */
    private Double cohesionBefore;
    /** Estimated cohesion signal after, computed in closed form from the operation's blueprint. */
    private Double cohesionAfter;
    /** System coupling density before the operation. */
    private Double couplingDensityBefore;
    /** Estimated system coupling density after. */
    private Double couplingDensityAfter;
    /** Local-step ratio of the involved functionalities before. */
    private Double localStepRatioBefore;
    /** Estimated local-step ratio after. */
    private Double localStepRatioAfter;

    // ─── Metadata ─────────────────────────────────────────────────────────────

    /** Short caveat string displayed as a footnote in the UI. Always non-null when present. */
    private String estimationNote;
}
