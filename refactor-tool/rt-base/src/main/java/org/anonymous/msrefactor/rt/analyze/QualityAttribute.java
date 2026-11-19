package org.anonymous.msrefactor.rt.analyze;

/**
 * Quality attributes used for ranking candidate refactoring operations and for measuring
 * the current architecture quality from snapshot metrics.
 */
public enum QualityAttribute {
    /** Latency, cross-service calls, bandwidth, critical-path SAGA cost. */
    PERFORMANCE,
    /** Invariant correctness, replica freshness, violation rate, lag window. */
    CONSISTENCY,
    /** Coupling, cohesion, aggregate size, ownership clarity, sync path clarity. */
    MAINTAINABILITY,
    /** Inter-service coupling (positive score = low coupling). */
    COUPLING,
    /** Intra-service cohesion (positive score = high cohesion). */
    COHESION,
    /** Ability to handle increasing load without latency/error degradation. */
    SCALABILITY,
    /** Degree to which services can evolve, deploy, and operate independently. */
    AUTONOMY
}
