package org.anonymous.msrefactor.rt.analyze;

/**
 * Categories of detected architectural hotspots.
 */
public enum HotspotType {
    /** Service or SAGA p95 latency exceeds threshold. */
    HIGH_LATENCY,
    /** High inter-service structural coupling (external relations count). */
    TIGHT_COUPLING,
    /** Low intra-service cohesion (low M1 contribution). */
    LOW_COHESION,
    /** SAGA compensation rate exceeds threshold. */
    HIGH_COMPENSATION,
    /** Service error rate exceeds threshold. */
    HIGH_ERROR_RATE,
    /** Read-heavy access pattern (high read/write ratio). */
    READ_HEAVY,
    /** Aggregate entity count exceeds threshold. */
    HIGH_ENTITY_COUNT,
    /** Explicit consistency violations detected in spans. */
    CONSISTENCY_VIOLATION
}
