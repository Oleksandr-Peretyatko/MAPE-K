package org.anonymous.msrefactor.rt.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotEntity;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotRepository;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Computes quality attribute scores from a persisted {@link MonitorSnapshot}.
 *
 * <p>Scores are computed on-demand (not cached); callers may add their own caching layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityScoreService {

    private final MonitorSnapshotRepository snapshotRepository;
    private final QualityAttributeCalculator calculator;
    private final ObjectMapper objectMapper;

    /**
     * Loads the snapshot identified by {@code snapshotId} and calculates quality attribute scores.
     *
     * @return empty when the snapshot is not found or cannot be deserialized
     */
    public Optional<QualityScoreResult> calculateQualityScores(String snapshotId) {
        return snapshotRepository.findById(snapshotId)
                .flatMap(this::deserialize)
                .map(snapshot -> compute(snapshotId, snapshot));
    }

    /**
     * Computes quality attribute scores from an already-deserialized {@link MonitorSnapshot},
     * avoiding a second DB round-trip when the snapshot is already in memory.
     */
    public List<QualityAttributeScore> computeFromSnapshot(MonitorSnapshot snapshot) {
        StaticMetrics sm = latestStaticMetrics(snapshot);
        return calculator.calculateAll(
                snapshot.getAggregateMetrics(),
                snapshot.getLocustScenarios(),
                sm,
                snapshot.getSagaMetrics());
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private QualityScoreResult compute(String snapshotId, MonitorSnapshot snapshot) {
        StaticMetrics staticMetrics = latestStaticMetrics(snapshot);

        List<QualityAttributeScore> scores = calculator.calculateAll(
                snapshot.getAggregateMetrics(),
                snapshot.getLocustScenarios(),
                staticMetrics,
                snapshot.getSagaMetrics());

        log.info("QualityScores [{}]: computed {} scores", snapshotId, scores.size());

        return QualityScoreResult.builder()
                .snapshotId(snapshotId)
                .version(resolveVersion(snapshot))
                .generatedAt(Instant.now().toString())
                .pipelineStatus(snapshot.getPipelineStatus())
                .scores(scores)
                .build();
    }

    /** Picks the most recently collected static version, or null when none exist. */
    private StaticMetrics latestStaticMetrics(MonitorSnapshot snapshot) {
        List<VersionAnalysisResult> versions = snapshot.getStaticVersions();
        if (versions == null || versions.isEmpty()) return null;
        // Last entry is the most recent version collected
        return versions.get(versions.size() - 1).getMetrics();
    }

    private String resolveVersion(MonitorSnapshot snapshot) {
        List<VersionAnalysisResult> versions = snapshot.getStaticVersions();
        if (versions == null || versions.isEmpty()) return "current";
        VersionAnalysisResult last = versions.get(versions.size() - 1);
        return last.getVersion() != null ? last.getVersion() : "current";
    }

    private Optional<MonitorSnapshot> deserialize(MonitorSnapshotEntity entity) {
        try {
            return Optional.of(objectMapper.readValue(entity.getPayload(), MonitorSnapshot.class));
        } catch (Exception e) {
            log.error("QualityScores: failed to deserialize snapshot {}: {}", entity.getSnapshotId(), e.getMessage());
            return Optional.empty();
        }
    }
}
