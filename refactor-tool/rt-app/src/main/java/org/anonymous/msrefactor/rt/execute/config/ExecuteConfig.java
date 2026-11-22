package org.anonymous.msrefactor.rt.execute.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.constants.TimingConstants;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;

@Component
@ConfigurationProperties(prefix = "rt.execute")
@Data
public class ExecuteConfig {

    /** Absolute path to the repository root inside the container (/app/project). */
    private String projectRoot = "../";

    /** Absolute path to the quizzes docker-compose.yml inside the container. */
    private String composeFile = "../" + AppTargetConstants.COMPOSE_FILE;

    /** Maven profiles used for the Docker build (e.g., sagas). */
    private String buildProfiles = "test-sagas";

    private String gitUserName = "refactor-tool";
    private String gitUserEmail = "anonymous@example.org";

    /**
     * Which compose services to deploy after each build.
     *
     * <p>"auto" (the default) discovers them from the compose file via
     * {@code ComposeTopologyResolver}, so a refactoring that renames, removes or adds a
     * microservice is picked up without touching configuration. Any other value is a
     * comma-separated literal list — e.g. "quizzes-local" for the monolith deployment — which is
     * still validated against the compose file.
     */
    private String deployTarget = "auto";

    /**
     * Max seconds to wait for services to report healthy before the deploy is marked FAILED.
     * Prevents the following Monitor phase from measuring a still-starting system.
     *
     * <p>Step 6 tears the whole project down and brings it back up, so this must cover a cold
     * start: each service sits behind a 180s healthcheck start_period and a freshly created DB.
     */
    private int deployHealthTimeoutSeconds = TimingConstants.DEPLOY_HEALTH_TIMEOUT_SECONDS;

    /** Base branch from which each refactoring iteration branch is created. */
    private String baseBranch = "tool/self-refactor";

    /**
     * When true, the Sagas test gate (Step 5a) is bypassed entirely.
     * Use when the change is known-safe or when iteration speed matters more than
     * per-iteration validation. Controlled by RT_EXECUTE_SKIP_TESTS env var.
     */
    private boolean skipTests = false;

    /**
     * Max seconds the Step 5a test gate ({@code docker build --target test}) may run before it is
     * killed and the run FAILS. Without a bound a hung build blocks the pipeline forever.
     * Controlled by RT_EXECUTE_TEST_TIMEOUT_SECONDS.
     */
    private int testTimeoutSeconds = 1800;

    /**
     * Max seconds for each runtime image build and {@code docker compose down/up} before the
     * process is killed and the run FAILS. Controlled by RT_EXECUTE_BUILD_TIMEOUT_SECONDS.
     */
    private int buildTimeoutSeconds = 1800;

    /**
     * When true the Execute pipeline runs in "ghost" mode: it creates the iteration branch and
     * then parks at {@code AWAITING_VERDICT}. The operator's verdict decides the CLAUDE.md
     * refactor-log {@code Result}, which is then committed. No Claude mutation, no test gate, no
     * image build, no redeploy — the running stack is left untouched.
     *
     * <p>For frontend work, where a full loop iteration is too slow to be a feedback cycle. Not a
     * runtime toggle on purpose: a half-real pipeline must be a deliberate boot-time decision.
     * Controlled by RT_EXECUTE_GHOST_MODE.
     */
    private boolean ghostMode = false;
}
