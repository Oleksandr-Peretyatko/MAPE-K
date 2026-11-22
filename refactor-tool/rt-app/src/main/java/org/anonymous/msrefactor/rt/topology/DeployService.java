package org.anonymous.msrefactor.rt.topology;

import java.util.List;

/**
 * A single deployable compose service, as read from {@code docker compose config}.
 *
 * @param composeService    compose service key, e.g. {@code tournament-service}
 * @param containerName     pinned {@code container_name}, or the compose key when unpinned
 * @param springProfile     first token of {@code SPRING_PROFILES_ACTIVE}, e.g. {@code tournament-service}
 * @param jaegerServiceName OTel {@code service.name} this process reports, i.e. its
 *                          {@code spring.application.name} — {@code tournament}, not
 *                          {@code tournament-service}. Monitor queries Jaeger by this name.
 * @param ports             container-side published ports
 * @param hasHealthcheck    false for services with no {@code healthcheck} block; those can never
 *                          report {@code healthy}, so the deploy wait accepts {@code running}
 */
public record DeployService(
        String composeService,
        String containerName,
        String springProfile,
        String jaegerServiceName,
        List<Integer> ports,
        boolean hasHealthcheck
) {
}
