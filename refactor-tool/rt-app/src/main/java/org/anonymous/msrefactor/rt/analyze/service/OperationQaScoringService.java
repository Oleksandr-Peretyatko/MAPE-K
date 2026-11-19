package org.anonymous.msrefactor.rt.analyze.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.rt.analyze.persistence.OperationQaScoreEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.OperationQaScoreRepository;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the per-project operation→QA score matrix.
 *
 * <p>Global defaults (projectId=null) are seeded on startup.
 * Project-specific rows override global defaults for the same (operation, qaId) pair.
 * {@link #getScoresForProject(String)} returns the merged view.
 */
@Slf4j
@Service
@DependsOn("qualityAttributeService")
@RequiredArgsConstructor
public class OperationQaScoringService {

    // op → { qaName → score }
    private static final Map<String, Map<String, Integer>> DEFAULT_SCORES = Map.ofEntries(
            Map.entry("MERGE_AGGREGATES",  Map.of("PERFORMANCE",  1, "CONSISTENCY",  1, "MAINTAINABILITY",  1, "COUPLING",  1, "COHESION",  2, "SCALABILITY",  0, "AUTONOMY", -1)),
            Map.entry("SPLIT_AGGREGATE",   Map.of("PERFORMANCE", -1, "CONSISTENCY", -1, "MAINTAINABILITY",  1, "COUPLING", -1, "COHESION",  1, "SCALABILITY",  1, "AUTONOMY",  1)),
            Map.entry("CREATE_REPLICA",    Map.of("PERFORMANCE",  2, "CONSISTENCY", -2, "MAINTAINABILITY", -1, "COUPLING", -1, "COHESION",  0, "SCALABILITY",  2, "AUTONOMY",  1)),
            Map.entry("REMOVE_REPLICA",    Map.of("PERFORMANCE", -1, "CONSISTENCY",  2, "MAINTAINABILITY",  1, "COUPLING",  1, "COHESION",  0, "SCALABILITY", -1, "AUTONOMY",  0))
    );

    private final OperationQaScoreRepository scoreRepository;
    private final QualityAttributeRepository  qaRepository;

    @PostConstruct
    @Transactional
    public void seedDefaults() {
        // Always upsert global defaults so stale/zero rows from a prior bad startup are self-healed.
        for (Map.Entry<String, Map<String, Integer>> opEntry : DEFAULT_SCORES.entrySet()) {
            String operation = opEntry.getKey();
            for (Map.Entry<String, Integer> qaEntry : opEntry.getValue().entrySet()) {
                String qaName = qaEntry.getKey();
                qaRepository.findByName(qaName).ifPresent(qa -> {
                    scoreRepository.findByProjectIdIsNullAndOperationAndQaId(operation, qa.getId())
                            .ifPresentOrElse(
                                    existing -> {
                                        existing.setScore(qaEntry.getValue());
                                        scoreRepository.save(existing);
                                    },
                                    () -> scoreRepository.save(OperationQaScoreEntity.builder()
                                            .id(UUID.randomUUID().toString())
                                            .projectId(null)
                                            .operation(operation)
                                            .qaId(qa.getId())
                                            .score(qaEntry.getValue())
                                            .build())
                            );
                });
            }
        }
        log.info("OperationQaScoringService: global defaults seeded/refreshed");
    }

    /**
     * Returns the merged score matrix for a project.
     * Structure: operation → { qaId → score }
     * Project-specific entries override global defaults.
     */
    public Map<String, Map<String, Integer>> getScoresForProject(String projectId) {
        Map<String, Map<String, Integer>> merged = new HashMap<>();

        // Load global defaults first
        for (OperationQaScoreEntity e : scoreRepository.findAllByProjectIdIsNull()) {
            merged.computeIfAbsent(e.getOperation(), k -> new HashMap<>()).put(e.getQaId(), e.getScore());
        }

        // Project-specific rows override
        if (projectId != null) {
            for (OperationQaScoreEntity e : scoreRepository.findAllByProjectId(projectId)) {
                merged.computeIfAbsent(e.getOperation(), k -> new HashMap<>()).put(e.getQaId(), e.getScore());
            }
        }

        return merged;
    }

    /**
     * Persists the full score matrix as project-specific rows.
     * Structure: operation → { qaId → score }
     */
    @Transactional
    public void saveProjectScores(String projectId, Map<String, Map<String, Integer>> scores) {
        scoreRepository.deleteAllByProjectId(projectId);
        for (Map.Entry<String, Map<String, Integer>> opEntry : scores.entrySet()) {
            for (Map.Entry<String, Integer> qaEntry : opEntry.getValue().entrySet()) {
                validateScore(qaEntry.getValue());
                scoreRepository.save(OperationQaScoreEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .projectId(projectId)
                        .operation(opEntry.getKey())
                        .qaId(qaEntry.getKey())
                        .score(qaEntry.getValue())
                        .build());
            }
        }
        log.info("OperationQaScoringService: saved project scores for projectId={}", projectId);
    }

    /** Removes all project-specific rows, reverting to global defaults. */
    @Transactional
    public void resetProjectScores(String projectId) {
        scoreRepository.deleteAllByProjectId(projectId);
        log.info("OperationQaScoringService: reset project scores for projectId={}", projectId);
    }

    private void validateScore(int score) {
        if (score < -2 || score > 2) {
            throw new IllegalArgumentException("Score must be in range [-2, 2], got: " + score);
        }
    }
}
