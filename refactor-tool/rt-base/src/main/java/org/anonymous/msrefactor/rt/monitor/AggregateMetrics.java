package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime metrics for a single microservice / aggregate, collected over one observation window.
 *
 * <p>Read and write latencies are separated because in DDD/SAGAS architectures reads (query steps)
 * and writes (command steps) have very different latency profiles and are targeted differently by
 * refactoring operations such as {@code CreateReplica} (eliminates read cross-service calls) vs
 * {@code MergeAggregates} (eliminates both).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AggregateMetrics {

    /** Service name as registered in Jaeger / Eureka (e.g. {@code tournament-service}). */
    private String serviceName;

    /** Average latency of read (query) operations in milliseconds. */
    private double avgReadLatencyMs;

    /** Average latency of write (command / mutation) operations in milliseconds. */
    private double avgWriteLatencyMs;

    /** 95th-percentile latency across all operations in milliseconds. */
    private double p95LatencyMs;

    /**
     * Fraction of spans that ended with an error tag ({@code error=true}).
     * Range: 0.0 (no errors) – 1.0 (all errors).
     */
    private double errorRate;

    /** Number of operations per minute observed in the collection window. */
    private long operationFrequency;

    /**
     * Estimated average bandwidth consumed by this service in bytes/second.
     * Derived from span payload-size tags when available, otherwise approximated
     * from {@code operationFrequency × estimated-avg-message-size}.
     */
    private double bandwidthBytesPerSec;

    /**
     * Ratio of read operations to write operations.
     * A value > 1 means the service is read-heavy; < 1 means write-heavy.
     */
    private double readWriteRatio;

    /** Total span count observed in the collection window (reads + writes). */
    private long totalSpanCount;

    /**
     * How many of those spans the latency figures were actually derived from — the "work" spans,
     * excluding trace scaffolding (service/master root spans), functionality roots and delay spans.
     *
     * <p>Reported separately from {@link #totalSpanCount} because a p95 computed over two spans and
     * one computed over two thousand are not the same claim, and nothing else in the snapshot says
     * which it is.
     */
    private long measuredSpanCount;

    /**
     * Source of the latency metrics for this entry.
     * {@code "jaeger"} — derived from distributed traces.
     * {@code "locust"} — backfilled from Locust load-test results (Jaeger had no spans).
     * {@code "none"}   — no latency data available from any source.
     */
    private String latencySource;
}
