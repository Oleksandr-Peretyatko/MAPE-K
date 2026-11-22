package org.anonymous.msrefactor.rt.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.aggregator.MonitorAggregator;
import org.anonymous.msrefactor.rt.monitor.collector.JaegerCollector;
import org.anonymous.msrefactor.rt.monitor.collector.JaegerCollector.SpanSummary;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotEntity;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotRepository;
import org.anonymous.msrefactor.rt.monitor.web.dto.AssembleSnapshotRequest;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Jaeger step-collection, snapshot assembly and read access to persisted snapshots. The full
 * static → Locust → Jaeger cycle is {@link MonitorPipelineService}; this service holds the pieces
 * it and the stepwise endpoints share.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    @Value("${rt.monitor.jaeger.collection-window-minutes:2}")
    private int collectionWindowMinutes;

    private final JaegerCollector jaegerCollector;
    private final MonitorAggregator aggregator;
    private final MonitorSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    // ─── Stepwise collection ─────────────────────────────────────────────────

    /**
     * Collects Jaeger spans using the window specified in the request and returns
     * aggregated metrics without persisting a snapshot.
     *
     * <p>If the request provides explicit epoch-millisecond bounds they are used directly;
     * otherwise the {@code windowMinutes} field is applied relative to now; if that is 0
     * the server default {@link #collectionWindowMinutes} is used.
     */
    public JaegerCollectionResult collectJaeger(org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionRequest req) {
        return collectJaeger(req, null);
    }

    /**
     * As {@link #collectJaeger(org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionRequest)},
     * restricted to {@code topology} — the deployment being measured. Null keeps the resolver's
     * cached topology (the stepwise endpoint, which has no version context).
     */
    public JaegerCollectionResult collectJaeger(org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionRequest req,
                                                org.anonymous.msrefactor.rt.topology.DeployTopology topology) {
        Instant to;
        Instant from;
        if (req.getWindowStartMs() != null && req.getWindowEndMs() != null) {
            from = Instant.ofEpochMilli(req.getWindowStartMs());
            to   = Instant.ofEpochMilli(req.getWindowEndMs());
        } else {
            int mins = req.getWindowMinutes() > 0 ? req.getWindowMinutes() : collectionWindowMinutes;
            to   = Instant.now();
            from = to.minus(Duration.ofMinutes(mins));
        }
        Duration window = Duration.between(from, to);

        List<String>      services = topology == null
                ? jaegerCollector.collectServices() : jaegerCollector.collectServices(topology);
        List<SpanSummary> spans    = jaegerCollector.collectSpans(services, from, to);
        log.info("Jaeger step-collect: {} spans from {} services (window={}s, from={}, to={})",
                spans.size(), services.size(), window.toSeconds(), from, to);

        return aggregator.aggregateJaeger(spans, services, window, to, (int) window.toMinutes());
    }

    /**
     * Assembles a {@link MonitorSnapshot} from pre-collected pipeline data and persists it.
     * Called by {@code POST /monitor/snapshot/assemble} after the stepwise pipeline completes.
     */
    @Transactional
    public MonitorSnapshot assembleAndSave(AssembleSnapshotRequest req) {
        log.info("[assemble] locustScenarios in request: {}",
                req.getLocustScenarios() == null ? "null" : req.getLocustScenarios().size() + " items");
        int windowMin = req.getWindowMinutes() > 0 ? req.getWindowMinutes() : collectionWindowMinutes;

        String snapshotId = UUID.randomUUID().toString();
        MonitorSnapshot snapshot = MonitorSnapshot.builder()
                .snapshotId(snapshotId)
                .collectedAt(Instant.now())
                .collectionWindow(Duration.ofMinutes(windowMin))
                .aggregateMetrics(req.getAggregateMetrics() != null
                        ? req.getAggregateMetrics() : Collections.emptyMap())
                .sagaMetrics(req.getSagaMetrics() != null
                        ? req.getSagaMetrics() : Collections.emptyMap())
                .locustScenarios(req.getLocustScenarios() != null
                        ? req.getLocustScenarios() : Collections.emptyList())
                .staticVersions(req.getStaticVersions() != null
                        ? req.getStaticVersions() : Collections.emptyList())
                .consistencyViolationCount(req.getConsistencyViolationCount())
                .discoveredServices(req.getAggregateMetrics() != null
                        ? new ArrayList<>(req.getAggregateMetrics().keySet())
                        : Collections.emptyList())
                .build();

        persist(snapshot);
        log.info("Assembled snapshot {} from pipeline steps", snapshotId);
        return snapshot;
    }

    // ─── Read access ─────────────────────────────────────────────────────────

    /** Returns the most recently persisted snapshot, or empty if none exists yet. */
    @Transactional(readOnly = true)
    public Optional<MonitorSnapshot> getLatest() {
        return repository.findLatest().map(this::deserialize);
    }

    /** Returns the snapshot with the given ID, or empty if not found. */
    @Transactional(readOnly = true)
    public Optional<MonitorSnapshot> getById(String snapshotId) {
        return repository.findById(snapshotId).map(this::deserialize);
    }

    // ─── Persistence helpers ─────────────────────────────────────────────────

    private void persist(MonitorSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            repository.save(MonitorSnapshotEntity.builder()
                    .snapshotId(snapshot.getSnapshotId())
                    .collectedAt(snapshot.getCollectedAt())
                    .payload(json)
                    .build());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize MonitorSnapshot {}: {}", snapshot.getSnapshotId(), e.getMessage());
        }
    }

    private MonitorSnapshot deserialize(MonitorSnapshotEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayload(), MonitorSnapshot.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize MonitorSnapshot {}: {}", entity.getSnapshotId(), e.getMessage());
            return null;
        }
    }
}
