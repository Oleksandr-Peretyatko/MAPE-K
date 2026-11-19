package org.anonymous.msrefactor.rt.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated metrics for one Locust test scenario (sampler name), parsed from
 * a Locust {@code *_stats.csv} file.
 *
 * <p>Locust stats.csv columns used:
 * <pre>
 * Type, Name, Request Count, Failure Count, Median Response Time,
 * Average Response Time, Min Response Time, Max Response Time,
 * Average Content Size, Requests/s, Failures/s,
 * 50%, 66%, 75%, 80%, 90%, 95%, 98%, 99%, 99.9%, 99.99%, 100%
 * </pre>
 *
 * <p>This model feeds the quality-attribute signals in the Analyze phase:
 * <ul>
 *   <li><b>Performance</b> — throughput, latency percentiles, latency variance</li>
 *   <li><b>Consistency</b> — failure rate under concurrency, burst failure patterns</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocustScenarioMetrics {

    /** Name of the Locust task / request label (from the "Name" column). */
    private String scenarioName;

    /** File name of the {@code *_stats.csv} source file. */
    private String sourceFile;

    /** Total number of requests issued in the test run. */
    private int requestCount;

    /** Total number of failed requests. */
    private int failureCount;

    /**
     * Failure rate: {@code failureCount / requestCount}.
     * Zero when no requests were made.
     * Maps to the {@code failure_rate} metric in the catalog.
     */
    private double errorRate;

    // ─── Latency percentiles (ms) ─────────────────────────────────────────────

    /** Median (50th-percentile) response time in ms. May be 0 if no requests. */
    private double medianResponseMs;

    /** Mean response time in ms. */
    private double avgResponseMs;

    /** Minimum observed response time in ms. */
    private double minResponseMs;

    /** Maximum observed response time in ms. */
    private double maxResponseMs;

    /** 95th-percentile response time in ms (maps to {@code p95_response_ms}). */
    private double p95ResponseMs;

    /** 99th-percentile response time in ms (maps to {@code p99_response_ms}). */
    private double p99ResponseMs;

    /**
     * 100th-percentile (absolute max via percentile column) response time in ms.
     * Maps to {@code max_response_ms} in the catalog.
     */
    private double maxPctResponseMs;

    // ─── Throughput ───────────────────────────────────────────────────────────

    /** Requests per second (maps to {@code throughput_rps}). */
    private double requestsPerSec;

    /** Failed requests per second (maps to {@code failure_rate} time-series component). */
    private double failuresPerSec;

    // ─── Size ────────────────────────────────────────────────────────────────

    /** Average HTTP response body size in bytes (maps to {@code avg_content_size_bytes}). */
    private double avgContentSizeBytes;
}
