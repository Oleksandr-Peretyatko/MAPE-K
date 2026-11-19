package org.anonymous.msrefactor.rt.constants;

/**
 * Identity of the application the tool refactors.
 *
 * <p>Everything else — compose file, Dockerfile, resources path, git pathspec, image tags — is
 * <b>derived</b> from these four values rather than written out again, so pointing the pipeline at
 * a different application is a matter of changing them (or the matching
 * {@code RT_APP_TARGET_*} environment variables) instead of hunting literals through 15 files.
 *
 * <p>Paths here are relative to {@code rt.execute.project-root}; the absolute forms live on
 * {@code AppTargetConfig}.
 */
public final class AppTargetConstants {

    /** Short name of the target application; also its OTel/monolith service name. */
    public static final String NAME = "quizzes";

    /** Path to the application module, relative to the repository root. */
    public static final String PATH = "applications/quizzes";

    /** Docker image repository for the built runtime — tagged :latest, :v4, :branch-name. */
    public static final String IMAGE_REPO = "quizzes";

    /**
     * Image the API gateway runs. It is the simulator image, not the application one, which is
     * why topology discovery never finds the gateway and it has to be added explicitly.
     */
    public static final String GATEWAY_IMAGE = "simulator:latest";

    // ─── Derived paths (relative to project root) ─────────────────────────────

    public static final String COMPOSE_FILE = PATH + "/docker-compose.yml";
    public static final String APP_RESOURCES_PATH = PATH + "/src/main/resources";
    public static final String SOURCE_TREE_PATH = PATH + "/src/main/java";

    /**
     * Java package root of the application, as a path. Independent of {@link #PATH}: the module
     * can move without the package changing, and vice versa.
     */
    public static final String JAVA_PACKAGE_PATH =
            "org/anonymous/msrefactor/" + NAME;

    /** Where the per-aggregate microservice packages live inside the source tree. */
    public static final String MICROSERVICES_PACKAGE_PATH =
            JAVA_PACKAGE_PATH + "/microservices";

    /**
     * The application's domain events, a sibling of the microservice packages rather than a child:
     * an event is published by one service and consumed by others, so it belongs to none of them.
     */
    public static final String EVENTS_PACKAGE_PATH =
            JAVA_PACKAGE_PATH + "/events";

    // ─── Skill invocation ─────────────────────────────────────────────────────

    /**
     * Flag every {@code refactor-*} skill receives so it operates on the configured module instead
     * of the literal {@code applications/quizzes} it used to hardcode. Its value is {@link #PATH}
     * (or the {@code RT_APP_TARGET_PATH} override) — relative to the repository root, which is what
     * the skills' git pathspecs and cross-module references need.
     */
    public static final String PROJECT_PATH_FLAG = "--project-path";

    // ─── Image tags ───────────────────────────────────────────────────────────

    public static final String IMAGE_LATEST = IMAGE_REPO + ":latest";

    private AppTargetConstants() {}
}
