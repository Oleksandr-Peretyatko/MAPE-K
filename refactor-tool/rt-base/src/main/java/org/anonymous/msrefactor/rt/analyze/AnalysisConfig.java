package org.anonymous.msrefactor.rt.analyze;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Project-level configuration snapshot for the Analyze phase.
 *
 * <p>Controls the previously hardcoded candidate-generation stages consumed by SIMPLE_RANK:
 * <ul>
 *   <li><b>Stage 2.5</b> — {@link #qaToHotspotTypes}: QA → hotspot-type routing for hotspot ordering</li>
 *   <li><b>Stage 3</b> — {@link #hotspotRules}: which operations are generated per hotspot type</li>
 * </ul>
 *
 * <p>Changes to this config take effect for the next analysis. Every {@link AnalysisResult}
 * embeds a snapshot of the config that was active when it ran, preserving iteration history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisConfig {

    /**
     * Stage 3 — maps each {@link HotspotType} to the {@link OperationType}s that will be
     * generated as candidates. An empty list disables candidate generation for that hotspot.
     */
    private Map<HotspotType, List<OperationType>> hotspotRules;

    /**
     * Stage 2.5 — maps each {@link QualityAttribute} to the hotspot types that evidence
     * a violation of that QA. Used by {@code AnalyzeService} to sort hotspot processing
     * according to the user's {@code qualityPriorities}.
     */
    private Map<QualityAttribute, List<HotspotType>> qaToHotspotTypes;

    /**
     * Ordered default quality priorities (QA names, highest first) used when an
     * {@link AnalysisRequest} carries no {@code qualityPriorities}. Stored as names rather
     * than {@link QualityAttribute} so custom catalog QAs can be referenced too.
     */
    private List<String> defaultQualityPriorities;

    /**
     * Returns the canonical default configuration whose values match the constants that
     * were previously hardcoded in {@code AnalysisHeuristics}.
     */
    public static AnalysisConfig defaults() {
        return AnalysisConfig.builder()
                .hotspotRules(defaultHotspotRules())
                .qaToHotspotTypes(defaultQaToHotspotTypes())
                .defaultQualityPriorities(defaultQualityPriorityNames())
                .build();
    }

    /** Factory-default priority order: the list previously hardcoded in {@code AnalyzeService}. */
    public static List<String> defaultQualityPriorityNames() {
        return List.of(QualityAttribute.PERFORMANCE.name(),
                       QualityAttribute.CONSISTENCY.name(),
                       QualityAttribute.MAINTAINABILITY.name());
    }

    // ─── QA → hotspot type routing ────────────────────────────────────────────

    private static Map<QualityAttribute, List<HotspotType>> defaultQaToHotspotTypes() {
        Map<QualityAttribute, List<HotspotType>> m = new EnumMap<>(QualityAttribute.class);
        m.put(QualityAttribute.PERFORMANCE,     List.of(HotspotType.HIGH_LATENCY,
                                                        HotspotType.READ_HEAVY,
                                                        HotspotType.HIGH_ERROR_RATE));
        m.put(QualityAttribute.CONSISTENCY,     List.of(HotspotType.HIGH_COMPENSATION,
                                                        HotspotType.CONSISTENCY_VIOLATION,
                                                        HotspotType.TIGHT_COUPLING,
                                                        HotspotType.LOW_COHESION));
        m.put(QualityAttribute.MAINTAINABILITY, List.of(HotspotType.TIGHT_COUPLING,
                                                        HotspotType.LOW_COHESION,
                                                        HotspotType.HIGH_ENTITY_COUNT));
        m.put(QualityAttribute.COUPLING,        List.of(HotspotType.TIGHT_COUPLING,
                                                        HotspotType.HIGH_LATENCY));
        m.put(QualityAttribute.COHESION,        List.of(HotspotType.LOW_COHESION,
                                                        HotspotType.HIGH_ENTITY_COUNT,
                                                        HotspotType.TIGHT_COUPLING));
        m.put(QualityAttribute.SCALABILITY,     List.of(HotspotType.HIGH_LATENCY,
                                                        HotspotType.HIGH_ERROR_RATE,
                                                        HotspotType.TIGHT_COUPLING));
        m.put(QualityAttribute.AUTONOMY,        List.of(HotspotType.TIGHT_COUPLING,
                                                        HotspotType.HIGH_COMPENSATION,
                                                        HotspotType.HIGH_LATENCY));
        return m;
    }

    // ─── Hotspot rules ────────────────────────────────────────────────────────

    private static Map<HotspotType, List<OperationType>> defaultHotspotRules() {
        Map<HotspotType, List<OperationType>> rules = new EnumMap<>(HotspotType.class);
        rules.put(HotspotType.HIGH_LATENCY,           List.of(OperationType.CREATE_REPLICA));
        rules.put(HotspotType.TIGHT_COUPLING,          List.of(OperationType.MERGE_AGGREGATES));
        rules.put(HotspotType.LOW_COHESION,            List.of(OperationType.SPLIT_AGGREGATE));
        rules.put(HotspotType.HIGH_ENTITY_COUNT,       List.of(OperationType.SPLIT_AGGREGATE));
        rules.put(HotspotType.HIGH_COMPENSATION,       List.of(OperationType.REMOVE_REPLICA));
        rules.put(HotspotType.READ_HEAVY,              List.of(OperationType.CREATE_REPLICA));
        rules.put(HotspotType.CONSISTENCY_VIOLATION,   List.of(OperationType.REMOVE_REPLICA));
        rules.put(HotspotType.HIGH_ERROR_RATE,         List.of());
        return rules;
    }
}
