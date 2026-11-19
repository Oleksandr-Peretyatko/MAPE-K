package org.anonymous.msrefactor.rt.analyze.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityAttributeService {

    private static final List<String[]> BUILT_INS = List.of(
            new String[]{"PERFORMANCE",      "Latency, throughput, critical-path cost"},
            new String[]{"CONSISTENCY",      "Invariant correctness, replica freshness, violation rate"},
            new String[]{"MAINTAINABILITY",  "Overall code/structure maintainability"},
            new String[]{"COUPLING",         "Inter-service dependency: coupling density, cross-service workflow steps, replicas"},
            new String[]{"COHESION",         "Intra-service cohesion: functional and structural cohesion, cluster factor"},
            new String[]{"SCALABILITY",      "Ability to scale independently under load"},
            new String[]{"AUTONOMY",         "Independent deployability and team ownership clarity"}
    );

    private final QualityAttributeRepository repository;

    @PostConstruct
    @Transactional
    public void seed() {
        for (int i = 0; i < BUILT_INS.size(); i++) {
            String name = BUILT_INS.get(i)[0];
            if (!repository.existsByName(name)) {
                repository.save(QualityAttributeEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .name(name)
                        .description(BUILT_INS.get(i)[1])
                        .sortOrder(i)
                        .active(true)
                        .builtIn(true)
                        .createdAt(Instant.now())
                        .build());
                log.info("QualityAttributeService: seeded '{}'", name);
            }
        }
    }

    public List<QualityAttributeEntity> listActive() {
        return repository.findAllByActiveTrueOrderBySortOrderAsc();
    }

    @Transactional
    public QualityAttributeEntity create(String name, String description) {
        if (repository.existsByName(name.toUpperCase())) {
            throw new IllegalArgumentException("Quality attribute '" + name + "' already exists");
        }
        int maxOrder = repository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .mapToInt(QualityAttributeEntity::getSortOrder).max().orElse(-1);
        QualityAttributeEntity entity = QualityAttributeEntity.builder()
                .id(UUID.randomUUID().toString())
                .name(name.toUpperCase())
                .description(description)
                .sortOrder(maxOrder + 1)
                .active(true)
                .builtIn(false)
                .createdAt(Instant.now())
                .build();
        return repository.save(entity);
    }

    @Transactional
    public QualityAttributeEntity update(String id, String name, String description, Boolean active) {
        QualityAttributeEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("QualityAttribute not found: " + id));
        if (name != null) entity.setName(name.toUpperCase());
        if (description != null) entity.setDescription(description);
        if (active != null) entity.setActive(active);
        return repository.save(entity);
    }

    @Transactional
    public void deactivate(String id) {
        QualityAttributeEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("QualityAttribute not found: " + id));
        entity.setActive(false);
        repository.save(entity);
    }

}
