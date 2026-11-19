package org.anonymous.msrefactor.rt.topology;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;

/**
 * Settings for deriving the deployable service set from the application's docker-compose file.
 *
 * <p>Deliberately carries its own {@code composeFile}/{@code projectRoot} instead of injecting
 * {@code ExecuteConfig}: the Monitor phase also consumes the resolver, and {@code rt.monitor}
 * must not depend on {@code rt.execute}.
 */
@Component
@ConfigurationProperties(prefix = "rt.topology")
@Data
public class TopologyConfig {

    /** Path to the application docker-compose.yml. Mirrors {@code rt.execute.compose-file}. */
    private String composeFile = "../" + AppTargetConstants.COMPOSE_FILE;

    /** Working directory for the docker CLI. Mirrors {@code rt.execute.project-root}. */
    private String projectRoot = "../";

    /**
     * Image tag the Execute phase rebuilds. A compose service carrying this image AND a
     * {@code START_CLASS} environment variable is a deployable microservice; the tag alone is not
     * enough, because the {@code quizzes-local}/{@code quizzes-remote} monoliths share it.
     */
    private String runtimeImage = AppTargetConstants.IMAGE_LATEST;

    /**
     * Services prepended to every {@code docker compose up}, regardless of discovery. The gateway
     * runs the simulator image so it is never discovered, but the stack is unusable without it.
     */
    private String alwaysUp = "gateway";

    /** Escape hatch: discovered services to drop from the deploy set. Normally empty. */
    private String exclude = "";

    /** Repo-relative directory holding {@code application-<profile>.yaml} files. */
    private String appResourcesPath = AppTargetConstants.APP_RESOURCES_PATH;

    /**
     * Last-resort service list, used only when compose discovery fails and no previously resolved
     * topology is available. Kept as the pre-refactoring set so a total failure degrades to
     * today's behaviour rather than to an empty deploy.
     */
    private String fallbackServices =
            "answer-service,course-service,execution-service,question-service,"
            + "quiz-service,topic-service,tournament-service,user-service";

    public List<String> getAlwaysUpList() {
        return splitCsv(alwaysUp);
    }

    public List<String> getExcludeList() {
        return splitCsv(exclude);
    }

    public List<String> getFallbackServicesList() {
        return splitCsv(fallbackServices);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
