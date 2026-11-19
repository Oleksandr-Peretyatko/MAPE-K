package org.anonymous.msrefactor.rt.topology;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The set of services the Execute phase deploys, resolved from the compose file on disk.
 *
 * <p>Because it is re-resolved after each mutation commit, a refactoring that adds, removes or
 * renames a compose service is picked up with no configuration change.
 *
 * @param source how the list was obtained — see {@link Source}
 */
public record DeployTopology(
        String composeFile,
        String runtimeImage,
        List<DeployService> services,
        Instant resolvedAt,
        Source source
) {
    public enum Source {
        /** Discovered from {@code docker compose config}. */
        COMPOSE,
        /** An explicit {@code rt.execute.deploy-target} list, enriched from the compose config. */
        EXPLICIT,
        /** Discovery failed; a previously persisted or configured list was used. */
        FALLBACK
    }

    /** Compose service keys, for {@code docker compose up} and {@code ps}. */
    public List<String> serviceNames() {
        return services.stream().map(DeployService::composeService).toList();
    }

    /** OTel {@code service.name} values, for Jaeger queries. */
    public List<String> jaegerServiceNames() {
        return services.stream()
                .map(DeployService::jaegerServiceName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return services.isEmpty();
    }
}
