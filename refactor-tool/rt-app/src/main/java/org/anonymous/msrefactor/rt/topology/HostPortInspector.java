package org.anonymous.msrefactor.rt.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which host ports the application's deployment needs, and which of them something else holds.
 *
 * <p>A compose file can be perfectly valid and still not start: {@code docker compose up} binds every
 * published port on the host, and a container of <i>another</i> compose project on the same Docker
 * daemon may already hold one. Nothing before the deployment notices — the tests run in one process,
 * the image builds without ports. v6 of the evaluation lost a whole run that way: its split gave the
 * satellite 8090, the lowest port the quizzes compose file left free, and {@code rt-app} itself
 * publishes 8090.
 *
 * <p>Two uses. {@link #findConflicts} is a preflight the Execute phase runs before building anything;
 * {@link #foreignBindings} lists the ports to keep away from, which the mutator puts in the prompt so
 * the refactoring does not pick one in the first place.
 *
 * <p>"Foreign" means outside this application's compose project, whose name is read from
 * {@code docker compose config} rather than assumed. The project's own containers are not a conflict:
 * the redeploy takes them down before it brings the new topology up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HostPortInspector {

    /** A host port a running container publishes. */
    public record HostPortBinding(int port, String protocol, String holder, String project) {
        String label() {
            return port + ("tcp".equals(protocol) ? "" : "/" + protocol);
        }
    }

    /** A host port the deployment needs that it cannot bind. */
    public record PortConflict(String composeService, int port, String protocol,
                               String holder, String holderProject, boolean sameProject) {
        public String describe() {
            String what = "host port " + port + "/" + protocol + " of " + composeService;
            return sameProject
                    ? what + " is also published by " + holder + " in the same compose project"
                    : what + " is already published by container '" + holder + "' (compose project '"
                            + (holderProject.isEmpty() ? "none" : holderProject) + "')";
        }
    }

    /** The inspection itself could not be done — never a verdict on the deployment. */
    public static class InspectionException extends IllegalStateException {
        public InspectionException(String message) { super(message); }
    }

    static final String PS_FORMAT = "{{.Names}}\t{{.Label \"com.docker.compose.project\"}}\t{{.Ports}}";

    /** {@code 0.0.0.0:8090->8090/tcp}, {@code [::]:4317-4318->4317-4318/tcp}; host IP optional. */
    private static final Pattern PUBLISHED =
            Pattern.compile("^(?:.*:)?(\\d+)(?:-(\\d+))?->\\d+(?:-\\d+)?/(\\w+)$");

    /** A published range wider than this is not expanded — no compose file here publishes one. */
    private static final int MAX_RANGE = 1024;

    private final ProcessRunner processRunner;
    private final TopologyConfig config;
    private final ObjectMapper objectMapper;

    /** Every host port held by a running container outside the application's compose project. */
    public List<HostPortBinding> foreignBindings() {
        return foreign(projectName(composeConfig()));
    }

    /**
     * The host ports the deployment of {@code rootServices} cannot bind.
     *
     * <p>Only what {@code compose up} would actually start is checked — the roots and everything they
     * {@code depends_on}, transitively. The compose file also declares services that never run with
     * the microservices ({@code postgres}, the monoliths, which share 8080 with the gateway), and
     * counting them would report conflicts no deployment can hit.
     *
     * @throws InspectionException when either docker command fails or its output is unusable
     */
    public List<PortConflict> findConflicts(Collection<String> rootServices) {
        JsonNode root = composeConfig();
        String project = projectName(root);
        JsonNode services = root.path("services");

        Map<String, HostPortBinding> taken = new LinkedHashMap<>();
        for (HostPortBinding b : foreign(project)) taken.putIfAbsent(key(b.port(), b.protocol()), b);

        List<PortConflict> conflicts = new ArrayList<>();
        Map<String, String> claimedBy = new LinkedHashMap<>();
        for (String service : dependsOnClosure(services, rootServices)) {
            for (HostPortBinding wanted : publishedPorts(service, services.path(service))) {
                String key = key(wanted.port(), wanted.protocol());
                HostPortBinding holder = taken.get(key);
                if (holder != null) {
                    conflicts.add(new PortConflict(service, wanted.port(), wanted.protocol(),
                            holder.holder(), holder.project(), false));
                }
                String sibling = claimedBy.putIfAbsent(key, service);
                if (sibling != null && !sibling.equals(service)) {
                    conflicts.add(new PortConflict(service, wanted.port(), wanted.protocol(),
                            sibling, project, true));
                }
            }
        }
        return conflicts;
    }

    /**
     * The prompt section that tells a refactoring which host ports are not free, or {@code ""} when
     * there are none.
     */
    public static String promptBlock(List<HostPortBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) return "";
        Map<String, Set<String>> holders = new TreeMap<>((a, b) -> {
            int byPort = Integer.compare(Integer.parseInt(a.split("/")[0]), Integer.parseInt(b.split("/")[0]));
            return byPort != 0 ? byPort : a.compareTo(b);
        });
        for (HostPortBinding b : bindings) {
            holders.computeIfAbsent(b.label(), k -> new LinkedHashSet<>()).add(b.holder());
        }
        List<String> entries = new ArrayList<>();
        holders.forEach((port, names) -> entries.add(port + " (" + String.join(", ", names) + ")"));
        return "## Reserved host ports\n\n"
                + "Host ports already published on this Docker host by containers outside this "
                + "application's compose project. No compose file declares them, but they are not free — "
                + "never map any service's host port to one of them: "
                + String.join(", ", entries) + "\n";
    }

    // ─── Docker ──────────────────────────────────────────────────────────────────

    private JsonNode composeConfig() {
        try {
            return ComposeTopologyResolver.readComposeConfigRoot(processRunner, config, objectMapper);
        } catch (Exception e) {
            throw new InspectionException(e.getMessage());
        }
    }

    private static String projectName(JsonNode composeConfig) {
        String name = composeConfig.path("name").asText("");
        if (name.isBlank()) throw new InspectionException("docker compose config reported no project name");
        return name;
    }

    private List<HostPortBinding> foreign(String project) {
        ProcessResult ps;
        try {
            // run(), not runStreaming(): a docker warning on stderr must not become a container line.
            ps = processRunner.run(List.of("docker", "ps", "--format", PS_FORMAT), config.getProjectRoot());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InspectionException("interrupted listing containers");
        } catch (Exception e) {
            throw new InspectionException("docker ps failed: " + e.getMessage());
        }
        if (!ps.succeeded()) {
            throw new InspectionException("docker ps failed (exit=" + ps.exitCode() + "): " + ps.stderr());
        }
        return parseDockerPs(ps.stdout()).stream()
                .filter(b -> !project.equals(b.project()))
                .toList();
    }

    // ─── Parsing ─────────────────────────────────────────────────────────────────

    /**
     * One binding per published host port and protocol, per container. Exposed-only ports
     * ({@code 5775/udp}) are not bindings; the IPv4 and IPv6 entries of one port are one binding; and a
     * line with missing fields is read as empty fields — {@code ProcessRunner} trims the output, so the
     * last container's empty project and ports columns can disappear.
     */
    static List<HostPortBinding> parseDockerPs(String stdout) {
        Set<HostPortBinding> bindings = new LinkedHashSet<>();
        if (stdout == null) return List.of();
        for (String line : stdout.split("\\R")) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            String name = fields[0].trim();
            String project = fields.length > 1 ? fields[1].trim() : "";
            String ports = fields.length > 2 ? fields[2] : "";
            for (String entry : ports.split(",")) {
                Matcher m = PUBLISHED.matcher(entry.trim());
                if (!m.matches()) continue;
                for (int port : range(m.group(1), m.group(2))) {
                    bindings.add(new HostPortBinding(port, m.group(3), name, project));
                }
            }
        }
        return new ArrayList<>(bindings);
    }

    /** {@code depends_on} is an object keyed by service in {@code compose config}; a list is tolerated. */
    static Set<String> dependsOnClosure(JsonNode services, Collection<String> roots) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String service = todo.pop();
            if (!services.has(service) || !seen.add(service)) continue;
            JsonNode deps = services.path(service).path("depends_on");
            if (deps.isObject()) {
                for (Iterator<String> it = deps.fieldNames(); it.hasNext(); ) todo.add(it.next());
            } else if (deps.isArray()) {
                deps.forEach(d -> todo.add(d.asText()));
            }
        }
        return seen;
    }

    /** A service's host-side ports: {@code published} is a string, {@code "8090"} or {@code "4317-4318"}. */
    static List<HostPortBinding> publishedPorts(String service, JsonNode serviceConfig) {
        List<HostPortBinding> ports = new ArrayList<>();
        for (JsonNode p : serviceConfig.path("ports")) {
            String published = p.path("published").asText("");
            if (published.isBlank()) continue;
            String protocol = p.path("protocol").asText("tcp");
            String[] bounds = published.split("-", 2);
            try {
                for (int port : range(bounds[0], bounds.length > 1 ? bounds[1] : null)) {
                    ports.add(new HostPortBinding(port, protocol, service, ""));
                }
            } catch (NumberFormatException e) {
                log.debug("[ports] {} — unreadable published port '{}'", service, published);
            }
        }
        return ports;
    }

    private static List<Integer> range(String from, String to) {
        int first = Integer.parseInt(from.trim());
        int last = to == null ? first : Integer.parseInt(to.trim());
        if (last < first || last - first > MAX_RANGE) return List.of(first);
        List<Integer> ports = new ArrayList<>();
        for (int p = first; p <= last; p++) ports.add(p);
        return ports;
    }

    private static String key(int port, String protocol) {
        return port + "/" + protocol;
    }
}
