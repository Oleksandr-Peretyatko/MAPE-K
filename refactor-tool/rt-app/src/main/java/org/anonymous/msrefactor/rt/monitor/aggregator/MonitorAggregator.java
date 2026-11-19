package org.anonymous.msrefactor.rt.monitor.aggregator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.AggregateStepLatency;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.SagaMetrics;
import org.anonymous.msrefactor.rt.monitor.collector.JaegerCollector.SpanSummary;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns raw Jaeger spans into the per-service and per-SAGA metrics of a {@link MonitorSnapshot}.
 *
 * <h3>Service metrics</h3>
 * All spans for a service are split into reads (operation name contains "get", "find",
 * "query", "read") and writes (everything else). Averages and the p95 are computed
 * across the combined set.
 *
 * <h3>SAGA workflow metrics</h3>
 * Root spans whose operation name ends with {@code FunctionalitySagas}
 * are treated as SAGA workflow roots. Their child spans
 * (same traceId, different service) count as cross-service calls.
 *
 * <h3>Consistency violations</h3>
 * Spans tagged {@code isInvariantBreak=true} are counted as consistency violations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorAggregator {

    @Value("${rt.monitor.jaeger.saga-root-suffixes:FunctionalitySagas,Functionality}")
    private List<String> sagaRootSuffixes;

    /**
     * Computes service and SAGA metrics from a flat span list. This is the aggregation the
     * pipeline ({@code MonitorPipelineService}) and the stepwise Jaeger endpoint share; the snapshot
     * itself is assembled by the caller.
     *
     * @param spans        all spans collected across services
     * @param services     discovered service names
     * @param window       observation window duration
     * @param collectedAt  timestamp of collection end
     * @param windowMinutes window size in minutes (carried through to the result DTO)
     */
    public JaegerCollectionResult aggregateJaeger(List<SpanSummary> spans,
                                                   List<String> services,
                                                   Duration window,
                                                   Instant collectedAt,
                                                   int windowMinutes) {
        Map<String, AggregateMetrics> aggMetrics  = buildServiceMetrics(spans, services, window);
        stampLatencySource(aggMetrics);
        Map<String, SagaMetrics>      sagaMetrics = buildSagaMetrics(spans);
        int violations = (int) spans.stream().filter(SpanSummary::isInvariantBreak).count();
        return JaegerCollectionResult.builder()
                .aggregateMetrics(aggMetrics)
                .sagaMetrics(sagaMetrics)
                .consistencyViolationCount(violations)
                .collectedAt(collectedAt)
                .windowMinutes(windowMinutes)
                .build();
    }

    // ─── Aggregate name derivation ────────────────────────────────────────────

    private static final List<String> KNOWN_AGGREGATE_NAMES =
            List.of("Tournament", "Quiz", "Question", "Execution", "Topic", "Course", "User", "Answer");

    /**
     * Derives the canonical aggregate service name from a step operation name.
     * Uses the quizzes naming convention where step names embed the aggregate
     * (e.g. {@code CreateTournament} → {@code tournament-service}).
     * Returns {@code null} if no known aggregate is found (infra/delay spans).
     */
    private String deriveAggregateName(String opName) {
        if (opName == null) return null;
        for (String agg : KNOWN_AGGREGATE_NAMES) {
            if (opName.contains(agg)) return agg.toLowerCase() + "-service";
        }
        return null;
    }

    // ─── Service metrics ─────────────────────────────────────────────────────

    private Map<String, AggregateMetrics> buildServiceMetrics(List<SpanSummary> spans,
                                                               List<String> services,
                                                               Duration window) {
        Map<String, List<SpanSummary>> byService = spans.stream()
                .collect(Collectors.groupingBy(SpanSummary::getServiceName));

        // The keys come from the spans, not from `services`. Jaeger is queried per service but
        // returns whole *traces*, and JaegerCollector labels every span with its own producer via
        // the trace's process map — so fetching quiz's traces also yields spans belonging to the
        // simulator's synthetic `root` service, which has no compose block, no database and no code
        // to refactor. Restricting to the known list (not merely padding it) is what keeps such
        // telemetry from becoming a service everywhere downstream: an aggregateMetrics entry, a
        // discoveredServices name and a hotspot.
        //
        // Skipped when `services` is empty — that means topology resolution failed, and an empty
        // allowlist would discard every measurement rather than degrade.
        if (!services.isEmpty()) {
            byService.keySet().retainAll(new HashSet<>(services));
        }

        // Ensure every known service appears in the map even if no spans arrived
        services.forEach(s -> byService.putIfAbsent(s, Collections.emptyList()));

        // Computed once over every span: self time needs the whole trace, not one service's slice.
        Map<String, Long> selfTimeUs = selfTimeBySpanId(spans);

        Map<String, AggregateMetrics> result = new LinkedHashMap<>();
        byService.forEach((svc, svcSpans) ->
                result.put(svc, computeServiceMetrics(svc, svcSpans, window, selfTimeUs)));

        // Expand monolith services: if a single service's step spans map to multiple
        // known aggregate names, replace that service entry with per-aggregate entries.
        //
        // Only for a monolith deployment (a single service in the topology). In a microservice
        // deployment each service is a deployment unit even when it runs several aggregates'
        // steps — after a merge, course-service runs Question steps, and splitting it back would
        // resurrect the dissolved question-service in every snapshot.
        if (services.size() != 1) return result;
        Map<String, AggregateMetrics> expanded = new LinkedHashMap<>(result);
        for (String svc : new ArrayList<>(result.keySet())) {
            List<SpanSummary> svcSpans = byService.getOrDefault(svc, Collections.emptyList());
            List<SpanSummary> stepLike = svcSpans.stream()
                    .filter(this::isWorkSpan)
                    .collect(Collectors.toList());

            Map<String, List<SpanSummary>> byAggregate = new LinkedHashMap<>();
            for (SpanSummary s : stepLike) {
                String agg = deriveAggregateName(s.getOperationName());
                if (agg != null) byAggregate.computeIfAbsent(agg, k -> new ArrayList<>()).add(s);
            }

            if (byAggregate.size() > 1) {
                expanded.remove(svc);
                byAggregate.forEach((agg, aggSpans) ->
                        expanded.put(agg, computeServiceMetrics(agg, aggSpans, window, selfTimeUs)));
            }
        }
        return expanded;
    }

    /**
     * @param selfTimeUs span key → self time, from {@link #selfTimeBySpanId}. Latency is derived
     *                   from this rather than from raw span duration; see the method's javadoc.
     */
    private AggregateMetrics computeServiceMetrics(String svc, List<SpanSummary> spans,
                                                   Duration window, Map<String, Long> selfTimeUs) {
        if (spans.isEmpty()) {
            return AggregateMetrics.builder().serviceName(svc).build();
        }

        // Latency describes work, so it comes from work spans' self time. Volume, errors, payload
        // and read/write mix describe traffic, so they stay on every span the service produced —
        // an error or a payload on a root span is real.
        List<SpanSummary> work = spans.stream().filter(this::isWorkSpan).collect(Collectors.toList());

        double avgRead  = avgSelfMs(work.stream().filter(this::isRead).toList(), selfTimeUs);
        double avgWrite = avgSelfMs(work.stream().filter(s -> !isRead(s)).toList(), selfTimeUs);

        List<Long> selfUs = work.stream().map(s -> selfTime(s, selfTimeUs))
                .sorted().collect(Collectors.toList());
        double p95 = percentileMs(selfUs, 0.95);

        long errors = spans.stream().filter(SpanSummary::isHasError).count();
        double errorRate = (double) errors / spans.size();

        double windowSec = window.toSeconds() > 0 ? window.toSeconds() : 300.0;
        long opsPerMin = Math.round((spans.size() / windowSec) * 60.0);

        double totalBytes = spans.stream().mapToLong(SpanSummary::getPayloadBytes).sum();
        double bandwidthBps = totalBytes / windowSec;

        long readCount  = spans.stream().filter(this::isRead).count();
        long writeCount = spans.size() - readCount;
        double readWriteRatio = writeCount == 0 ? readCount : (double) readCount / writeCount;

        return AggregateMetrics.builder()
                .serviceName(svc)
                .avgReadLatencyMs(avgRead)
                .avgWriteLatencyMs(avgWrite)
                .p95LatencyMs(p95)
                .errorRate(errorRate)
                .operationFrequency(opsPerMin)
                .bandwidthBytesPerSec(bandwidthBps)
                .readWriteRatio(readWriteRatio)
                .totalSpanCount((long) spans.size())
                .measuredSpanCount((long) work.size())
                .build();
    }

    /**
     * A span that represents work, as opposed to trace scaffolding.
     *
     * <p>{@code TraceManager} opens a root span per service per functionality that stays open for
     * the whole workflow; counting its duration as latency reports workflow duration once per
     * service, which is what made all eight services report ~29.7 s within 1 ms of each other.
     * Functionality roots are the workflow itself (already measured by {@code SagaMetrics}) and
     * {@code before}/{@code after} are artificial delays.
     */
    private boolean isWorkSpan(SpanSummary s) {
        return !s.isServiceRoot() && !s.isRootSpan() && !isSagaRoot(s) && !isDelaySpan(s);
    }

    /**
     * Self time per span: its duration minus the time it was blocked on its direct children.
     *
     * <p>A span's raw duration includes everything it waited for downstream, so attributing it to
     * the service that opened the span charges that service for other services' work. Subtracting
     * the children leaves the time this service actually spent.
     *
     * <p>Keyed by trace *and* span id: Jaeger only guarantees span ids to be unique within a trace,
     * and a collision across traces would mix one workflow's timings into another's.
     */
    private Map<String, Long> selfTimeBySpanId(List<SpanSummary> spans) {
        Map<String, Long> childDurationUs = new HashMap<>();
        for (SpanSummary s : spans) {
            if (s.getParentSpanId() != null) {
                childDurationUs.merge(spanKey(s.getTraceId(), s.getParentSpanId()),
                        s.getDurationUs(), Long::sum);
            }
        }
        Map<String, Long> selfUs = new HashMap<>();
        for (SpanSummary s : spans) {
            String key = spanKey(s.getTraceId(), s.getSpanId());
            // Clamped: concurrent children can sum past the parent's duration.
            selfUs.put(key, Math.max(0L, s.getDurationUs() - childDurationUs.getOrDefault(key, 0L)));
        }
        return selfUs;
    }

    private static String spanKey(String traceId, String spanId) {
        return traceId + "/" + spanId;
    }

    /** Falls back to raw duration when the span is absent from the index (partial trace). */
    private long selfTime(SpanSummary s, Map<String, Long> selfTimeUs) {
        return selfTimeUs.getOrDefault(spanKey(s.getTraceId(), s.getSpanId()), s.getDurationUs());
    }

    private double avgSelfMs(List<SpanSummary> spans, Map<String, Long> selfTimeUs) {
        return spans.stream().mapToLong(s -> selfTime(s, selfTimeUs)).average().orElse(0.0) / 1000.0;
    }

    private boolean isRead(SpanSummary s) {
        if (s.getOperationName() == null) return false;
        String op = s.getOperationName().toLowerCase();
        return op.startsWith("get") || op.startsWith("find") ||
               op.startsWith("query") || op.startsWith("read") ||
               op.startsWith("fetch");
    }

    // ─── SAGA workflow metrics ────────────────────────────────────────────────

    private Map<String, SagaMetrics> buildSagaMetrics(List<SpanSummary> spans) {
        // Index spans by traceId for child lookup
        Map<String, List<SpanSummary>> byTrace = spans.stream()
                .collect(Collectors.groupingBy(SpanSummary::getTraceId));

        // Index spans by spanId for direct-parent lookup (step breakdown)
        Map<String, SpanSummary> bySpanId = spans.stream()
                .collect(Collectors.toMap(SpanSummary::getSpanId, s -> s, (a, b) -> a));

        // Functionality spans of SAGA workflows (NOT root spans — they are children of
        // the service root span, named "{FuncName}::N")
        List<SpanSummary> funcSpans = spans.stream()
                .filter(this::isSagaRoot)
                .collect(Collectors.toList());

        // Group by normalised workflow name (strips the ::N counter suffix)
        Map<String, List<SpanSummary>> byWorkflow = funcSpans.stream()
                .collect(Collectors.groupingBy(s -> normalizeWorkflowName(s.getOperationName())));

        Map<String, SagaMetrics> result = new LinkedHashMap<>();
        byWorkflow.forEach((name, workflowFuncSpans) ->
                result.put(name, computeSagaMetrics(name, workflowFuncSpans, byTrace, bySpanId)));
        return result;
    }

    private boolean isSagaRoot(SpanSummary s) {
        // Primary: TraceManager sets tag "functionality" on every Functionality root span.
        // The actual operation name is the camelCase method name + "::N" counter
        // (e.g. "addParticipant::0"), NOT the class name — so suffix matching on
        // FunctionalitySagas is a fallback only for hand-crafted spans.
        if (s.isFunctionalitySpan()) return true;
        if (s.getOperationName() == null) return false;
        // Fallback: strip ::N and check configured suffixes (covers class-name-based naming)
        String base = s.getOperationName().replaceAll("::\\d+$", "");
        return sagaRootSuffixes.stream().anyMatch(base::endsWith);
    }

    private String normalizeWorkflowName(String opName) {
        return opName != null ? opName.replaceAll("::\\d+$", "") : "unknown";
    }

    private SagaMetrics computeSagaMetrics(String name, List<SpanSummary> funcSpans,
                                            Map<String, List<SpanSummary>> byTrace,
                                            Map<String, SpanSummary> bySpanId) {
        List<Long> totalDurationsUs = funcSpans.stream()
                .map(SpanSummary::getDurationUs).sorted().collect(Collectors.toList());

        double avgLatency = avgDurationMs(funcSpans);
        double p95Latency = percentileMs(totalDurationsUs, 0.95);

        // Cross-service calls = child spans from a different service than the functionality span
        List<Integer> crossServiceCounts = new ArrayList<>();
        Set<String> allInvolvedServices = new LinkedHashSet<>();
        long compensationCount = 0;

        // Collect step spans per aggregate across all executions of this workflow
        Map<String, List<SpanSummary>> stepSpansByAggregate = new LinkedHashMap<>();

        for (SpanSummary funcSpan : funcSpans) {
            List<SpanSummary> traceSpans = byTrace.getOrDefault(funcSpan.getTraceId(), Collections.emptyList());

            // All spans in the trace that are not the functionality span itself
            List<SpanSummary> others = traceSpans.stream()
                    .filter(s -> !s.getSpanId().equals(funcSpan.getSpanId()))
                    .collect(Collectors.toList());

            long crossService = others.stream()
                    .filter(c -> !c.getServiceName().equals(funcSpan.getServiceName()))
                    .count();
            crossServiceCounts.add((int) crossService);

            others.stream().map(SpanSummary::getServiceName).forEach(allInvolvedServices::add);
            allInvolvedServices.add(funcSpan.getServiceName());

            boolean hasCompensation = others.stream().anyMatch(SpanSummary::isCompensation);
            if (hasCompensation) compensationCount++;

            // Direct children of this functionality span = step spans
            traceSpans.stream()
                    .filter(s -> funcSpan.getSpanId().equals(s.getParentSpanId()))
                    .filter(s -> !isDelaySpan(s))
                    .forEach(step -> stepSpansByAggregate
                            .computeIfAbsent(step.getServiceName(), k -> new ArrayList<>())
                            .add(step));
        }

        double avgCross = crossServiceCounts.stream()
                .mapToInt(Integer::intValue).average().orElse(0.0);
        double compensationRate = funcSpans.isEmpty() ? 0.0 : (double) compensationCount / funcSpans.size();

        // If all step spans share the same service name (monolith), repartition by
        // aggregate name derived from the operation name so each aggregate gets its own entry.
        final Map<String, List<SpanSummary>> stepSpansForBreakdown;
        if (stepSpansByAggregate.size() == 1) {
            Map<String, List<SpanSummary>> repartitioned = new LinkedHashMap<>();
            stepSpansByAggregate.values().stream().flatMap(List::stream).forEach(step -> {
                String agg = deriveAggregateName(step.getOperationName());
                String key = agg != null ? agg : step.getServiceName();
                repartitioned.computeIfAbsent(key, k -> new ArrayList<>()).add(step);
            });
            stepSpansForBreakdown = repartitioned.size() > 1 ? repartitioned : stepSpansByAggregate;
        } else {
            stepSpansForBreakdown = stepSpansByAggregate;
        }

        // Build per-aggregate step latency breakdown
        Map<String, AggregateStepLatency> stepBreakdown = new LinkedHashMap<>();
        stepSpansForBreakdown.forEach((svc, steps) -> {
            List<Long> sortedUs = steps.stream()
                    .map(SpanSummary::getDurationUs).sorted().collect(Collectors.toList());
            stepBreakdown.put(svc, AggregateStepLatency.builder()
                    .aggregateName(svc)
                    .avgStepLatencyMs(avgDurationMs(steps))
                    .p95StepLatencyMs(percentileMs(sortedUs, 0.95))
                    .stepCount(steps.size())
                    .build());
        });

        return SagaMetrics.builder()
                .workflowName(name)
                .avgTotalLatencyMs(avgLatency)
                .p95TotalLatencyMs(p95Latency)
                .avgCrossServiceCalls((int) Math.round(avgCross))
                .compensationRate(compensationRate)
                .executionCount((long) funcSpans.size())
                .involvedServices(new ArrayList<>(allInvolvedServices))
                .stepBreakdown(stepBreakdown.isEmpty() ? null : stepBreakdown)
                .build();
    }

    private boolean isDelaySpan(SpanSummary s) {
        return "before".equals(s.getOperationName()) || "after".equals(s.getOperationName());
    }

    // ─── Load-test fallback ───────────────────────────────────────────────────

    /**
     * Sets {@link AggregateMetrics#latencySource} from whether any latency was actually measurable.
     *
     * <p>Keyed on {@code measuredSpanCount}, not {@code totalSpanCount}: a service can produce spans
     * and still have nothing to measure, if all of them are trace scaffolding. Reporting
     * {@code "jaeger"} there would present a 0 ms p95 as a real measurement — and 0 ms normalises to
     * a perfect score, so absent data would read as the best possible result.
     *
     * <p>Load-test data is shown in its own dedicated panel and is never merged into Jaeger metrics.
     */
    private void stampLatencySource(Map<String, AggregateMetrics> aggMap) {
        for (AggregateMetrics agg : aggMap.values()) {
            agg.setLatencySource(agg.getMeasuredSpanCount() > 0 ? "jaeger" : "none");
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private double avgDurationMs(List<SpanSummary> spans) {
        return spans.stream().mapToLong(SpanSummary::getDurationUs)
                .average().orElse(0.0) / 1000.0;
    }

    private double percentileMs(List<Long> sortedUs, double p) {
        if (sortedUs.isEmpty()) return 0.0;
        int idx = (int) Math.max(0, Math.ceil(p * sortedUs.size()) - 1);
        return sortedUs.get(idx) / 1000.0;
    }
}
