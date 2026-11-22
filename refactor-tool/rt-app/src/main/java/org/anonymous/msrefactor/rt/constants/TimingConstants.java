package org.anonymous.msrefactor.rt.constants;

/**
 * Polling intervals and timeouts.
 *
 * <p>Defaults for {@code ExecuteConfig} / {@code MonitorConfig} fields; override via environment
 * (e.g. {@code RT_EXECUTE_DEPLOY_HEALTH_TIMEOUT_SECONDS=900}).
 */
public final class TimingConstants {

    /**
     * Cold-start budget for the deploy health gate. Step 6 tears the whole project down and
     * brings it back up, so this must cover a cold start: each service sits behind a 180s
     * healthcheck start_period and a freshly created database.
     */
    public static final int DEPLOY_HEALTH_TIMEOUT_SECONDS = 600;

    /** How often the deploy health gate re-checks container status. */
    public static final long HEALTH_POLL_INTERVAL_MS = 5_000L;

    /** How often the Monitor pipeline polls locust-service for run completion. */
    public static final int LOCUST_POLL_INTERVAL_MS = 2_000;

    private TimingConstants() {}
}
