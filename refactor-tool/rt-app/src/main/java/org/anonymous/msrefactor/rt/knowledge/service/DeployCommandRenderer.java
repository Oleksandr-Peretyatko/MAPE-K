package org.anonymous.msrefactor.rt.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.topology.TopologyConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders the shell commands that deploy one code version by hand — the same checkout, image
 * build, teardown and {@code compose up} the Execute phase runs, written for a terminal at the
 * repository root. Stored on {@code code_version.deploy_command} so the Versions page can show it.
 *
 * <p>Paths are repo-relative on purpose: {@code rt.execute.compose-file} is the container path
 * ({@code /app/project/...}), which means nothing on the host where the operator pastes this.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeployCommandRenderer {

    /**
     * Interpolated by the compose file. An empty VERSION_MODE leaves SPRING_PROFILES_INCLUDE blank
     * and the services go looking for a version-service nobody started, so the values rt-app
     * deploys with are written into the command rather than left to the operator's shell.
     */
    static final List<String> DEPLOY_ENV = List.of("VERSION_MODE", "COMM_LAYER");

    private final AppTargetConfig appTarget;
    private final TopologyConfig topologyConfig;
    private final ExecuteConfig executeConfig;
    private final ProcessRunner processRunner;

    /** Source of {@link #DEPLOY_ENV} values; replaced in tests. */
    private Function<String, String> env = System::getenv;

    void setEnv(Function<String, String> env) {
        this.env = env;
    }

    /**
     * The service arguments of {@code docker compose up}: the always-up services (the gateway runs
     * the simulator image, so discovery never finds it) followed by the discovered ones. Shared with
     * {@code ExecuteService.buildComposeUp} so the command shown and the command run cannot drift.
     */
    public static List<String> upServices(List<String> alwaysUp, List<String> services) {
        List<String> args = new ArrayList<>();
        for (String extra : alwaysUp) {
            if (!services.contains(extra)) args.add(extra);
        }
        args.addAll(services);
        return args;
    }

    /**
     * @param gitRef        branch (preferred) or commit to check out
     * @param versionNumber 0 for the baseline, which is tagged {@code :baseline} instead of {@code :v0}
     * @param services      the version's discovered microservices, without the always-up ones
     */
    public String render(String gitRef, int versionNumber, List<String> services) {
        String compose = "docker compose -f " + appTarget.getPath() + "/docker-compose.yml";
        String versionTag = versionNumber == 0
                ? appTarget.getImageRepo() + ":baseline"
                : appTarget.versionTag(versionNumber);
        List<String> alwaysUp = topologyConfig.getAlwaysUpList();

        List<String> lines = new ArrayList<>();
        lines.add("git checkout " + gitRef);

        List<String> assignments = new ArrayList<>();
        for (String name : DEPLOY_ENV) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) assignments.add(name + "=" + value);
        }
        if (!assignments.isEmpty()) lines.add("export " + String.join(" ", assignments));

        // `up --no-build` below never builds, and the always-up services run their own image.
        if (!alwaysUp.isEmpty()) lines.add(compose + " build " + String.join(" ", alwaysUp));
        lines.add("docker build -f " + appTarget.getPath() + "/Dockerfile"
                + " --build-arg PROFILE=" + executeConfig.getBuildProfiles()
                + " -t " + versionTag + " -t " + appTarget.latestTag() + " .");
        // Same teardown as ExecuteService.redeploy: --remove-orphans drops the containers of
        // services a merge dissolved, which the next version's compose file no longer names.
        lines.add(compose + " down --remove-orphans");
        lines.add(compose + " up -d --no-build " + String.join(" ", upServices(alwaysUp, services)));
        return String.join("\n", lines);
    }

    /**
     * The microservices the base branch's compose file defines, read with {@code git show} so the
     * answer does not depend on which branch the working tree happens to be on. Same selection rule
     * as {@code ComposeTopologyResolver}: the runtime image plus a {@code START_CLASS}. Falls back to
     * {@code rt.topology.fallback-services}, which is the pre-refactoring set.
     */
    public List<String> baselineServices(String baseBranch) {
        String composePath = appTarget.getPath() + "/docker-compose.yml";
        String root = executeConfig.getProjectRoot();
        try {
            ProcessResult result = processRunner.run(
                    List.of("git", "-C", root, "show", baseBranch + ":" + composePath), root);
            if (!result.succeeded()) {
                throw new IllegalStateException("git show failed (exit=" + result.exitCode() + "): "
                        + result.stderr());
            }
            List<String> services = selectDeployTargets(result.stdout());
            if (services.isEmpty()) throw new IllegalStateException("no deployable services found");
            return services;
        } catch (Exception e) {
            log.warn("[version] could not read {} on {} ({}) — using rt.topology.fallback-services",
                    composePath, baseBranch, e.getMessage());
            return topologyConfig.getFallbackServicesList();
        }
    }

    @SuppressWarnings("unchecked")
    List<String> selectDeployTargets(String composeYaml) {
        // The compose file merges its shared blocks with `<<: *anchor`; SnakeYAML's default cap of
        // 50 aliases is below what eight microservices plus their databases use.
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(1000);
        Object root = new Yaml(options).load(composeYaml);
        if (!(root instanceof Map)) return List.of();
        Object services = ((Map<String, Object>) root).get("services");
        if (!(services instanceof Map)) return List.of();

        List<String> excluded = topologyConfig.getExcludeList();
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) services).entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            if (topologyConfig.getRuntimeImage().equals(svc.get("image"))
                    && hasStartClass(svc.get("environment"))
                    && !excluded.contains(entry.getKey())) {
                selected.add(entry.getKey());
            }
        }
        selected.sort(String::compareTo);
        return selected;
    }

    /** {@code environment} is either a map or a list of {@code KEY=value} strings. */
    private boolean hasStartClass(Object environment) {
        if (environment instanceof Map<?, ?> map) return map.get("START_CLASS") != null;
        if (environment instanceof List<?> list) {
            return list.stream().anyMatch(e -> String.valueOf(e).startsWith("START_CLASS="));
        }
        return false;
    }
}
