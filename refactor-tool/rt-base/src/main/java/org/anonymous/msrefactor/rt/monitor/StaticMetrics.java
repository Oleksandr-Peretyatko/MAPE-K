package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Application-level static structural metrics computed from Java source analysis.
 *
 * <h3>Formulas (Mancoridis et al. [47])</h3>
 * <pre>
 * M1 = (1/t') · Σᵢ (μᵢ / Nᵢ²)                                   intra-service cohesion
 * M2 = 1/(t'(t'−1)/2) · Σᵢ≠ⱼ σᵢⱼ / (2·Nᵢ·Nⱼ)                  inter-service coupling
 * </pre>
 * where t' is the number of services, Nᵢ the entity count, μᵢ the internal relations,
 * and σᵢⱼ the relations between services sᵢ and sⱼ.
 *
 * <p>Higher M1 → more cohesive services. Lower M2 → more decoupled services.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StaticMetrics {

    /**
     * Per-service structural data keyed by service name
     * (e.g., {@code "quiz"}, {@code "tournament"}).
     */
    private Map<String, ServiceStaticMetrics> services;

    /** M1 — intra-service cohesion score (higher = more cohesive). */
    private double m1;

    /** M2 — inter-service coupling score (lower = more decoupled). */
    private double m2;

    /** Number of services analysed (t'). */
    private int serviceCount;

    /** Path of the source directory that was analysed. */
    private String sourceDir;

    /**
     * All Functionality classes found in this version (Pass 4).
     * Null when not yet collected (backward-compatible with older snapshots).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<FunctionalityInfo> functionalities;

    /**
     * Every {@code *Event} class declared in the application's {@code events/} package.
     *
     * <p>The inventory the publisher and subscriber scans are checked against. A class here that no
     * service registers and no aggregate subscribes to is dead code the two scans alone cannot see,
     * because both start from a use rather than from the declaration.
     *
     * <p>Null when the package could not be read; empty means it was read and holds no event.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> eventCatalogue;

    /**
     * M1 computed from the functionality graph only.
     * μ'ᵢ = total step count of Functionality classes owned by service i.
     * Formula: (1/t') · Σᵢ (μ'ᵢ / Nᵢ²).  Null for older snapshots without Pass 4 data.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double m1Functional;

    /**
     * M2 computed from the functionality graph only.
     * σ'ᵢⱼ = number of Functionality classes owned by service i that call service j.
     * Formula: 1/(t'(t'−1)/2) · Σᵢ≠ⱼ (σ'ᵢⱼ + σ'ⱼᵢ) / (2·Nᵢ·Nⱼ).  Null for older snapshots.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double m2Functional;

    // ─── Normalised structural signals (see StructuralSignals) ───────────────
    // All entity-weighted over services; null on snapshots taken before these existed, in which
    // case StructuralSignals.enrich recomputes them from the raw counts above.

    /** Entity-weighted mean of per-service {@link ServiceStaticMetrics#getStructuralCohesion()}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double structuralCohesion;
    /** Entity-weighted mean of per-service {@link ServiceStaticMetrics#getFunctionalCohesion()}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double functionalCohesion;
    /** Entity-weighted mean of per-service {@link ServiceStaticMetrics#getClusterFactor()}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double clusterFactor;
    /** Step-weighted fraction of workflow steps that stay inside their owner service. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double localStepRatio;
    /** Entity-weighted mean of per-service replica density. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double replicaDensity;
    /** Entity-weighted mean of per-service event-edge density. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double eventEdgeDensity;
    /** {@code Σσ / ((t'−1)·ΣN)}: cross-service references over the most the system could hold. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double couplingDensity;
    /** Mean step count of the functionalities that cross a service boundary; 0 when none does. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double avgDistributedSteps;
    /** The M2 the formula would give if every entity referenced every other service once. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double m2Max;
}
