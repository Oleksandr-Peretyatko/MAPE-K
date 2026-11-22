package org.anonymous.msrefactor.rt.monitor;

import java.util.List;

/**
 * Result of a single Locust collection attempt.
 *
 * <p>Carries both the collected scenarios (may be empty) and an explicit status string
 * so callers can distinguish between "collection succeeded but found nothing" and
 * "collection failed entirely" — both return an empty list but have different meanings.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code OK} — scenarios returned successfully</li>
 *   <li>{@code NO_ACTIVE_RUN} — no active run_id was registered by this rt-app instance</li>
 *   <li>{@code RUN_NOT_FOUND} — run_id present but locust-service returned 404</li>
 *   <li>{@code SERVICE_UNREACHABLE} — HTTP call to locust-service threw an exception</li>
 *   <li>{@code EMPTY} — call succeeded but 0 endpoint metric rows were returned</li>
 * </ul>
 */
public record LocustCollectionResult(List<LocustScenarioMetrics> scenarios, String status) {

    public static final String OK                  = "OK";
    public static final String NO_ACTIVE_RUN       = "NO_ACTIVE_RUN";
    public static final String RUN_NOT_FOUND       = "RUN_NOT_FOUND";
    public static final String SERVICE_UNREACHABLE = "SERVICE_UNREACHABLE";
    public static final String EMPTY               = "EMPTY";

    public static LocustCollectionResult ok(List<LocustScenarioMetrics> scenarios) {
        return new LocustCollectionResult(scenarios, scenarios.isEmpty() ? EMPTY : OK);
    }

    public static LocustCollectionResult noActiveRun() {
        return new LocustCollectionResult(List.of(), NO_ACTIVE_RUN);
    }

    public static LocustCollectionResult runNotFound() {
        return new LocustCollectionResult(List.of(), RUN_NOT_FOUND);
    }

    public static LocustCollectionResult unreachable() {
        return new LocustCollectionResult(List.of(), SERVICE_UNREACHABLE);
    }
}
