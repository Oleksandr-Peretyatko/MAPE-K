package org.anonymous.msrefactor.rt.knowledge.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.anonymous.msrefactor.rt.analyze.service.ConstraintService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.analyze.AnalysisResult;
import org.anonymous.msrefactor.rt.analyze.service.AnalyzeService;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.dto.ExecuteResponse;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.persistence.MonitorSnapshotRepository;
import org.anonymous.msrefactor.rt.monitor.service.MonitorService;
import org.anonymous.msrefactor.rt.plan.dto.PlanSummary;
import org.anonymous.msrefactor.rt.plan.persistence.PlanDocumentRepository;
import org.anonymous.msrefactor.rt.plan.service.PlanService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service("loopIterationService")
@RequiredArgsConstructor
@Slf4j
public class LoopIterationService {

    public static final String CREATED   = "CREATED";
    public static final String MONITORING = "MONITORING";
    public static final String ANALYZED  = "ANALYZED";
    public static final String PLANNED   = "PLANNED";
    public static final String EXECUTING = "EXECUTING";
    public static final String DEPLOYED  = "DEPLOYED";
    public static final String FAILED    = "FAILED";

    /**
     * Branch lifecycle, tracked separately from the phase {@link #CREATED}…{@link #FAILED} ladder:
     * that one records how far the loop got, this one records what happened to the branch.
     */
    public static final String BRANCH_CREATED            = "CREATED";
    public static final String BRANCH_EXECUTING_REFACTOR = "EXECUTING_REFACTOR";
    public static final String BRANCH_BUILD_SUCCESS      = "BUILD_SUCCESS";
    public static final String BRANCH_BUILD_FAILED       = "BUILD_FAILED";

    private final LoopIterationRepository     loopIterationRepository;
    private final MonitorSnapshotRepository   snapshotRepository;
    private final PlanDocumentRepository      planDocumentRepository;
    private final ExecuteRefactorRepository   executeRefactorRepository;
    private final MonitorService              monitorService;
    private final AnalyzeService              analyzeService;
    private final PlanService                 planService;
    private final CodeVersionService          codeVersionService;
    private final ConstraintService           constraintService;
    private final ObjectMapper                objectMapper;

    /**
     * Fallback parent branch for iterations whose code version has none (the baseline).
     *
     * <p>Read from {@link ExecuteConfig} rather than re-declared with its own {@code @Value}
     * default: one owner, one default, one {@code RT_EXECUTE_BASE_BRANCH} override.
     */
    private final ExecuteConfig executeConfig;

