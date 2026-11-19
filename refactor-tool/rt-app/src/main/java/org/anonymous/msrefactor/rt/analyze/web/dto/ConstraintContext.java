package org.anonymous.msrefactor.rt.analyze.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Static-analysis context returned by {@code GET /config/constraints/context}.
 *
 * <p>Provides the UI with the real services, entities, and relation counts derived
 * from the latest static-analysis scan, so rule dropdowns show live codebase values
 * rather than the static YAML seed list.
 */
public record ConstraintContext(List<ServiceContext> services) {

    /**
     * Per-service static analysis summary for rule authoring.
     *
     * @param serviceName        canonical service name (matches microservices package directory)
     * @param entityNames        sorted list of entity class names in this service
     * @param intraRelationCount count of JPA relations internal to this service (@OneToOne / @OneToMany / @ManyToOne)
     * @param interRelations     external relations to other services: targetServiceName → cross-service entity count
     */
    public record ServiceContext(
            String serviceName,
            List<String> entityNames,
            int intraRelationCount,
            Map<String, Integer> interRelations
    ) {}
}
