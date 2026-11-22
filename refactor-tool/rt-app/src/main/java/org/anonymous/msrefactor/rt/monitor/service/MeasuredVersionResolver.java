package org.anonymous.msrefactor.rt.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.ProjectStructureService;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployService;
import org.anonymous.msrefactor.rt.topology.DeployTopology;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what a Monitor run measures: the code version, the topology it was deployed with, and
 * the source tree its static metrics come from.
 *
 * <p>The measured version is the one the iteration is bound to — the version the operator picked
 * on the versions page before deploying it, possibly by hand with its deploy command. A manual
 * deploy never updates the ACTIVE record in the database, so that record cannot decide what is
 * running; it is only a fallback for runs without a bound version, and a mismatch is logged.
 *
 * <p>None of the inputs may come from the shared working tree or the topology cache. The repository
 * is bind-mounted into rt-app and moves between branches — the Execute phase checks out iteration
 * branches, and so does the operator on the host — so reading it measured whichever branch happened
 * to be checked out: after merging question into course, a snapshot of v1 still listed question.
 * The deployment record and the version's branch do not move with the checkout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeasuredVersionResolver {

    /** Label the static result is stored under; consumers and the frontend look for it. */
    static final String CURRENT = "current";

    private final CodeVersionService codeVersionService;
    private final LoopIterationRepository loopIterationRepository;
    private final ExecuteRefactorRepository executeRefactorRepository;
    private final ProjectStructureService projectStructureService;
    private final ComposeTopologyResolver topologyResolver;
    private final ExecuteConfig executeConfig;
    private final ObjectMapper objectMapper;

    /**
     * @param version        the version being measured; null only before the baseline exists
     * @param topology       services to collect for — the deployment's own record when there is one
     * @param topologySource "deployed v1", "compose", …, for the log
     * @param deployedAt     when the version went live; Jaeger spans before it belong to another version
     */
    public record Measured(CodeVersionEntity version, DeployTopology topology, String topologySource,
                           Instant deployedAt) {
        public String label() {
            return version == null ? "unknown" : version.getVersionLabel();
        }
    }

    /**
     * The iteration's bound version is what the operator chose to measure, and what they deployed —
     * through Execute or by hand with the version's deploy command. A mismatch with the database's
     * ACTIVE record is logged rather than overriding the choice: manual deploys never update that
     * record, so it only decides runs with no bound version.
     */
    public Measured resolve(String iterationId) {
        CodeVersionEntity active = codeVersionService.findActiveOrBaseline().orElse(null);
        CodeVersionEntity bound = iterationVersion(iterationId);

        if (bound != null && active != null && !Objects.equals(bound.getVersionId(), active.getVersionId())) {
            log.warn("[monitor] measuring {} (iteration {}) although the DB records {} as deployed — "
                            + "assuming a manual deploy of {}",
                    bound.getVersionLabel(), iterationId, active.getVersionLabel(), bound.getVersionLabel());
        }
        CodeVersionEntity measured = bound != null ? bound : active;

        Optional<DeployTopology> recorded = measured == null ? Optional.empty() : recordedTopology(measured);
        if (recorded.isPresent() && !recorded.get().isEmpty()) {
            return new Measured(measured, recorded.get(), "deployed " + measured.getVersionLabel(),
                    measured.getDeployedAt());
        }

        // The baseline was never deployed by Execute, and a manually deployed version may predate
        // its record: resolve fresh from the compose file (never the cache, which may predate a
        // checkout).
        DeployTopology fresh = topologyResolver.resolve(executeConfig.getDeployTarget(), true, List::of);
        return new Measured(measured, fresh, fresh.source().name().toLowerCase(),
                measured == null ? null : measured.getDeployedAt());
    }

    /** Static metrics of the measured version's own source tree, stored under {@link #CURRENT}. */
    public Optional<VersionAnalysisResult> staticVersion(Measured measured) {
        if (measured.version() == null) return Optional.empty();
        ProjectStructureService.Resolved resolved = projectStructureService.resolveSource(measured.version());
        if (resolved.result() == null || resolved.result().getMetrics() == null) return Optional.empty();
        log.info("[monitor] static metrics for {} from {}", measured.label(), resolved.description());
        return Optional.of(VersionAnalysisResult.builder()
                .version(CURRENT)
                .path(measured.label() + " (" + resolved.description() + ")")
                .metrics(resolved.result().getMetrics())
                .build());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CodeVersionEntity iterationVersion(String iterationId) {
        if (iterationId == null || iterationId.isBlank()) return null;
        return loopIterationRepository.findById(iterationId)
                .map(LoopIterationEntity::getCodeVersionId)
                .flatMap(codeVersionService::findById)
                .orElse(null);
    }

    /** The topology Execute recorded when it deployed this version — full record, else names only. */
    private Optional<DeployTopology> recordedTopology(CodeVersionEntity version) {
        String execId = version.getProducingExecutionId();
        if (execId == null || execId.isBlank()) return Optional.empty();
        Optional<ExecuteRefactorEntity> exec = executeRefactorRepository.findById(execId);
        if (exec.isEmpty()) return Optional.empty();

        String full = exec.get().getDeployedTopology();
        if (full != null && !full.isBlank()) {
            try {
                // The stored JSON also carries the record's derived getters (serviceNames, empty, …).
                return Optional.of(objectMapper.readerFor(DeployTopology.class)
                        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(full));
            } catch (Exception e) {
                log.warn("[monitor] could not read deployed topology of {}: {} — using service names",
                        version.getVersionLabel(), e.getMessage());
            }
        }
        String names = exec.get().getDeployedServices();
        if (names == null || names.isBlank()) return Optional.empty();
        try {
            List<String> services = objectMapper.readValue(names, new TypeReference<List<String>>() {});
            // Same shape ComposeTopologyResolver builds when only names are known.
            List<DeployService> bare = services.stream()
                    .map(n -> new DeployService(n, n, null, n.replaceAll("-service$", ""), List.of(), true))
                    .toList();
            return Optional.of(new DeployTopology(null, null, bare, Instant.now(), DeployTopology.Source.FALLBACK));
        } catch (Exception e) {
            log.warn("[monitor] could not read deployed services of {}: {}", version.getVersionLabel(), e.getMessage());
            return Optional.empty();
        }
    }
}
