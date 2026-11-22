package org.anonymous.msrefactor.rt.analyze.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.analyze.ConstraintSnapshot;
import org.anonymous.msrefactor.rt.analyze.persistence.RefactorConstraintEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.RefactorConstraintRepository;
import org.anonymous.msrefactor.rt.analyze.web.dto.ConstraintContext;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;

import java.time.Instant;
import java.util.*;

/**
 * CRUD service for architect-defined refactoring constraints.
 *
 * <p>Matching logic: a constraint matches a candidate if each non-null field on the
 * constraint equals the corresponding field on the candidate (case-insensitive).
 * Null fields are wildcards — they match any value.
 *
 * <p>Rules are scoped to the application being refactored ({@code AppTargetConfig.getName()}).
 * A row with a null {@code projectId} is global and applies everywhere; the two sets are merged on
 * read, the same way {@code OperationQaScoringService} merges global defaults with project
 * overrides. With a single target application the two sets coincide — they diverge as soon as a
 * second application is analysed.
 */
@Service
@RequiredArgsConstructor
public class ConstraintService {

    private final RefactorConstraintRepository repository;
    private final AppTargetConfig appTarget;

    /** Every rule in scope for the current application: its own plus the global ones. */
    public List<RefactorConstraintEntity> findAll() {
        List<RefactorConstraintEntity> merged =
                new ArrayList<>(repository.findAllByProjectIdIsNull());
        merged.addAll(repository.findAllByProjectId(currentProjectId()));
        return merged;
    }

    /** The application these rules belong to — the discriminator {@code --project-path} selects. */
    public String currentProjectId() {
        return appTarget.getName();
    }

