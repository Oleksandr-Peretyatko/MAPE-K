package org.anonymous.msrefactor.rt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;

import java.nio.file.Paths;

/**
 * Runtime view of {@link AppTargetConstants} — which application the pipeline refactors.
 *
 * <p>Defaults come from the constants; override via {@code RT_APP_TARGET_NAME},
 * {@code RT_APP_TARGET_PATH}, {@code RT_APP_TARGET_IMAGE_REPO}, {@code RT_APP_TARGET_GATEWAY_IMAGE}.
 *
 * <p>The {@code absolute*} helpers take the repository root — {@code rt.execute.project-root} —
 * rather than storing it, so there is one owner of that path.
 */
@Component
@ConfigurationProperties(prefix = "rt.app-target")
@Data
public class AppTargetConfig {

    private String name = AppTargetConstants.NAME;
    private String path = AppTargetConstants.PATH;
    private String imageRepo = AppTargetConstants.IMAGE_REPO;
    private String gatewayImage = AppTargetConstants.GATEWAY_IMAGE;

    // ─── Relative paths ───────────────────────────────────────────────────────

    /** Git pathspec for staging/cleaning the application module — trailing slash included. */
    public String gitPathspec() {
        return path + "/";
    }

    /**
     * Exclusion pathspec for the skill's own log file.
     *
     * <p>Combined with {@link #gitPathspec()} it answers "did the run change anything but its
     * {@code CLAUDE.md} entry?" — the one file a deliberately-no-op run is expected to write.
     */
    public String claudeMdExcludePathspec() {
        return ":(exclude)" + path + "/CLAUDE.md";
    }

    // ─── Skill invocation ─────────────────────────────────────────────────────

    /**
     * Appends {@code --project-path <path>} to a skill invocation so the skill operates on this
     * target rather than the module it used to hardcode.
     *
     * <p>Idempotent: an invocation that already carries the flag — a user edit, or a refined
     * invocation returned by the plan enricher — comes back unchanged. The flag goes last so a
     * preceding multi-value flag ({@code --entities}, {@code --into}) cannot swallow it.
     */
    public String withProjectPath(String skillInvocation) {
        if (skillInvocation == null || skillInvocation.isBlank()) return skillInvocation;
        if (skillInvocation.contains(AppTargetConstants.PROJECT_PATH_FLAG)) return skillInvocation;
        return skillInvocation + " " + AppTargetConstants.PROJECT_PATH_FLAG + " " + path;
    }

    // ─── Absolute paths (given the repository root) ───────────────────────────

    public String absoluteDir(String projectRoot) {
        return Paths.get(projectRoot, path).toString();
    }

    public String absoluteDockerfile(String projectRoot) {
        return Paths.get(projectRoot, path, "Dockerfile").toString();
    }

    public String absoluteClaudeMd(String projectRoot) {
        return Paths.get(projectRoot, path, "CLAUDE.md").toString();
    }

    // ─── Image tags ───────────────────────────────────────────────────────────

    public String latestTag() {
        return imageRepo + ":latest";
    }

    public String versionTag(int versionNumber) {
        return imageRepo + ":v" + versionNumber;
    }

    /** Slashes are illegal in Docker tags, so branch names get theirs replaced with dashes. */
    public String branchTag(String branchName) {
        return imageRepo + ":" + branchName.replace('/', '-');
    }
}
