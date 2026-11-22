package org.anonymous.msrefactor.rt.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Derives the deployable service set from {@code docker compose config}, so the compose file is
 * the single source of truth for deployment topology.
 *
 * <p>A refactoring that renames, removes or adds a microservice edits the compose file; re-running
 * discovery after the mutation commit therefore yields the new topology without any configuration
 * change. This is what lets one mechanism serve merge (8 -> 7), split (4 -> 5), create and remove.
 *
 * <p>Nothing is resolved at bean startup: at that point the working tree sits on an arbitrary
 * branch. Resolution is explicit ({@code force=true} from the Execute phase, right after the
 * mutation is committed) or lazy-cached ({@link #current()} for Monitor and the config API).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComposeTopologyResolver {

    /** {@code rt.execute.deploy-target} value that selects compose discovery. */
    public static final String AUTO = "auto";

    /**
     * An explicit {@code deploy-target} named a service the compose file does not define. This is
     * a configuration error, not a transient discovery failure, so it is never absorbed by the
     * fallback ladder — re-adding the bogus name would just defer the failure to the health wait.
     */
    public static class UnknownComposeServiceException extends IllegalStateException {
        public UnknownComposeServiceException(String message) {
            super(message);
        }
    }

    private final ProcessRunner processRunner;
    private final TopologyConfig config;
    private final ObjectMapper objectMapper;

    private final AtomicReference<DeployTopology> cache = new AtomicReference<>();

    /** Drops the cached topology. Call whenever the working tree changes (checkout, rollback). */
    public void invalidate() {
        DeployTopology previous = cache.getAndSet(null);
        if (previous != null) {
            log.info("[topology] cache invalidated (was {} service(s) from {})",
                    previous.services().size(), previous.source());
        }
    }

    /**
     * Cached topology for read-only consumers (Monitor, config API). Resolves lazily on first use
     * and never throws — callers get the fallback list rather than an error.
     */
    public DeployTopology current() {
        DeployTopology cached = cache.get();
        if (cached != null) return cached;
        return resolve(AUTO, false, List::of);
    }

    /**
     * Resolves the deploy topology.
     *
     * @param deployTargetSpec  {@link #AUTO} to discover, otherwise a comma-separated literal list
     *                          of compose services (still enriched from the compose config, so an
     *                          unknown name fails fast instead of timing out in the health wait)
     * @param force             true to bypass the cache
     * @param persistedFallback supplies the previously deployed service names, consulted only when
     *                          discovery fails
     */
    public DeployTopology resolve(String deployTargetSpec, boolean force,
                                  Supplier<List<String>> persistedFallback) {
        if (!force) {
            DeployTopology cached = cache.get();
            if (cached != null) return cached;
        }

        String spec = (deployTargetSpec == null || deployTargetSpec.isBlank())
                ? AUTO : deployTargetSpec.trim();
        boolean auto = AUTO.equalsIgnoreCase(spec);

        try {
            JsonNode services = readComposeConfig();
            DeployTopology resolved = auto
                    ? discover(services)
                    : enrichExplicit(TopologyConfig.splitCsv(spec), services);
            cache.set(resolved);
            log.info("[topology] resolved {} service(s) from {} [{}]: {}",
                    resolved.services().size(), config.getComposeFile(), resolved.source(),
                    resolved.serviceNames());
            return resolved;
        } catch (UnknownComposeServiceException e) {
            throw e;
        } catch (Exception e) {
            // Returned but never cached: a transient `docker compose config` failure would otherwise
            // pin a guessed service list (the configured fallback still names services a merge has
            // since dissolved) for every read-only consumer until the next Execute invalidates it.
            return fallback(spec, auto, persistedFallback, e);
        }
    }

    // ─── Discovery ───────────────────────────────────────────────────────────

    private JsonNode readComposeConfig() throws Exception {
        JsonNode services = readComposeConfigRoot(processRunner, config, objectMapper).path("services");
        if (!services.isObject() || services.isEmpty()) {
            throw new IllegalStateException("docker compose config returned no services");
        }
        return services;
    }

    /** The whole {@code docker compose config} document — project {@code name} included. */
    static JsonNode readComposeConfigRoot(ProcessRunner processRunner, TopologyConfig config,
                                          ObjectMapper objectMapper) throws Exception {
        List<String> cmd = List.of("docker", "compose",
                "-f", config.getComposeFile(),
                "config", "--format", "json");
        // No extraEnv: the command must inherit rt-app's VERSION_MODE/COMM_LAYER so it
        // interpolates exactly as the subsequent `docker compose up` will.
        ProcessResult result = processRunner.run(cmd, config.getProjectRoot());
        if (!result.succeeded()) {
            throw new IllegalStateException("docker compose config failed (exit="
                    + result.exitCode() + "): " + result.stderr());
        }
        return objectMapper.readTree(result.stdout());
    }

    private DeployTopology discover(JsonNode services) {
        Map<String, String> profileNameCache = new HashMap<>();
        List<String> excluded = config.getExcludeList();
        List<DeployService> selected = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> it = services.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (isDeployTarget(entry.getKey(), entry.getValue(), excluded)) {
                selected.add(toDeployService(entry.getKey(), entry.getValue(), profileNameCache));
            }
        }
        selected.sort(java.util.Comparator.comparing(DeployService::composeService));
        return topology(selected, DeployTopology.Source.COMPOSE);
    }

    /**
     * A compose service is deployable when it carries the rebuilt runtime image <em>and</em> a
     * {@code START_CLASS}. The image alone is not enough: {@code quizzes-local} and
     * {@code quizzes-remote} share the tag but are monolith deployments, and they are the whole
     * reason a plain image filter returns 10 services instead of 8.
     */
    private boolean isDeployTarget(String name, JsonNode svc, List<String> excluded) {
        return config.getRuntimeImage().equals(svc.path("image").asText(null))
                && svc.path("environment").hasNonNull("START_CLASS")
                && !excluded.contains(name);
    }

    private DeployTopology enrichExplicit(List<String> names, JsonNode services) {
        Map<String, String> profileNameCache = new HashMap<>();
        List<DeployService> selected = new ArrayList<>();
        for (String name : names) {
            JsonNode svc = services.path(name);
            if (svc.isMissingNode() || svc.isNull()) {
                // Fail loudly here rather than let `docker compose ps` report the name to stderr
                // and the health wait spin for its full timeout on an "unknown" status.
                throw new UnknownComposeServiceException(
                        "deploy-target names a service absent from " + config.getComposeFile() + ": " + name);
            }
            selected.add(toDeployService(name, svc, profileNameCache));
        }
        return topology(selected, DeployTopology.Source.EXPLICIT);
    }

    private DeployService toDeployService(String name, JsonNode svc, Map<String, String> profileNameCache) {
        String containerName = svc.path("container_name").asText(null);
        String springProfile = springProfile(svc);
        String jaegerName = profileNameCache.computeIfAbsent(
                springProfile == null ? "" : springProfile,
                p -> jaegerServiceName(p, name));

        List<Integer> ports = new ArrayList<>();
        for (JsonNode port : svc.path("ports")) {
            if (port.hasNonNull("target")) ports.add(port.get("target").asInt());
        }

        return new DeployService(
                name,
                containerName == null || containerName.isBlank() ? name : containerName,
                springProfile,
                jaegerName,
                List.copyOf(ports),
                // The key is present but null for services without a healthcheck block.
                svc.hasNonNull("healthcheck"));
    }

    /** First {@code *-service} token of SPRING_PROFILES_ACTIVE, else the first token. */
    private String springProfile(JsonNode svc) {
        String active = svc.path("environment").path("SPRING_PROFILES_ACTIVE").asText("");
        if (active.isBlank()) return null;
        List<String> tokens = TopologyConfig.splitCsv(active);
        return tokens.stream()
                .filter(t -> t.endsWith("-service"))
                .findFirst()
                .orElse(tokens.isEmpty() ? null : tokens.get(0));
    }

    /**
     * The name this process reports to Jaeger, which is its {@code spring.application.name} — e.g.
     * {@code tournament}, not {@code tournament-service}. Read from the profile's resource file
     * rather than derived by stripping the suffix, because the two diverge: {@code quizzes-local}
     * reports {@code quizzes}, and a renamed service is only correct in the file.
     */
    private String jaegerServiceName(String springProfile, String composeService) {
        Path resources = Paths.get(config.getProjectRoot(), config.getAppResourcesPath());

        if (springProfile != null && !springProfile.isBlank()) {
            String fromProfile = readApplicationName(resources.resolve("application-" + springProfile + ".yaml"));
            if (fromProfile != null) return fromProfile;
        }
        String fromBase = readApplicationName(resources.resolve("application.yaml"));
        if (fromBase != null) return fromBase;

        log.warn("[topology] no spring.application.name for '{}' (profile={}) — falling back to suffix strip",
                composeService, springProfile);
        return composeService.replaceAll("-service$", "");
    }

    @SuppressWarnings("unchecked")
    private String readApplicationName(Path yamlFile) {
        if (!Files.isReadable(yamlFile)) return null;
        try (InputStream in = Files.newInputStream(yamlFile)) {
            // application.yaml is a multi-document file (profile-specific sections); take the
            // first document that actually declares the name.
            for (Object document : new Yaml().loadAll(in)) {
                if (!(document instanceof Map)) continue;
                Object spring = ((Map<String, Object>) document).get("spring");
                if (!(spring instanceof Map)) continue;
                Object application = ((Map<String, Object>) spring).get("application");
                if (!(application instanceof Map)) continue;
                Object name = ((Map<String, Object>) application).get("name");
                if (name != null && !name.toString().isBlank()) return name.toString();
            }
        } catch (Exception e) {
            log.warn("[topology] could not read {}: {}", yamlFile, e.getMessage());
        }
        return null;
    }

    // ─── Fallback ────────────────────────────────────────────────────────────

    /**
     * Ordered degradation when discovery fails: last resolved topology, then the previously
     * deployed list, then an explicit spec, then the configured fallback. Never returns empty —
     * the caller's non-empty guard is the last line of defence, and an empty list passed to
     * {@code docker compose up} would start every service in the project including the test
     * containers.
     */
    private DeployTopology fallback(String spec, boolean auto,
                                    Supplier<List<String>> persistedFallback, Exception cause) {
        DeployTopology cached = cache.get();
        if (cached != null && !cached.isEmpty()) {
            log.warn("[topology] discovery failed ({}) — reusing last resolved topology: {}",
                    cause.getMessage(), cached.serviceNames());
            return cached;
        }

        List<String> persisted = safeGet(persistedFallback);
        if (!persisted.isEmpty()) {
            log.warn("[topology] discovery failed ({}) — using last deployed services: {}",
                    cause.getMessage(), persisted);
            return bare(persisted, DeployTopology.Source.FALLBACK);
        }

        if (!auto) {
            List<String> explicit = TopologyConfig.splitCsv(spec);
            if (!explicit.isEmpty()) {
                log.warn("[topology] discovery failed ({}) — using literal deploy-target: {}",
                        cause.getMessage(), explicit);
                return bare(explicit, DeployTopology.Source.FALLBACK);
            }
        }

        List<String> configured = config.getFallbackServicesList();
        log.error("[topology] discovery failed ({}) and no prior topology is known — "
                        + "falling back to rt.topology.fallback-services: {}",
                cause.getMessage(), configured);
        return bare(configured, DeployTopology.Source.FALLBACK);
    }

    private List<String> safeGet(Supplier<List<String>> supplier) {
        try {
            List<String> value = supplier == null ? null : supplier.get();
            return value == null ? List.of() : value;
        } catch (Exception e) {
            log.warn("[topology] persisted-topology fallback failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** A topology known only by name — no compose metadata available. */
    private DeployTopology bare(List<String> names, DeployTopology.Source source) {
        List<DeployService> services = names.stream()
                .map(n -> new DeployService(n, n, null, n.replaceAll("-service$", ""), List.of(), true))
                .toList();
        return topology(services, source);
    }

    private DeployTopology topology(List<DeployService> services, DeployTopology.Source source) {
        return new DeployTopology(config.getComposeFile(), config.getRuntimeImage(),
                List.copyOf(services), Instant.now(), source);
    }
}
