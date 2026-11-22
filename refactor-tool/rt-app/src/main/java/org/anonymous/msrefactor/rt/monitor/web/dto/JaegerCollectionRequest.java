package org.anonymous.msrefactor.rt.monitor.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /monitor/collect/jaeger}.
 *
 * <p>Callers may specify either a relative window ({@code windowMinutes}) or an explicit
 * absolute window ({@code windowStartMs} and {@code windowEndMs} in epoch milliseconds).
 * Explicit window takes precedence. If neither is provided, the server default is used.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JaegerCollectionRequest {
    /** Collection window in minutes relative to now. 0 means use the server default. */
    private int windowMinutes;

    /**
     * Explicit window start as epoch milliseconds (inclusive).
     * When set together with {@link #windowEndMs}, overrides {@link #windowMinutes}.
     */
    private Long windowStartMs;

    /**
     * Explicit window end as epoch milliseconds (inclusive).
     * When set together with {@link #windowStartMs}, overrides {@link #windowMinutes}.
     */
    private Long windowEndMs;
}