    // ─── DTOs ────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LoopIterationSummary {
        private String iterationId;
        private int    iterationNumber;
        private String name;
        private String status;
        private String snapshotId;
        private String analysisId;
        private String planId;
        private String executeRefactorId;
        private String codeVersionId;
        private String codeVersionLabel;
        private String branchName;
        private String parentBranchName;
        private String branchStatus;
        private String startedAt;
        private String completedAt;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LoopIterationDetail {
        private String          iterationId;
        private int             iterationNumber;
        private String          name;
        private String          status;
        private String          startedAt;
        private String          completedAt;
        private String          notes;
        private MonitorSnapshot snapshot;
        private AnalysisResult  analysis;
        private PlanSummary     plan;
        private ExecuteResponse execution;
        private String snapshotId;
        private String analysisId;
        private String planId;
        private String executeRefactorId;
        private String codeVersionId;
        private String codeVersionLabel;
        private String branchName;
        private String parentBranchName;
        private String branchStatus;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public LoopIterationSummary create(String name, String codeVersionId) {
        int num = loopIterationRepository.findMaxIterationNumber() + 1;
        String label = (name != null && !name.isBlank()) ? name : "Iteration " + num;
        LoopIterationEntity entity = LoopIterationEntity.builder()
                .iterationId(UUID.randomUUID().toString())
                .iterationNumber(num)
                .name(label)
                .status(CREATED)
                .codeVersionId(codeVersionId)
                .startedAt(Instant.now())
                .build();
        loopIterationRepository.save(entity);
        log.info("[loop] Created iteration #{} '{}' codeVersionId={}", num, label, codeVersionId);
        return toSummary(entity);
    }

    public List<LoopIterationSummary> listAll() {
        return loopIterationRepository.findAllByOrderByIterationNumberAsc()
                .stream().map(this::toSummary).collect(Collectors.toList());
    }

    public Optional<LoopIterationDetail> getDetail(String iterationId) {
        return loopIterationRepository.findById(iterationId)
                .map(this::toDetail);
    }

    public LoopIterationSummary linkSnapshot(String iterationId, String snapshotId) {
        LoopIterationEntity e = findOrThrow(iterationId);
        e.setSnapshotId(snapshotId);
        if (statusRank(e.getStatus()) < statusRank(MONITORING)) e.setStatus(MONITORING);
        loopIterationRepository.save(e);
        log.info("[loop] iteration {} linked snapshot={}", iterationId, snapshotId);
        return toSummary(e);
    }

    public LoopIterationSummary linkAnalysis(String iterationId, String analysisId) {
        LoopIterationEntity e = findOrThrow(iterationId);
        e.setAnalysisId(analysisId);
        if (statusRank(e.getStatus()) < statusRank(ANALYZED)) e.setStatus(ANALYZED);
        loopIterationRepository.save(e);
        log.info("[loop] iteration {} linked analysisId={}", iterationId, analysisId);
        return toSummary(e);
    }

    public LoopIterationSummary linkPlan(String iterationId, String planId) {
        LoopIterationEntity e = findOrThrow(iterationId);
        e.setPlanId(planId);
        if (statusRank(e.getStatus()) < statusRank(PLANNED)) e.setStatus(PLANNED);
        loopIterationRepository.save(e);
        log.info("[loop] iteration {} linked planId={}", iterationId, planId);
        return toSummary(e);
    }

    public LoopIterationSummary linkExecute(String iterationId, String executeRefactorId) {
        LoopIterationEntity e = findOrThrow(iterationId);
        e.setExecuteRefactorId(executeRefactorId);
        if (statusRank(e.getStatus()) < statusRank(EXECUTING)) e.setStatus(EXECUTING);
        loopIterationRepository.save(e);
        log.info("[loop] iteration {} linked executeRefactorId={}", iterationId, executeRefactorId);
        return toSummary(e);
    }

    /**
     * Drives the iteration to its terminal phase status once an Execute run finishes.
     *
     * <p>Set directly rather than through {@link #statusRank}: DEPLOYED and FAILED share rank 5,
     * so the monotonic guard used by the {@code link*} methods cannot distinguish them.
     */
    public void updateFromExecuteStatus(String iterationId, String executeStatus) {
        if (iterationId == null || iterationId.isBlank()) return;
        loopIterationRepository.findById(iterationId).ifPresent(e -> {
            if ("DEPLOYED".equals(executeStatus)) {
                e.setStatus(DEPLOYED);
                e.setCompletedAt(Instant.now());
            } else if ("FAILED".equals(executeStatus) || "ROLLED_BACK".equals(executeStatus)) {
                e.setStatus(FAILED);
                e.setCompletedAt(Instant.now());
            } else if ("NOT_APPLICABLE".equals(executeStatus)) {
                // The operation did not fit the codebase — the run did not fail and the iteration is
                // not finished. Drop back to PLANNED (set directly; statusRank's monotonic guard
                // only applies to the link* methods) so the loop can try the next candidate.
                e.setStatus(PLANNED);
            }
            snapshotConstraints(e, executeStatus);
            loopIterationRepository.save(e);
            log.info("[loop] iteration {} → {} (execute {})", iterationId, e.getStatus(), executeStatus);
        });
    }

    /**
     * Freezes the constraints in force onto the iteration once its Execute run has settled.
     *
     * <p>Taken here rather than at analysis time on purpose: a restriction the Execute phase records
     * — when a skill reports its operation inapplicable — is created after
     * {@code AnalysisResult.activeConstraints} was frozen, so this is the only snapshot that
     * includes what the iteration itself discovered.
     */
    private void snapshotConstraints(LoopIterationEntity e, String executeStatus) {
        try {
            e.setConstraintsJson(objectMapper.writeValueAsString(constraintService.getAllSnapshots()));
        } catch (Exception ex) {
            // Bookkeeping must never sink an iteration outcome.
            log.warn("[loop] iteration {} — could not snapshot constraints after execute {}: {}",
                    e.getIterationId(), executeStatus, ex.getMessage());
        }
    }

    // ─── Branch lifecycle ────────────────────────────────────────────────────

    /** The branch an Execute run works on, and the branch it must fall back to when it fails. */
    public record BranchAssignment(String branchName, String parentBranchName) {}

    /**
     * Resolves this iteration's branch, pinning it on first use and reusing it on retry.
     *
     * <p>The parent is the branch of the code version being refactored — the last known-good code —
     * so a failed build returns there rather than to the configured base branch, which may be many
     * versions behind.
     *
     * @param iterationId   null/blank for test runs and direct API calls; those get a throwaway
     *                      branch off the base branch and persist nothing
     * @param versionNumber the Execute version number, used to build the branch name
     */
    public BranchAssignment ensureBranch(String iterationId, int versionNumber) {
        if (iterationId == null || iterationId.isBlank()) {
            return new BranchAssignment("refactor/v" + versionNumber + "-" + UUID.randomUUID(), executeConfig.getBaseBranch());
        }

        Optional<LoopIterationEntity> found = loopIterationRepository.findById(iterationId);
        if (found.isEmpty()) {
            log.warn("[loop] iteration {} not found — using an unpinned branch", iterationId);
            return new BranchAssignment("refactor/v" + versionNumber + "-" + iterationId, executeConfig.getBaseBranch());
        }

        LoopIterationEntity e = found.get();
        String parent = e.getParentBranchName() != null && !e.getParentBranchName().isBlank()
                ? e.getParentBranchName()
                : resolveParentBranch(e);

        if (e.getBranchName() != null && !e.getBranchName().isBlank()) {
            // Retry: the branch is already pinned. checkout -B resets it to the same start point.
            if (!parent.equals(e.getParentBranchName())) {
                e.setParentBranchName(parent);
                loopIterationRepository.save(e);
            }
            log.info("[loop] iteration {} reusing pinned branch '{}' (parent '{}')",
                    iterationId, e.getBranchName(), parent);
            return new BranchAssignment(e.getBranchName(), parent);
        }

        String branch = "refactor/v" + versionNumber + "-" + iterationId;
        e.setBranchName(branch);
        e.setParentBranchName(parent);
        e.setBranchStatus(BRANCH_CREATED);
        loopIterationRepository.save(e);
        log.info("[loop] iteration {} branch '{}' created from parent '{}'", iterationId, branch, parent);
        return new BranchAssignment(branch, parent);
    }

    /** The branch of the version this iteration refactors; the base branch for the baseline. */
    private String resolveParentBranch(LoopIterationEntity e) {
        String fromVersion = e.getCodeVersionId() == null ? null
                : codeVersionService.findById(e.getCodeVersionId())
                        .map(CodeVersionEntity::getBranchName)
                        .filter(b -> !b.isBlank())
                        .orElse(null);
        return fromVersion != null ? fromVersion : executeConfig.getBaseBranch();
    }

    /**
     * Puts an iteration whose Execute run FAILED back to EXECUTING, because that run is being
     * resumed from its committed refactor. Set directly: the run's own terminal update will move it
     * on to DEPLOYED or FAILED again.
     */
    public void reopenForResume(String iterationId) {
        if (iterationId == null || iterationId.isBlank()) return;
        loopIterationRepository.findById(iterationId).ifPresent(e -> {
            e.setStatus(EXECUTING);
            e.setCompletedAt(null);
            e.setBranchStatus(BRANCH_EXECUTING_REFACTOR);
            loopIterationRepository.save(e);
            log.info("[loop] iteration {} → {} (execute resumed)", iterationId, EXECUTING);
        });
    }

    public void markBranchStatus(String iterationId, String branchStatus) {
        if (iterationId == null || iterationId.isBlank()) return;
        loopIterationRepository.findById(iterationId).ifPresent(e -> {
            e.setBranchStatus(branchStatus);
            loopIterationRepository.save(e);
            log.info("[loop] iteration {} branch_status → {}", iterationId, branchStatus);
        });
    }

    /** Appends why the iteration's Execute run failed to its notes — the only record of that reason. */
    public void recordBuildFailure(String iterationId, String reason) {
        if (iterationId == null || iterationId.isBlank()) return;
        loopIterationRepository.findById(iterationId).ifPresent(e -> {
            if (reason != null && !reason.isBlank()) {
                String prefix = e.getNotes() == null || e.getNotes().isBlank() ? "" : e.getNotes() + "\n";
                e.setNotes(prefix + "[build_failed] " + reason);
            }
            loopIterationRepository.save(e);
            log.info("[loop] iteration {} recorded build failure: {}", iterationId, reason);
        });
    }

    public int seed() {
        int created = 0;

        for (var execRefactor : executeRefactorRepository.findAllByOrderByStartedAtAsc()) {
            if (loopIterationRepository.existsByExecuteRefactorId(execRefactor.getExecuteRefactorId())) continue;

            int num = loopIterationRepository.findMaxIterationNumber() + 1;
            String loopStatus = mapExecStatus(execRefactor.getStatus().name());
            LoopIterationEntity loop = LoopIterationEntity.builder()
                    .iterationId(UUID.randomUUID().toString())
                    .iterationNumber(num)
                    .name("Iteration " + num + " (seeded)")
                    .status(loopStatus)
                    .snapshotId(execRefactor.getSnapshotId())
                    .planId(null)
                    .executeRefactorId(execRefactor.getExecuteRefactorId())
                    .startedAt(execRefactor.getStartedAt() != null ? execRefactor.getStartedAt() : Instant.now())
                    .completedAt(execRefactor.getCompletedAt())
                    .build();
            loopIterationRepository.save(loop);
            created++;
            log.info("[loop:seed] Created loop iteration #{} from executeRefactor={}", num, execRefactor.getExecuteRefactorId());
        }

        for (var snap : snapshotRepository.findAllSummaries()) {
            String snapshotId = snap.getSnapshotId();
            if (loopIterationRepository.existsBySnapshotId(snapshotId)) continue;

            int num = loopIterationRepository.findMaxIterationNumber() + 1;
            LoopIterationEntity loop = LoopIterationEntity.builder()
                    .iterationId(UUID.randomUUID().toString())
                    .iterationNumber(num)
                    .name("Iteration " + num + " (seeded)")
                    .status(MONITORING)
                    .snapshotId(snapshotId)
                    .startedAt(snap.getCollectedAt() != null ? snap.getCollectedAt() : Instant.now())
                    .build();
            loopIterationRepository.save(loop);
            created++;
            log.info("[loop:seed] Created loop iteration #{} from snapshot={}", num, snapshotId);
        }

        for (var plan : planDocumentRepository.findAll()) {
            if (loopIterationRepository.existsByPlanId(plan.getPlanId())) continue;

            int num = loopIterationRepository.findMaxIterationNumber() + 1;
            LoopIterationEntity loop = LoopIterationEntity.builder()
                    .iterationId(UUID.randomUUID().toString())
                    .iterationNumber(num)
                    .name("Iteration " + num + " (seeded)")
                    .status(PLANNED)
                    .snapshotId(plan.getSnapshotId())
                    .planId(plan.getPlanId())
                    .startedAt(plan.getCreatedAt() != null ? plan.getCreatedAt() : Instant.now())
                    .build();
            loopIterationRepository.save(loop);
            created++;
            log.info("[loop:seed] Created loop iteration #{} from plan={}", num, plan.getPlanId());
        }

        log.info("[loop:seed] Seeding complete — created {} iteration(s)", created);
        return created;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private LoopIterationEntity findOrThrow(String iterationId) {
        return loopIterationRepository.findById(iterationId)
                .orElseThrow(() -> new IllegalArgumentException("LoopIteration not found: " + iterationId));
    }

    private LoopIterationSummary toSummary(LoopIterationEntity e) {
        return LoopIterationSummary.builder()
                .iterationId(e.getIterationId())
                .iterationNumber(e.getIterationNumber())
                .name(e.getName())
                .status(e.getStatus())
                .snapshotId(e.getSnapshotId())
                .analysisId(e.getAnalysisId())
                .planId(e.getPlanId())
                .executeRefactorId(e.getExecuteRefactorId())
                .codeVersionId(e.getCodeVersionId())
                .branchName(e.getBranchName())
                .parentBranchName(e.getParentBranchName())
                .branchStatus(e.getBranchStatus())
                .startedAt(e.getStartedAt() != null ? e.getStartedAt().toString() : null)
                .completedAt(e.getCompletedAt() != null ? e.getCompletedAt().toString() : null)
                .notes(e.getNotes())
                .build();
    }

    private LoopIterationDetail toDetail(LoopIterationEntity e) {
        MonitorSnapshot snapshot = e.getSnapshotId() != null
                ? monitorService.getById(e.getSnapshotId()).orElse(null) : null;

        AnalysisResult analysis = e.getAnalysisId() != null
                ? analyzeService.getById(e.getAnalysisId()).orElse(null) : null;

        PlanSummary plan = null;
        if (e.getPlanId() != null) {
            plan = planService.getPlan(e.getPlanId())
                    .map(r -> new PlanSummary(
                            r.planId(), r.operation(), r.targetService(),
                            null, r.snapshotId(), r.status(), r.createdAt(), r.skillInvocation()))
                    .orElse(null);
        }

        ExecuteResponse execution = e.getExecuteRefactorId() != null
                ? executeRefactorRepository.findById(e.getExecuteRefactorId())
                        .map(ExecuteResponse::from).orElse(null)
                : null;

        return LoopIterationDetail.builder()
                .iterationId(e.getIterationId())
                .iterationNumber(e.getIterationNumber())
                .name(e.getName())
                .status(e.getStatus())
                .startedAt(e.getStartedAt() != null ? e.getStartedAt().toString() : null)
                .completedAt(e.getCompletedAt() != null ? e.getCompletedAt().toString() : null)
                .notes(e.getNotes())
                .snapshotId(e.getSnapshotId())
                .analysisId(e.getAnalysisId())
                .planId(e.getPlanId())
                .executeRefactorId(e.getExecuteRefactorId())
                .codeVersionId(e.getCodeVersionId())
                .branchName(e.getBranchName())
                .parentBranchName(e.getParentBranchName())
                .branchStatus(e.getBranchStatus())
                .snapshot(snapshot)
                .analysis(analysis)
                .plan(plan)
                .execution(execution)
                .build();
    }

    private String mapExecStatus(String execStatus) {
        return switch (execStatus) {
            case "DEPLOYED" -> DEPLOYED;
            case "FAILED", "ROLLED_BACK" -> FAILED;
            case "MUTATING", "BUILDING", "DEPLOYING" -> EXECUTING;
            // NOT_APPLICABLE is deliberately PLANNED, not FAILED: nothing went wrong, the operation
            // simply did not fit, and the iteration is still open for the next candidate.
            case "NOT_APPLICABLE" -> PLANNED;
            default -> PLANNED;
        };
    }

    private int statusRank(String status) {
        return switch (status == null ? "" : status) {
            case CREATED    -> 0;
            case MONITORING -> 1;
            case ANALYZED   -> 2;
            case PLANNED    -> 3;
            case EXECUTING  -> 4;
            case DEPLOYED   -> 5;
            case FAILED     -> 5;
            default         -> 0;
        };
    }
}
