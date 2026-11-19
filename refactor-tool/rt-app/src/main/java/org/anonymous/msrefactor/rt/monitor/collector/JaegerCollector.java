package org.anonymous.msrefactor.rt.monitor.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployTopology;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Collects distributed trace data from the Jaeger Query HTTP API.
 *
 * <p>Jaeger API endpoints used:
 * <ul>
 *   <li>{@code GET /api/services} — discovers all registered service names</li>
 *   <li>{@code GET /api/traces?service={svc}&limit={n}&start={μs}&end={μs}}
 *       — fetches recent traces for a service</li>
 * </ul>
 *
 * <p>The collector maps raw Jaeger JSON into {@link SpanSummary} records that the
 * {@link org.anonymous.msrefactor.rt.monitor.aggregator.MonitorAggregator}
 * uses to compute per-service and per-SAGA-workflow metrics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JaegerCollector {

    @Value("${rt.monitor.jaeger.base-url}")
    private String jaegerBaseUrl;

    /**
     * Static seed, used only when topology resolution yields nothing. Jaeger identifies a service
     * by its {@code spring.application.name} ({@code tournament}), not its compose service name
     * ({@code tournament-service}), so this list must hold unsuffixed names.
     */
    @Value("${rt.monitor.jaeger.known-services:}")
    private List<String> knownServicesSeed;

    @Value("${rt.monitor.jaeger.excluded-services:jaeger-all-in-one,jaeger}")
    private List<String> excludedServices;

    /** {@code TraceManager} root-span names: {@code root7} (master) and {@code quiz-root7} (service). */
    private static final Pattern ROOT_SPAN_NAME = Pattern.compile("^(?:[A-Za-z0-9_-]+-)?root\\d+$");

    private final RestTemplate restTemplate;
    private final ComposeTopologyResolver topologyResolver;

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the services to collect spans for. **The deployed topology is the allowlist**: Jaeger
     * discovery confirms what is deployed, it does not define it.
     *
     * <p>Not every OTel {@code service.name} is a deployment unit. The simulator emits a synthetic
     * {@code root} service from a second {@code SdkTracerProvider} in {@code TraceManager}, whose
     * only job is to publish one master span per functionality that parents each service's own root
     * span. It has no compose block, no database and no source to refactor, but it used to be
     * unioned in — and since {@code MonitorAggregator} seeds one {@code aggregateMetrics} entry per
     * discovered name, it became a service everywhere downstream: its span stays open for a whole
     * functionality, so its duration silently became the {@code max()} that defines the PERFORMANCE
     * p95 and Analyze was shown a service no operation can target.
     *
     * <p>Deriving the allowlist from the topology rather than a denylist also keeps it correct
     * across the catalogue's own operations — a merge removes a service, a split adds one — with no
     * list to maintain.
     *
     * <p>When topology resolution yields nothing there is no allowlist to apply, so the old
     * behaviour stands: static seed plus whatever Jaeger reports, minus {@code excluded-services}.
     * That path is what keeps a resolver failure from producing a blank snapshot.
     *
     */
    public List<String> collectServices() {
        return collectServices(topologyResolver.current());
    }

    /**
     * As {@link #collectServices()}, against an explicit topology — the one the measured version was
     * actually deployed with — instead of the resolver's cache, which reflects whatever branch the
     * shared working tree happened to be on when it was last read.
     */
    public List<String> collectServices(DeployTopology deployed) {
        Set<String> topology = new LinkedHashSet<>(deployed == null ? List.of() : deployed.jaegerServiceNames());
        Set<String> services = topology.isEmpty() ? new LinkedHashSet<>(knownServicesSeed) : topology;

        try {
            ServicesResponse response = restTemplate.getForObject(
                    jaegerBaseUrl + "/api/services", ServicesResponse.class);
            if (response != null && response.getData() != null) {
                List<String> reported = response.getData().stream()
                        .filter(s -> !excludedServices.contains(s))
                        .toList();

                if (topology.isEmpty()) {
                    services.addAll(reported);
                } else {
                    List<String> dropped = reported.stream()
                            .filter(s -> !topology.contains(s))
                            .toList();
                    if (!dropped.isEmpty()) {
                        log.debug("Jaeger reported {} name(s) outside the deployed topology, ignored: {}",
                                dropped.size(), dropped);
                    }
                }
            }
        } catch (RestClientException e) {
            log.warn("Could not reach Jaeger at {} — using {} list only. Cause: {}",
                    jaegerBaseUrl, topology.isEmpty() ? "seed" : "topology", e.getMessage());
        }
        return new ArrayList<>(services);
    }

    /**
     * Collects spans for every service in one pass, **deduplicated by (traceId, spanId)**.
     *
     * <p>Jaeger's trace query returns whole traces, not one service's slice: asking for {@code quiz}
     * returns every span of every trace {@code quiz} took part in. Callers that loop over services
     * and concatenate therefore add each span once per service queried — with eight services, a
     * nine-span trace becomes 72 spans. That inflates {@code totalSpanCount},
     * {@code operationFrequency}, {@code bandwidthBytesPerSec} and the {@code errorRate} denominator
     * by the service count, and ops/s is a PERFORMANCE input.
     *
     * <p>Span ids are only unique within a trace, so the key is the pair.
     */
    public List<SpanSummary> collectSpans(List<String> services, Instant from, Instant to) {
        Map<String, SpanSummary> unique = new LinkedHashMap<>();
        for (String service : services) {
            for (SpanSummary span : collectSpans(service, from, to)) {
                unique.putIfAbsent(span.getTraceId() + "/" + span.getSpanId(), span);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Fetches all traces for {@code serviceName} in the window
     * [{@code from}, {@code to}] and returns them as flat {@link SpanSummary} records.
     *
     * @param serviceName Jaeger service name (e.g., {@code tournament-service})
     * @param from        start of the window (inclusive)
     * @param to          end of the window (inclusive)
     * @return flat list of span summaries; empty if Jaeger is unreachable
     */
    public List<SpanSummary> collectSpans(String serviceName, Instant from, Instant to) {
        String url = String.format(
                "%s/api/traces?service=%s&limit=1000&start=%d&end=%d",
                jaegerBaseUrl,
                serviceName,
                from.toEpochMilli() * 1000L,   // Jaeger uses microseconds
                to.toEpochMilli() * 1000L);

        try {
            TracesResponse response = restTemplate.getForObject(url, TracesResponse.class);
            if (response == null || response.getData() == null) {
                return Collections.emptyList();
            }
            return flattenTraces(response.getData(), serviceName);
        } catch (RestClientException e) {
            log.warn("Failed to fetch traces for service '{}': {}", serviceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Flattening logic ─────────────────────────────────────────────────────

    private List<SpanSummary> flattenTraces(List<JaegerTrace> traces, String defaultService) {
        List<SpanSummary> result = new ArrayList<>();
        for (JaegerTrace trace : traces) {
            Map<String, String> processMap = buildProcessMap(trace);
            for (JaegerSpan span : trace.getSpans()) {
                result.add(toSummary(span, trace.getTraceID(), processMap, defaultService));
            }
        }
        return result;
    }

    private Map<String, String> buildProcessMap(JaegerTrace trace) {
        Map<String, String> map = new HashMap<>();
        if (trace.getProcesses() != null) {
            trace.getProcesses().forEach((key, proc) -> map.put(key, proc.getServiceName()));
        }
        return map;
    }

    private SpanSummary toSummary(JaegerSpan span, String traceId,
                                   Map<String, String> processMap, String defaultService) {
        boolean hasError = span.getTags() != null && span.getTags().stream()
                .anyMatch(t -> "error".equals(t.getKey()) && "true".equals(String.valueOf(t.getValue())));

        // TraceManager names compensation spans "[Compensate]funcName::N"
        boolean isCompensation = span.getOperationName() != null &&
                (span.getOperationName().contains("compensation") ||
                 span.getOperationName().contains("Compensation") ||
                 span.getOperationName().startsWith("[Compensate]"));

        boolean isInvariantBreak = span.getTags() != null && span.getTags().stream()
                .anyMatch(t -> "exception.message".equals(t.getKey()) &&
                               t.getValue() != null &&
                               t.getValue().toString().contains("INVARIANT_BREAK"));

        String serviceName = processMap.getOrDefault(span.getProcessID(), defaultService);

        // Estimate payload bytes from http.response_content_length or similar tags
        long payloadBytes = span.getTags() != null ? span.getTags().stream()
                .filter(t -> "http.response_content_length".equals(t.getKey()) ||
                             "response_size".equals(t.getKey()))
                .mapToLong(t -> parseLong(t.getValue()))
                .sum() : 0L;

        // TraceManager sets tag "functionality" = methodName on every Functionality root span
        boolean isFunctionalitySpan = span.getTags() != null && span.getTags().stream()
                .anyMatch(t -> "functionality".equals(t.getKey()) && t.getValue() != null);

        boolean isServiceRoot = isServiceRootSpan(span);

        return SpanSummary.builder()
                .traceId(traceId)
                .spanId(span.getSpanID())
                .parentSpanId(span.getReferences() != null && !span.getReferences().isEmpty()
                        ? span.getReferences().get(0).getSpanID() : null)
                .operationName(span.getOperationName())
                .serviceName(serviceName)
                .durationUs(span.getDuration())
                .hasError(hasError)
                .isCompensation(isCompensation)
                .isInvariantBreak(isInvariantBreak)
                .payloadBytes(payloadBytes)
                .isRootSpan(span.getReferences() == null || span.getReferences().isEmpty())
                .isFunctionalitySpan(isFunctionalitySpan)
                .isServiceRoot(isServiceRoot)
                .build();
    }

    /**
     * True for the spans {@code TraceManager} opens to scaffold a trace rather than to do work.
     *
     * <p>There are two: the master root ({@code root<N>}, service {@code root}) and each service's
     * own root ({@code <service>-root<N>}). Both stay open for the whole functionality, so their
     * duration is workflow duration, not latency — and {@link SpanSummary#isRootSpan} does not catch
     * the second kind, because a downstream service creates its root with a remote parent
     * ({@code startRootSpan(traceId, spanId)}) and therefore *has* references.
     *
     * <p>The {@code root} tag is checked first: it is the producer's own declaration
     * ({@code span.setAttribute("root", "root")}). The name pattern is a fallback for spans whose
     * tags did not survive, and would silently stop matching if the naming convention changed.
     */
    private boolean isServiceRootSpan(JaegerSpan span) {
        boolean taggedRoot = span.getTags() != null && span.getTags().stream()
                .anyMatch(t -> "root".equals(t.getKey()) && t.getValue() != null);
        if (taggedRoot) return true;

        String op = span.getOperationName();
        return op != null && ROOT_SPAN_NAME.matcher(op).matches();
    }

    private long parseLong(Object value) {
        if (value == null) return 0L;
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return 0L; }
    }

    // ─── Internal DTOs (Jaeger JSON model) ───────────────────────────────────

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServicesResponse {
        private List<String> data;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TracesResponse {
        private List<JaegerTrace> data;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JaegerTrace {
        @JsonProperty("traceID") private String traceID;
        private List<JaegerSpan> spans;
        private Map<String, JaegerProcess> processes;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JaegerSpan {
        @JsonProperty("traceID") private String traceID;
        @JsonProperty("spanID")  private String spanID;
        private String operationName;
        private String processID;
        private long startTime;   // microseconds since epoch
        private long duration;    // microseconds
        private List<JaegerTag> tags;
        private List<JaegerReference> references;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JaegerTag {
        private String key;
        private Object value;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JaegerReference {
        @JsonProperty("spanID") private String spanID;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JaegerProcess {
        private String serviceName;
    }

    // ─── Output DTO ──────────────────────────────────────────────────────────

    /**
     * Flat span record after parsing from Jaeger JSON.
     * Passed to {@link org.anonymous.msrefactor.rt.monitor.aggregator.MonitorAggregator}.
     */
    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SpanSummary {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String operationName;
        private String serviceName;
        private long durationUs;       // microseconds
        private boolean hasError;
        private boolean isCompensation;
        private boolean isInvariantBreak;
        private long payloadBytes;
        private boolean isRootSpan;
        /**
         * True for {@code TraceManager}'s scaffolding spans — the master root and each service's
         * own root. They stay open for a whole functionality, so their duration is workflow
         * duration and must never be counted as service latency.
         *
         * <p>Distinct from {@link #isRootSpan}, which only means "has no parent": a downstream
         * service's root span is created with a remote parent and so is not flagged by it.
         */
        private boolean isServiceRoot;
        /**
         * True when the TraceManager {@code "functionality"} tag is present on this span,
         * meaning it is the root span of a Functionality invocation.
         * The tag is set by {@code TraceManager.startSpanForFunctionality()}.
         */
        private boolean isFunctionalitySpan;
    }
}
