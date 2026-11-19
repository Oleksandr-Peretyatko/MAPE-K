package org.anonymous.msrefactor.rt.analyze.attribution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Step 4.2 — Entity-level attribution.
 *
 * <p>Enriches each hotspot with {@code entityLevelCauses}: the specific entity class names
 * inside the hotspot's affected service(s) that are the root cause of the detected problem.
 * This data is subsequently consumed by {@code CandidateGenerator} (to populate
 * {@code affectedEntities}) and {@code CandidateDeepener} (to resolve the specific
 * entity/aggregate for the replica candidates).
 */
@Slf4j
@Service
public class EntityAttributionService {

    /**
     * Attributes entity-level causes to each hotspot based on the available structural evidence.
     *
     * @param hotspots  hotspots produced by HotspotDetector (sorted by QA priority)
     * @param structural structural evidence containing per-service metrics
     * @return the same list with {@code entityLevelCauses} populated on each hotspot
     */
    public List<Hotspot> attribute(List<Hotspot> hotspots, StructuralEvidence structural) {
        if (hotspots == null || hotspots.isEmpty()) return hotspots;
        if (structural == null || structural.getPerService() == null) return hotspots;

        List<Hotspot> enriched = new ArrayList<>(hotspots.size());
        for (Hotspot h : hotspots) {
            enriched.add(attributeOne(h, structural));
        }
        return enriched;
    }

    private Hotspot attributeOne(Hotspot h, StructuralEvidence structural) {
        if (h.getType() == null) return h;

        List<String> causes = switch (h.getType()) {
            case TIGHT_COUPLING -> attributeTightCoupling(h, structural);
            case LOW_COHESION   -> attributeLowCohesion(h, structural);
            case HIGH_ENTITY_COUNT -> attributeHighEntityCount(h, structural);
            default -> List.of();
        };

        if (causes.isEmpty()) return h;

        return Hotspot.builder()
                .type(h.getType())
                .qualityAttribute(h.getQualityAttribute())
                .affectedServices(h.getAffectedServices())
                .evidence(h.getEvidence())
                .severity(h.getSeverity())
                .entityLevelCauses(causes)
                .build();
    }

    /**
     * TIGHT_COUPLING between A and B: bridging entity classes in A that reference B,
     * i.e. crossServiceEntities[A][B].
     */
    private List<String> attributeTightCoupling(Hotspot h, StructuralEvidence structural) {
        List<String> services = h.getAffectedServices();
        if (services == null || services.size() < 2) return List.of();

        String serviceA = services.get(0);
        String serviceB = services.get(1);
        Map<String, ServiceStaticMetrics> perService = structural.getPerService();

        ServiceStaticMetrics smA = perService.get(serviceA);
        if (smA == null || smA.getCrossServiceEntities() == null) return List.of();

        List<String> bridging = smA.getCrossServiceEntities().get(serviceB);
        return bridging != null ? bridging : List.of();
    }

    /**
     * LOW_COHESION on service X: the entity in X with the highest number of cross-service
     * references across all target services, i.e. the entity that appears most often in
     * crossServiceEntities[X][*].
     */
    private List<String> attributeLowCohesion(Hotspot h, StructuralEvidence structural) {
        List<String> services = h.getAffectedServices();
        if (services == null || services.isEmpty()) return List.of();

        String target = services.get(0);
        ServiceStaticMetrics sm = structural.getPerService().get(target);
        if (sm == null || sm.getCrossServiceEntities() == null) return List.of();

        // Count how many times each entity appears across all cross-service maps
        Map<String, Long> entityRefCount = new java.util.HashMap<>();
        for (List<String> entityList : sm.getCrossServiceEntities().values()) {
            for (String entity : entityList) {
                entityRefCount.merge(entity, 1L, Long::sum);
            }
        }

        if (entityRefCount.isEmpty()) return List.of();

        // Return the top entity by external reference count
        return entityRefCount.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(e -> List.of(e.getKey()))
                .orElse(List.of());
    }

    /**
     * HIGH_ENTITY_COUNT on service X: all entity names in X (the count itself is the problem;
     * all entities are candidates for redistribution).
     */
    private List<String> attributeHighEntityCount(Hotspot h, StructuralEvidence structural) {
        List<String> services = h.getAffectedServices();
        if (services == null || services.isEmpty()) return List.of();

        String target = services.get(0);
        ServiceStaticMetrics sm = structural.getPerService().get(target);
        if (sm == null || sm.getEntityNames() == null) return List.of();

        return sm.getEntityNames();
    }
}
