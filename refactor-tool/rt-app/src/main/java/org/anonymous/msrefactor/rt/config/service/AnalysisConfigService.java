package org.anonymous.msrefactor.rt.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.config.persistence.AnalysisConfigEntity;
import org.anonymous.msrefactor.rt.config.persistence.AnalysisConfigRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.anonymous.msrefactor.rt.constants.ProtocolConstants;

/**
 * Manages the singleton {@link AnalysisConfig} stored in the {@code analysis_config} table.
 *
 * <p>On startup the config is loaded from the DB (or seeded with {@link AnalysisConfig#defaults()}
 * if no row exists). Subsequent reads are served from an in-memory {@link AtomicReference} for
 * zero-latency access during analysis. Writes go to the DB first, then update the cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisConfigService {

    private static final int SINGLETON_ID = ProtocolConstants.SINGLETON_ID;

    private final AnalysisConfigRepository repository;
    private final ObjectMapper             objectMapper;

    private final AtomicReference<AnalysisConfig> cache =
            new AtomicReference<>(AnalysisConfig.defaults());

    @PostConstruct
    @Transactional
    public void init() {
        AnalysisConfigEntity entity = repository.findById(SINGLETON_ID).orElseGet(() -> {
            log.info("AnalysisConfigService: no row found — seeding defaults");
            return repository.save(toEntity(AnalysisConfig.defaults()));
        });
        cache.set(fromEntity(entity));
        log.info("AnalysisConfigService: loaded config (updatedAt={})", entity.getUpdatedAt());
    }

    /** Returns the currently active config (cache-first, never null). */
    public AnalysisConfig current() {
        return cache.get();
    }

    /** Persists {@code updated} and refreshes the cache. Returns the saved config. */
    @Transactional
    public AnalysisConfig save(AnalysisConfig updated) {
        AnalysisConfigEntity entity = toEntity(updated);
        repository.save(entity);
        // Re-read through fromEntity so the cache carries the persisted semantics
        // (e.g. a null defaultQualityPriorities column means "factory defaults").
        AnalysisConfig effective = fromEntity(entity);
        cache.set(effective);
        log.info("AnalysisConfigService: config updated");
        return effective;
    }

    /** Resets the config to factory defaults, persists, and refreshes the cache. */
    @Transactional
    public AnalysisConfig reset() {
        return save(AnalysisConfig.defaults());
    }

    // ─── Serialisation helpers ────────────────────────────────────────────────

    private AnalysisConfigEntity toEntity(AnalysisConfig cfg) {
        try {
            return AnalysisConfigEntity.builder()
                    .id(SINGLETON_ID)
                    .hotspotRulesJson(objectMapper.writeValueAsString(cfg.getHotspotRules()))
                    .qaToHotspotTypesJson(objectMapper.writeValueAsString(cfg.getQaToHotspotTypes()))
                    .qualityPrioritiesJson(cfg.getDefaultQualityPriorities() != null
                            ? objectMapper.writeValueAsString(cfg.getDefaultQualityPriorities())
                            : null)
                    .updatedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("AnalysisConfigService: failed to serialize config", e);
        }
    }

    private AnalysisConfig fromEntity(AnalysisConfigEntity entity) {
        try {
            Map<HotspotType, List<OperationType>> rules = objectMapper.readValue(
                    entity.getHotspotRulesJson(),
                    new TypeReference<>() {});
            Map<QualityAttribute, List<HotspotType>> qaMap = entity.getQaToHotspotTypesJson() != null
                    ? objectMapper.readValue(entity.getQaToHotspotTypesJson(), new TypeReference<>() {})
                    : AnalysisConfig.defaults().getQaToHotspotTypes();
            List<String> priorities = entity.getQualityPrioritiesJson() != null
                    ? objectMapper.readValue(entity.getQualityPrioritiesJson(), new TypeReference<>() {})
                    : AnalysisConfig.defaultQualityPriorityNames();
            return AnalysisConfig.builder()
                    .hotspotRules(rules)
                    .qaToHotspotTypes(qaMap)
                    .defaultQualityPriorities(priorities)
                    .build();
        } catch (Exception e) {
            log.warn("AnalysisConfigService: failed to deserialize config — falling back to defaults: {}",
                    e.getMessage());
            return AnalysisConfig.defaults();
        }
    }
}
