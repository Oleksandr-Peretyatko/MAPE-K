package org.anonymous.msrefactor.rt.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Claude-powered discovery of the service dependency graph.
 *
 * <p>Each {@code DiscoveryGraph} captures the architecture of one version of the application:
 * which services exist, what aggregates each service owns, and how they relate to each other
 * via entities, invariants, and cross-service references.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiscoveryGraph {

    private String snapshotId;
    private String codeVersionId;
    private String version;
    private String discoveredAt;
    private List<DiscoveredService> services;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiscoveredService {
        private String name;
        private List<DiscoveredAggregate> aggregates;
        private List<String> serviceOperations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiscoveredAggregate {
        private String name;
        private List<String> entities;
        private List<DiscoveredInvariant> invariants;
        private List<ExternalRelation> externalRelations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiscoveredInvariant {
        private String name;
        /** "intra" for single-aggregate invariants, "inter" for cross-aggregate. */
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExternalRelation {
        private String to;
        private String via;
    }
}
