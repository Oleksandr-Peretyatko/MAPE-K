package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.rt.analyze.HotspotThresholds;
import org.anonymous.msrefactor.rt.monitor.persistence.ProjectConfigEntity;
import org.anonymous.msrefactor.rt.monitor.persistence.ProjectConfigRepository;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.anonymous.msrefactor.rt.constants.ProtocolConstants;

/**
 * Per-project settings: hotspot detection thresholds and the constraint-aware selection flag.
 *
 * <p>Loaded from the single-row {@code project_config} table on startup and cached in
 * {@link AtomicReference} fields for fast reads. The table's {@code switched_at} column is a
 * leftover of the file-based collectors and is only filled to satisfy its NOT NULL constraint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectConfig {

    private static final int SINGLETON_ID = ProtocolConstants.SINGLETON_ID;

    private final ProjectConfigRepository repository;
    private final ObjectMapper            objectMapper;

    private final AtomicReference<HotspotThresholds> thresholds     = new AtomicReference<>(HotspotThresholds.defaults());
    private final AtomicReference<Boolean>           constraintAwareSelection = new AtomicReference<>(Boolean.TRUE);

    @PostConstruct
    @Transactional
    public void init() {
        ProjectConfigEntity entity = repository.findById(SINGLETON_ID).orElseGet(() -> {
            ProjectConfigEntity fresh = ProjectConfigEntity.builder()
                    .id(SINGLETON_ID)
                    .switchedAt(Instant.EPOCH)
                    .build();
            return repository.save(fresh);
        });
        if (entity.getThresholdsJson() != null) {
            try {
                thresholds.set(objectMapper.readValue(entity.getThresholdsJson(), HotspotThresholds.class));
            } catch (Exception e) {
                log.warn("ProjectConfig: could not deserialize thresholds_json — using defaults: {}", e.getMessage());
            }
        }
        if (entity.getConstraintAwareSelection() != null) {
            constraintAwareSelection.set(entity.getConstraintAwareSelection());
        }
        log.info("ProjectConfig loaded — constraintAwareSelection={}", constraintAwareSelection.get());
    }

    public HotspotThresholds getThresholds() {
        return thresholds.get();
    }

    /** Whether candidate generators select the strongest constraint-compliant operand (default true). */
    public boolean isConstraintAwareSelection() {
        return Boolean.TRUE.equals(constraintAwareSelection.get());
    }

    @Transactional
    public HotspotThresholds updateThresholds(HotspotThresholds updated) {
        String json;
        try {
            json = objectMapper.writeValueAsString(updated);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize HotspotThresholds", e);
        }
        thresholds.set(updated);
        ProjectConfigEntity entity = repository.findById(SINGLETON_ID)
                .orElse(ProjectConfigEntity.builder().id(SINGLETON_ID).switchedAt(Instant.EPOCH).build());
        entity.setThresholdsJson(json);
        repository.save(entity);
        log.info("HotspotThresholds updated");
        return updated;
    }
}
