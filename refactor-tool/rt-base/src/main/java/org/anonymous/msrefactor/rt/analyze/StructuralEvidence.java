package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.List;
import java.util.Map;

/**
 * Structural evidence derived from static M1/M2 analysis of the selected project version.
 * Populated by {@code EvidenceExtractor.extractStructural()}. All collections are never null
 * (empty when no data is available).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StructuralEvidence {

    /** System-level intra-service cohesion score (M1). 0.0 when no data. */
    private double m1;

    /** System-level inter-service coupling score (M2). 0.0 when no data. */
    private double m2;

    /** Number of services present in the analysed version. */
    private int serviceCount;

    /** Per-service structural metrics keyed by service name. */
    private Map<String, ServiceStaticMetrics> perService;

    /**
     * Pairs of service names with external relations above the coupling threshold.
     * Each entry is a two-element array: [serviceA, serviceB].
     */
    private List<String[]> highCouplingPairs;

    /** Services whose M1 contribution is below the cohesion threshold. */
    private List<String> lowCohesionServices;

    /** Services whose entity count exceeds the high-entity-count threshold. */
    private List<String> highEntityCountServices;

    /** Version name from which this evidence was derived (e.g., "v1"). Null if no versions. */
    private String sourceVersion;
}