    /**
     * Frozen snapshot of every constraint in scope for the current application — safe to embed in
     * persisted results. Taken at analysis time into {@code AnalysisResult.activeConstraints} and
     * again at iteration end into {@code loop_iteration.constraints_json}, so both records stay
     * accurate after the live rules are edited.
     */
    public List<ConstraintSnapshot> getAllSnapshots() {
        return findAll().stream()
                .map(e -> ConstraintSnapshot.builder()
                        .id(e.getId())
                        .operation(e.getOperation())
                        .targetService(e.getTargetService())
                        .secondaryTarget(e.getSecondaryTarget())
                        .targetEntity(e.getTargetEntity())
                        .reason(e.getReason())
                        .blocking(e.isBlocking())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Persists a new rule. Defaults {@code projectId} to the current application when the caller
     * left it unset, so a rule created through the UI is scoped rather than silently global.
     */
    public RefactorConstraintEntity create(RefactorConstraintEntity constraint) {
        constraint.setId(UUID.randomUUID().toString());
        constraint.setCreatedAt(Instant.now());
        if (constraint.getProjectId() == null || constraint.getProjectId().isBlank()) {
            constraint.setProjectId(currentProjectId());
        }
        return repository.save(constraint);
    }

    /**
     * Creates a rule unless an equivalent one already exists for this project.
     *
     * <p>{@link #create} has no upsert — it mints a fresh id every call — so a pipeline that
     * re-runs the same failed operation would otherwise append a duplicate row on every iteration.
     * Equivalence is the operand tuple, not the reason text.
     *
     * @return the existing row when one matched, otherwise the newly created one
     */
    public RefactorConstraintEntity createIfAbsent(RefactorConstraintEntity constraint) {
        String projectId = (constraint.getProjectId() == null || constraint.getProjectId().isBlank())
                ? currentProjectId() : constraint.getProjectId();
        return findAll().stream()
                .filter(c -> Objects.equals(c.getProjectId(), projectId))
                .filter(c -> equalsIgnoreCaseOrBothNull(c.getOperation(), constraint.getOperation()))
                .filter(c -> equalsIgnoreCaseOrBothNull(c.getTargetService(), constraint.getTargetService()))
                .filter(c -> equalsIgnoreCaseOrBothNull(c.getSecondaryTarget(), constraint.getSecondaryTarget()))
                .filter(c -> equalsIgnoreCaseOrBothNull(c.getTargetEntity(), constraint.getTargetEntity()))
                .findFirst()
                .orElseGet(() -> create(constraint));
    }

    private boolean equalsIgnoreCaseOrBothNull(String a, String b) {
        if (a == null || a.isBlank()) return b == null || b.isBlank();
        return a.equalsIgnoreCase(b);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    /**
     * Returns all constraints whose fields match the given candidate context.
     * A constraint field that is null or blank acts as a wildcard.
     *
     * @param operation       candidate operation type name (e.g., "MERGE_AGGREGATES")
     * @param targetService   primary target service name
     * @param secondaryTarget secondary target service name
     * @param affectedEntities entity class names directly involved in the operation (may be null)
     */
    public List<RefactorConstraintEntity> findApplicableTo(
            String operation, String targetService, String secondaryTarget,
            List<String> affectedEntities) {

        return findAll().stream()
                .filter(c -> matches(c.getOperation(),       operation))
                .filter(c -> matches(c.getTargetService(),   targetService))
                .filter(c -> matches(c.getSecondaryTarget(), secondaryTarget))
                .filter(c -> matchesEntity(c.getTargetEntity(), affectedEntities))
                .toList();
    }

    /**
     * True when a <em>blocking</em> constraint forbids this exact operand tuple. Used by the
     * constraint-aware selection in the candidate generators/selectors to skip a forbidden
     * partner/source/owner/entity before it is committed (as opposed to demoting it at Stage 9).
     */
    public boolean isBlocked(String operation, String targetService, String secondaryTarget,
                             List<String> affectedEntities) {
        return findApplicableTo(operation, targetService, secondaryTarget, affectedEntities).stream()
                .anyMatch(RefactorConstraintEntity::isBlocking);
    }

    /**
     * The human-readable reasons of every <em>blocking</em> constraint matching this operand tuple.
     * Populated onto a blocked placeholder candidate when every option for a hotspot is forbidden.
     */
    public List<String> blockingReasons(String operation, String targetService, String secondaryTarget,
                                        List<String> affectedEntities) {
        return findApplicableTo(operation, targetService, secondaryTarget, affectedEntities).stream()
                .filter(RefactorConstraintEntity::isBlocking)
                .map(RefactorConstraintEntity::getReason)
                .toList();
    }

    /**
     * Builds a {@link ConstraintContext} from a persisted snapshot.
     * Extracts the {@code "current"} version (or the last available version) from
     * {@code snapshot.staticVersions} and maps each service's metrics to a
     * {@link ConstraintContext.ServiceContext}.
     *
     * <p>Returns an empty context (no services) when the snapshot has no static data.
     */
    public ConstraintContext buildContext(MonitorSnapshot snapshot) {
        if (snapshot == null || snapshot.getStaticVersions() == null
                || snapshot.getStaticVersions().isEmpty()) {
            return new ConstraintContext(List.of());
        }

        // Prefer "current" version; fall back to last entry
        StaticMetrics metrics = snapshot.getStaticVersions().stream()
                .filter(v -> "current".equals(v.getVersion()))
                .findFirst()
                .map(VersionAnalysisResult::getMetrics)
                .orElseGet(() -> {
                    List<VersionAnalysisResult> versions = snapshot.getStaticVersions();
                    return versions.get(versions.size() - 1).getMetrics();
                });

        if (metrics == null || metrics.getServices() == null) {
            return new ConstraintContext(List.of());
        }

        List<ConstraintContext.ServiceContext> services = metrics.getServices().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toServiceContext(entry.getKey(), entry.getValue()))
                .toList();

        return new ConstraintContext(services);
    }

    private ConstraintContext.ServiceContext toServiceContext(String name, ServiceStaticMetrics m) {
        List<String> entities = m.getEntityNames() != null
                ? new ArrayList<>(m.getEntityNames()) : List.of();
        Collections.sort(entities);

        Map<String, Integer> interRels = m.getExternalRelations() != null
                ? new TreeMap<>(m.getExternalRelations()) : Map.of();

        return new ConstraintContext.ServiceContext(
                name,
                entities,
                m.getInternalRelations(),
                interRels
        );
    }

    private boolean matches(String constraintField, String candidateValue) {
        if (constraintField == null || constraintField.isBlank()) return true;
        if (candidateValue == null) return false;
        return constraintField.equalsIgnoreCase(candidateValue);
    }

    private boolean matchesEntity(String constraintEntity, List<String> affectedEntities) {
        if (constraintEntity == null || constraintEntity.isBlank()) return true;
        if (affectedEntities == null || affectedEntities.isEmpty()) return false;
        return affectedEntities.stream().anyMatch(e -> constraintEntity.equalsIgnoreCase(e));
    }
}
