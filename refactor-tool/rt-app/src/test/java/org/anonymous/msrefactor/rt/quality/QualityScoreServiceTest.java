package org.anonymous.msrefactor.rt.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotEntity;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityScoreServiceTest {

    private MonitorSnapshotRepository repo;
    private QualityScoreService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repo = mock(MonitorSnapshotRepository.class);
        objectMapper = new ObjectMapper();
        QualityAttributeCalculator calculator = new QualityAttributeCalculator(new MetricNormalizer());
        service = new QualityScoreService(repo, calculator, objectMapper);
    }

    @Test
    void snapshotNotFound_returnsEmpty() {
        when(repo.findById("unknown")).thenReturn(Optional.empty());
        assertThat(service.calculateQualityScores("unknown")).isEmpty();
    }

    @Test
    void validSnapshot_returns7Scores() throws Exception {
        MonitorSnapshot snapshot = buildSnapshot();
        MonitorSnapshotEntity entity = MonitorSnapshotEntity.builder()
                .snapshotId("snap-1")
                .collectedAt(Instant.now())
                .payload(objectMapper.writeValueAsString(snapshot))
                .build();

        when(repo.findById("snap-1")).thenReturn(Optional.of(entity));

        Optional<QualityScoreResult> result = service.calculateQualityScores("snap-1");

        assertThat(result).isPresent();
        QualityScoreResult qsr = result.get();
        assertThat(qsr.getSnapshotId()).isEqualTo("snap-1");
        assertThat(qsr.getScores()).hasSize(7);
        assertThat(qsr.getGeneratedAt()).isNotBlank();
    }

    @Test
    void validSnapshot_allScoresInRange() throws Exception {
        MonitorSnapshot snapshot = buildSnapshot();
        MonitorSnapshotEntity entity = MonitorSnapshotEntity.builder()
                .snapshotId("snap-2").collectedAt(Instant.now())
                .payload(objectMapper.writeValueAsString(snapshot)).build();

        when(repo.findById("snap-2")).thenReturn(Optional.of(entity));

        QualityScoreResult result = service.calculateQualityScores("snap-2").orElseThrow();
        for (QualityAttributeScore score : result.getScores()) {
            assertThat(score.getScore()).isBetween(0.0, 1.0);
            assertThat(score.getStatus()).isIn("poor", "warning", "good", "excellent");
        }
    }

    @Test
    void snapshotWithNoStaticVersions_stillReturns7Scores() throws Exception {
        MonitorSnapshot snapshot = MonitorSnapshot.builder()
                .snapshotId("snap-3")
                .aggregateMetrics(Map.of("svc", AggregateMetrics.builder()
                        .p95LatencyMs(100).errorRate(0.001).operationFrequency(60)
                        .bandwidthBytesPerSec(10000).readWriteRatio(2.0).build()))
                .locustScenarios(List.of())
                .staticVersions(List.of())
                .build();

        MonitorSnapshotEntity entity = MonitorSnapshotEntity.builder()
                .snapshotId("snap-3").collectedAt(Instant.now())
                .payload(objectMapper.writeValueAsString(snapshot)).build();

        when(repo.findById("snap-3")).thenReturn(Optional.of(entity));

        Optional<QualityScoreResult> result = service.calculateQualityScores("snap-3");
        assertThat(result).isPresent();
        assertThat(result.get().getScores()).hasSize(7);
    }

    @Test
    void corruptPayload_returnsEmpty() {
        MonitorSnapshotEntity entity = MonitorSnapshotEntity.builder()
                .snapshotId("snap-bad").collectedAt(Instant.now())
                .payload("NOT VALID JSON").build();

        when(repo.findById("snap-bad")).thenReturn(Optional.of(entity));
        assertThat(service.calculateQualityScores("snap-bad")).isEmpty();
    }

    @Test
    void versionResolution_usesLastStaticVersion() throws Exception {
        MonitorSnapshot snapshot = buildSnapshot(); // last version = "v1"
        MonitorSnapshotEntity entity = MonitorSnapshotEntity.builder()
                .snapshotId("snap-v").collectedAt(Instant.now())
                .payload(objectMapper.writeValueAsString(snapshot)).build();

        when(repo.findById("snap-v")).thenReturn(Optional.of(entity));

        QualityScoreResult result = service.calculateQualityScores("snap-v").orElseThrow();
        assertThat(result.getVersion()).isEqualTo("v1");
    }

    // ─── Fixture ─────────────────────────────────────────────────────────────

    private MonitorSnapshot buildSnapshot() {
        StaticMetrics sm = StaticMetrics.builder()
                .m1(0.70).m2(0.20).serviceCount(2)
                .services(Map.of(
                        "quiz", ServiceStaticMetrics.builder()
                                .entityCount(3).internalRelations(2)
                                .externalRelations(Map.of("tournament", 1)).build(),
                        "tournament", ServiceStaticMetrics.builder()
                                .entityCount(4).internalRelations(3)
                                .externalRelations(Map.of("quiz", 1)).build()))
                .build();

        return MonitorSnapshot.builder()
                .snapshotId("snap-1")
                .aggregateMetrics(Map.of(
                        "quiz", AggregateMetrics.builder()
                                .p95LatencyMs(150).avgReadLatencyMs(80).avgWriteLatencyMs(120)
                                .errorRate(0.002).operationFrequency(120).bandwidthBytesPerSec(40_000)
                                .readWriteRatio(3.0).totalSpanCount(200).build()))
                .locustScenarios(List.of())
                .staticVersions(List.of(
                        VersionAnalysisResult.builder().version("v1").metrics(sm).build()))
                .build();
    }
}
