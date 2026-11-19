package org.anonymous.msrefactor.rt.execute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.constants.TimingConstants;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.dto.ExecuteResponse;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteStatus;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.execute.util.SecretRedactor;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.DeployCommandRenderer;
import org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService;
import org.anonymous.msrefactor.rt.analyze.persistence.RefactorConstraintEntity;
import org.anonymous.msrefactor.rt.analyze.service.ConstraintService;
import org.anonymous.msrefactor.rt.execute.RestrictionParser;
import org.anonymous.msrefactor.rt.plan.dto.PlanResponse;
import org.anonymous.msrefactor.rt.plan.service.PlanService;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployService;
import org.anonymous.msrefactor.rt.topology.DeployTopology;
import org.anonymous.msrefactor.rt.topology.HostPortInspector;
import org.anonymous.msrefactor.rt.topology.TopologyConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecuteService {

    private static final List<ExecuteStatus> ACTIVE_STATUSES = List.of(
            ExecuteStatus.PENDING, ExecuteStatus.MUTATING,
            ExecuteStatus.BUILDING, ExecuteStatus.DEPLOYING,
            // A ghost run parked for a verdict is still in flight: its branch is checked out and
            // its iteration is open, so a second run must not be allowed to start behind it.
            ExecuteStatus.AWAITING_VERDICT
    );

    private final ExecuteRefactorRepository executeRefactorRepository;
    private final PlanService planService;
    private final ProcessRunner processRunner;
    private final ExecuteConfig config;
    private final AppTargetConfig appTarget;
    private final ObjectMapper objectMapper;
    private final BehavioralPreservationChecker preservationChecker;
    private final ClaudeCommandMutator claudeCommandMutator;
    private final CodeVersionService codeVersionService;
    private final LoopIterationRepository loopIterationRepository;
    private final LoopIterationService loopIterationService;
    private final ComposeTopologyResolver topologyResolver;
    private final TopologyConfig topologyConfig;
    private final ConstraintService constraintService;
    private final HostPortInspector hostPortInspector;


    @PostConstruct
    void logGhostMode() {
        if (config.isGhostMode()) {
            log.warn("[execute] ***** GHOST MODE ACTIVE (RT_EXECUTE_GHOST_MODE=true) ***** "
                    + "Runs create a branch and wait for a manual verdict; no Claude mutation, "
                    + "no test gate, no image build, no deploy. Versions produced this way carry "
                    + "no code change. Redeploy without --ghost for real refactorings.");
        }
    }

    private final Object pipelineLock = new Object();

    @Autowired
    @Qualifier("executeTaskExecutor")
    private Executor pipelineExecutor;

    /**
     * Fails runs left mid-pipeline by a previous rt-app process. Their executor thread died with
     * that process, so nothing will ever move them on — and while they sit in an active status
     * {@link #isRunning()} refuses every new run. A ghost run parked at AWAITING_VERDICT is not
     * touched: it holds no thread and is resumed by the operator's verdict. Git is left alone; the
     * next run checks out its own start point.
     */
    @EventListener(ApplicationReadyEvent.class)
    void failOrphanedRuns() {
        List<ExecuteRefactorEntity> orphans = executeRefactorRepository.findAllByStatusIn(List.of(
                ExecuteStatus.PENDING, ExecuteStatus.MUTATING, ExecuteStatus.BUILDING, ExecuteStatus.DEPLOYING));
        for (ExecuteRefactorEntity orphan : orphans) {
            StringBuilder execLog = new StringBuilder(orphan.getExecuteLog() == null ? "" : orphan.getExecuteLog());
            fail(orphan, execLog, "Aborted — rt-app restarted while the run was " + orphan.getStatus());
        }
    }

    public boolean isRunning() {
        return executeRefactorRepository.existsByStatusIn(ACTIVE_STATUSES);
    }

    public ExecuteResponse startRun(String iterationId) {
        synchronized (pipelineLock) {
            if (isRunning()) {
                throw new IllegalStateException("Another execution is already running");
            }
            log.info("[execute] startRun() invoked — loading current plan");
            PlanResponse current = planService.getCurrent()
                    .orElseThrow(() -> new IllegalStateException(
                            "No current plan — call POST /plan/generate first"));
            // Re-fetch so the run reflects the latest approval/version state, not a stale in-memory plan.
            PlanResponse plan = planService.getPlan(current.planId()).orElse(current);
            log.info("[execute] current plan loaded: planId={} operation={} target={}",
                    plan.planId(), plan.operation(), plan.targetService());
            logPinnedVersion(plan);

            int version = executeRefactorRepository.findMaxVersionNumber() + 1;
            var branch = loopIterationService.ensureBranch(iterationId, version);
            String sourceVersionId = resolveSourceVersionId(iterationId);

            ExecuteRefactorEntity exec = ExecuteRefactorEntity.builder()
                    .executeRefactorId(UUID.randomUUID().toString())
                    .versionNumber(version)
                    .branchName(branch.branchName())
                    .parentBranchName(branch.parentBranchName())
                    .iterationId(iterationId)
                    .operation(plan.operation())
                    .targetService(plan.targetService())
                    // The stop-verdict scope check compares the run's pair against the block's; a
                    // null secondary made every paired operation's verdict look out-of-scope.
                    .secondaryTarget(plan.secondaryTarget())
                    .planPayload(toJson(plan))
                    .executedPlanVersionId(plan.activePlanVersionId())
                    .executedVersionNumber(plan.activeVersionNumber())
                    .sourceVersionId(sourceVersionId)
                    .status(ExecuteStatus.PENDING)
                    .executeLog("")
                    .startedAt(Instant.now())
                    .build();

            executeRefactorRepository.save(exec);
            log.info("[execute] Created execute-refactor v{} branch={} parent={} sourceVersion={}",
                    version, branch.branchName(), branch.parentBranchName(), sourceVersionId);
            String execRefactorId = exec.getExecuteRefactorId();
            pipelineExecutor.execute(() -> runPipeline(execRefactorId, plan));

            return ExecuteResponse.from(exec);
        }
    }

    public ExecuteResponse startRunForPlan(String planId, String iterationId) {
        synchronized (pipelineLock) {
            if (isRunning()) {
                throw new IllegalStateException("Another execution is already running");
            }
            log.info("[execute] startRunForPlan() invoked — planId={}", planId);
            PlanResponse plan = planService.getPlan(planId)
                    .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
            log.info("[execute] plan loaded: planId={} operation={} target={}",
                    plan.planId(), plan.operation(), plan.targetService());
            logPinnedVersion(plan);

            int version = executeRefactorRepository.findMaxVersionNumber() + 1;
            var branch = loopIterationService.ensureBranch(iterationId, version);
            String sourceVersionId = resolveSourceVersionId(iterationId);

            ExecuteRefactorEntity exec = ExecuteRefactorEntity.builder()
                    .executeRefactorId(UUID.randomUUID().toString())
                    .versionNumber(version)
                    .branchName(branch.branchName())
                    .parentBranchName(branch.parentBranchName())
                    .iterationId(iterationId)
                    .operation(plan.operation())
                    .targetService(plan.targetService())
                    // The stop-verdict scope check compares the run's pair against the block's; a
                    // null secondary made every paired operation's verdict look out-of-scope.
                    .secondaryTarget(plan.secondaryTarget())
                    .planPayload(toJson(plan))
                    .executedPlanVersionId(plan.activePlanVersionId())
                    .executedVersionNumber(plan.activeVersionNumber())
                    .sourceVersionId(sourceVersionId)
                    .status(ExecuteStatus.PENDING)
                    .executeLog("")
                    .startedAt(Instant.now())
                    .build();

            executeRefactorRepository.save(exec);
            log.info("[execute] Created execute-refactor v{} branch={} parent={} sourceVersion={} for planId={}",
                    version, branch.branchName(), branch.parentBranchName(), sourceVersionId, planId);
            String execRefactorId = exec.getExecuteRefactorId();
            pipelineExecutor.execute(() -> runPipeline(execRefactorId, plan));

            return ExecuteResponse.from(exec);
        }
    }

    public ExecuteResponse startTestRun() {
        synchronized (pipelineLock) {
            if (isRunning()) {
                throw new IllegalStateException("Another execution is already running");
            }
            int version = executeRefactorRepository.findMaxVersionNumber() + 1;
            String branch = "refactor/v" + version + "-test-change";

            PlanResponse syntheticPlan = new PlanResponse(
                    "test-run", "TEST", "TEST_CHANGE", "quiz-service", null,
                    null, "test-change", Instant.now().toString(), null, null, null, null, null, null,
                    null, null, null);

            ExecuteRefactorEntity exec = ExecuteRefactorEntity.builder()
                    .executeRefactorId(UUID.randomUUID().toString())
                    .versionNumber(version)
                    .branchName(branch)
                    // No iteration, so recovery falls back to the configured base branch.
                    .parentBranchName(config.getBaseBranch())
                    .operation("TEST_CHANGE")
                    .targetService("quiz-service")
                    .planPayload(toJson(syntheticPlan))
                    .sourceVersionId(resolveSourceVersionId(null))
                    .status(ExecuteStatus.PENDING)
                    .executeLog("")
                    .startedAt(Instant.now())
                    .build();

            executeRefactorRepository.save(exec);
            log.info("[execute] Created test-run execute-refactor v{} branch={}", version, branch);
            String execRefactorId = exec.getExecuteRefactorId();
            pipelineExecutor.execute(() -> runPipeline(execRefactorId, syntheticPlan));
            return ExecuteResponse.from(exec);
        }
    }

    /**
     * Re-enters a FAILED run at step 5a, reusing the refactor it already committed.
     *
     * <p>The mutation is the expensive, non-deterministic part of a run; when what failed came after
     * it — a hung or failed test gate, an image build, a deploy, or an rt-app restart — repeating it
     * would cost the same time again and produce a different refactor. Only allowed when the run's
     * branch still points at the commit the run recorded, so the resumed build tests exactly that
     * refactor. Same run, same version number; its log continues after a {@code [resume]} marker.
     */
    public ExecuteResponse resumeFromBuild(String executeRefactorId) {
        synchronized (pipelineLock) {
            if (isRunning()) {
                throw new IllegalStateException("Another execution is already running");
            }
            ExecuteRefactorEntity exec = executeRefactorRepository.findById(executeRefactorId)
                    .orElseThrow(() -> new IllegalArgumentException("ExecuteRefactor not found: " + executeRefactorId));
            if (exec.getStatus() != ExecuteStatus.FAILED) {
                throw new IllegalStateException("Only a FAILED run can be resumed (v" + exec.getVersionNumber()
                        + " is " + exec.getStatus() + ")");
            }
            String sha = exec.getGitCommitSha();
            if (sha == null || sha.isBlank()) {
                throw new IllegalStateException("v" + exec.getVersionNumber()
                        + " never committed a refactor — nothing to resume; start a new run");
            }
            String projectRoot = config.getProjectRoot();
            String branchTip = resolveRef(exec.getBranchName(), projectRoot);
            if (branchTip == null) {
                throw new IllegalStateException("Branch '" + exec.getBranchName() + "' no longer exists");
            }
            if (!branchTip.equals(sha)) {
                throw new IllegalStateException("Branch '" + exec.getBranchName() + "' is at " + branchTip
                        + ", not at the run's commit " + sha + " — resume would test different code");
            }

            StringBuilder execLog = new StringBuilder(exec.getExecuteLog() == null ? "" : exec.getExecuteLog());
            execLog.append("\n\n[resume] ").append(Instant.now())
                   .append(" — resuming at step 5a from commit ").append(sha)
                   .append(" on '").append(exec.getBranchName()).append("'; mutation not repeated.\n");
            exec.setCompletedAt(null);
            // Set synchronously so isRunning() blocks a second start before the pipeline thread runs.
            setStatus(exec, ExecuteStatus.BUILDING, execLog);
            loopIterationService.reopenForResume(exec.getIterationId());
            log.info("[execute] v{} resume requested — branch='{}' sha={}",
                    exec.getVersionNumber(), exec.getBranchName(), sha);

            pipelineExecutor.execute(() -> resumePipeline(executeRefactorId));
            return ExecuteResponse.from(exec);
        }
    }

    void resumePipeline(String executeRefactorId) {
        String projectRoot = config.getProjectRoot();
        ExecuteRefactorEntity exec = null;
        StringBuilder execLog = new StringBuilder();
        boolean branchCheckedOut = false;
        AtomicBoolean stackTornDown = new AtomicBoolean(false);
        try {
            exec = executeRefactorRepository.findById(executeRefactorId)
                    .orElseThrow(() -> new IllegalStateException("ExecuteRefactor not found: " + executeRefactorId));
            execLog.append(exec.getExecuteLog() == null ? "" : exec.getExecuteLog());
            PlanResponse plan = readPlanPayload(exec);

            // Plain checkout, not --force: the branch already holds the refactor, and an unrelated
            // dirty file in the tree should stop the resume rather than be silently discarded.
            runGit(execLog, projectRoot, "-C", projectRoot, "checkout", exec.getBranchName());
            branchCheckedOut = true;
            topologyResolver.invalidate();
            String head = captureGitSha(projectRoot);
            if (!exec.getGitCommitSha().equals(head)) {
                throw new IllegalStateException("HEAD is " + head + " after checkout, expected " + exec.getGitCommitSha());
            }
            loopIterationService.markBranchStatus(
                    exec.getIterationId(), LoopIterationService.BRANCH_EXECUTING_REFACTOR);
            log.info("[execute] v{} resume — on '{}' at {}; continuing from step 5a",
                    exec.getVersionNumber(), exec.getBranchName(), head);

            buildTestAndDeploy(exec, plan, execLog, projectRoot, branchCheckedOut, stackTornDown);
        } catch (Exception e) {
            if (exec != null) {
                fail(exec, execLog, "Unexpected error on resume: " + e.getMessage());
                log.error("[execute] v{} resume FAILED — {}", exec.getVersionNumber(), e.getMessage(), e);
                recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                        execLog, "Unexpected error on resume: " + e.getMessage());
            } else {
                log.error("[execute] resume FAILED before entity load (executeRefactorId={}) — {}",
                        executeRefactorId, e.getMessage(), e);
            }
        }
    }

    /** The commit {@code ref} points at, or null when it does not resolve. */
    private String resolveRef(String ref, String projectRoot) {
        try {
            ProcessResult r = processRunner.run(
                    List.of("git", "-C", projectRoot, "rev-parse", "--verify", ref + "^{commit}"), projectRoot);
            return r.succeeded() && !r.stdout().isBlank() ? r.stdout().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Optional<ExecuteResponse> getLatest() {
        return executeRefactorRepository.findTopByOrderByVersionNumberDesc()
                .map(ExecuteResponse::from);
    }

    public Optional<ExecuteResponse> getById(String executeRefactorId) {
        return executeRefactorRepository.findById(executeRefactorId)
                .map(ExecuteResponse::from);
    }

    public ExecuteResponse rollback() {
        ExecuteRefactorEntity latest = executeRefactorRepository.findTopByOrderByVersionNumberDesc()
                .orElseThrow(() -> new IllegalStateException("No execute-refactor records to roll back"));

        if (latest.getStatus() != ExecuteStatus.DEPLOYED) {
            throw new IllegalStateException(
                    "Latest execute-refactor is not DEPLOYED (status=" + latest.getStatus() + ")");
        }

        StringBuilder execLog = new StringBuilder(
                latest.getExecuteLog() != null ? latest.getExecuteLog() : "");
        String projectRoot = config.getProjectRoot();

        try {
            ExecuteRefactorEntity previous = executeRefactorRepository
                    .findTopByStatusAndVersionNumberLessThanOrderByVersionNumberDesc(
                            ExecuteStatus.DEPLOYED, latest.getVersionNumber())
                    .orElse(null);

            // 1. Restore the source tree FIRST. The compose file must describe the topology being
            //    rolled back to before anything reads it — otherwise a rollback across a merge
            //    would try to start services the working tree no longer defines.
            // Same rule as resolveBranchStartPoint: a version's commit is exact, but rolling back
            // past every version lands on the base branch, whose tip is a run-time fact.
            String startPoint = (previous != null && previous.getGitCommitSha() != null
                    && !previous.getGitCommitSha().isBlank())
                    ? previous.getGitCommitSha()
                    : resolveBranchTip(config.getBaseBranch(), projectRoot, execLog);
            // --force: the mutation may have left the tree dirty, which a plain checkout refuses.
            ProcessResult checkout = processRunner.run(
                    List.of("git", "-C", projectRoot, "checkout", "--force", startPoint), projectRoot);
            execLog.append(checkout.toLogEntry("git-checkout-rollback-target"));
            topologyResolver.invalidate();

            // 2. Re-tag the previous image as the one the compose file will run.
            String prevTag = previousImageTag(latest.getVersionNumber());
            if (prevTag != null) {
                ProcessResult retag = processRunner.run(
                        List.of("docker", "tag", prevTag, appTarget.latestTag()), projectRoot);
                execLog.append(retag.toLogEntry("docker-retag-rollback"));
            }

            // 3. Rediscover from the restored tree — this now yields the previous topology.
            DeployTopology topology = topologyResolver.resolve(
                    config.getDeployTarget(), true,
                    () -> readServiceNames(previous == null ? null : previous.getDeployedServices()));

            List<String> expected = readServiceNames(
                    previous == null ? null : previous.getDeployedServices());
            if (!expected.isEmpty() && !expected.equals(topology.serviceNames())) {
                log.warn("[execute] rollback topology differs from what v{} recorded — recorded={} resolved={}",
                        previous.getVersionNumber(), expected, topology.serviceNames());
                execLog.append("\n[rollback] topology divergence: recorded=").append(expected)
                       .append(" resolved=").append(topology.serviceNames()).append("\n");
            }

            if (topology.serviceNames().isEmpty()) {
                throw new IllegalStateException("rollback resolved no services — refusing to deploy");
            }

            // A manual rollback tears the stack down unconditionally, so the flag is informational.
            AtomicBoolean tornDown = new AtomicBoolean(false);
            if (!redeploy(topology, latest.getVersionNumber(), execLog, tornDown)
                    || !waitForServicesHealthy(topology, latest.getVersionNumber(), execLog)) {
                execLog.append("\n[ROLLBACK WARN] services did not come back healthy\n");
            }

        } catch (Exception e) {
            execLog.append("\n[ROLLBACK ERROR] ").append(e.getMessage());
        }

        latest.setStatus(ExecuteStatus.ROLLED_BACK);
        latest.setCompletedAt(Instant.now());
        latest.setExecuteLog(execLog.toString());
        executeRefactorRepository.save(latest);

        return ExecuteResponse.from(latest);
    }

    // ─── Ghost mode ───────────────────────────────────────────────────────────

    /**
     * Settles a ghost run parked at {@link ExecuteStatus#AWAITING_VERDICT}.
     *
     * <p>The verdict is taken <b>before</b> anything is written, because it is what the CLAUDE.md
     * refactor-log entry records: {@code CHANGED} for a success, {@code NO_CHANGE} for a failure.
     * Writing the entry first and patching it afterwards would leave a wrong entry on disk for the
     * window in between, and the entry is the whole artefact a ghost run exists to produce.
     *
     * <p>From there each outcome rejoins the real pipeline's own terminal path — the success block
     * of {@link #runPipeline} or {@link #recoverToParentBranch} — so the iteration, branch status,
     * code version and follow-up iteration all settle exactly as they would after a real run.
     * Docker is never touched in either direction: a ghost run never tore the stack down.
     */
    public ExecuteResponse recordVerdict(String executeRefactorId, boolean success, String summary) {
        ExecuteRefactorEntity exec = executeRefactorRepository.findById(executeRefactorId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ExecuteRefactor not found: " + executeRefactorId));

        if (exec.getStatus() != ExecuteStatus.AWAITING_VERDICT) {
            throw new IllegalStateException("Execute-refactor v" + exec.getVersionNumber()
                    + " is not awaiting a verdict (status=" + exec.getStatus() + ")");
        }

        StringBuilder execLog = new StringBuilder(
                exec.getExecuteLog() != null ? exec.getExecuteLog() : "");
        String projectRoot = config.getProjectRoot();
        PlanResponse plan = readPlanPayload(exec);

        log.info("[execute] v{} — GHOST verdict: {}", exec.getVersionNumber(),
                success ? "SUCCESS" : "FAILED");

        try {
            appendGhostLogEntry(exec, plan, success, summary, execLog);
            commitLogEntryOnly(exec, projectRoot, execLog,
                    "refactor(v" + exec.getVersionNumber() + ") [ghost]: " + plan.operation()
                            + " " + plan.targetService() + " — " + (success ? "CHANGED" : "NO_CHANGE"));
        } catch (Exception e) {
            // The entry is the artefact; failing to record it makes the verdict meaningless, so
            // this fails the run rather than silently continuing to the success path.
            fail(exec, execLog, "Ghost verdict could not be recorded: " + e.getMessage());
            recoverToParentBranch(exec, true, false, projectRoot, execLog,
                    "Ghost verdict could not be recorded");
            return ExecuteResponse.from(exec);
        }

        if (!success) {
            fail(exec, execLog, "Ghost run marked FAILED by the operator");
            // branchCheckedOut=true (step 2 ran), stackTornDown=false (a ghost run never deploys),
            // so recovery returns to the parent branch and leaves the containers alone.
            recoverToParentBranch(exec, true, false, projectRoot, execLog,
                    "Ghost run marked FAILED by the operator");
            return ExecuteResponse.from(exec);
        }

        // Mirrors runPipeline's terminal success block. imageTag stays null: no image was built,
        // so the code version records none and Monitor measures whatever stack is already running.
        exec.setStatus(ExecuteStatus.DEPLOYED);
        exec.setCompletedAt(Instant.now());
        exec.setExecuteLog(execLog.toString());
        executeRefactorRepository.save(exec);
        loopIterationService.markBranchStatus(
                exec.getIterationId(), LoopIterationService.BRANCH_BUILD_SUCCESS);
        loopIterationService.updateFromExecuteStatus(
                exec.getIterationId(), ExecuteStatus.DEPLOYED.name());
        log.info("[execute] v{} GHOST DEPLOYED — branch='{}' (no image, no deploy)",
                exec.getVersionNumber(), exec.getBranchName());
        recordVersionAndFollowUp(exec);

        return ExecuteResponse.from(exec);
    }

    /**
     * Appends the refactor-log entry a skill would have written, with the operator's verdict in the
     * {@code Result} field.
     *
     * <p>Deliberately emits no {@code REFACTOR_RESTRICTION} block: those markers are what
     * {@code readRestriction} scans for, and a ghost entry must not teach Analyze that a real
     * architectural stop was hit.
     */
    private void appendGhostLogEntry(ExecuteRefactorEntity exec, PlanResponse plan, boolean success,
                                     String summary, StringBuilder execLog) throws Exception {
        String invocation = (plan.enrichedSkillInvocation() != null
                && !plan.enrichedSkillInvocation().isBlank())
                ? plan.enrichedSkillInvocation() : plan.skillInvocation();
        if (invocation == null || invocation.isBlank()) {
            invocation = slugify(plan.operation(), plan.targetService());
        }
        String skill = invocation.trim().split("\\s+")[0].replaceFirst("^/", "");
        String note = (summary != null && !summary.isBlank())
                ? summary.trim()
                : (success
                        ? "Ghost run: recorded as applied without executing the refactoring."
                        : "Ghost run: recorded as not applied; no code was changed.");

        String entry = "\n<!-- refactor-log -->\n"
                + "Date: " + Instant.now() + "\n"
                + "Skill: " + skill + "\n"
                + "Invocation: " + invocation + "\n"
                + "Result: " + (success ? "CHANGED" : "NO_CHANGE") + "\n"
                + "Summary: [ghost] " + note.replace('\n', ' ') + "\n"
                + "<!-- end-refactor-log -->\n";

        String target = appTarget.absoluteClaudeMd(config.getProjectRoot());
        Files.write(Paths.get(target), entry.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        execLog.append("\n[GHOST] refactor-log entry appended to ").append(target)
               .append(" (Result: ").append(success ? "CHANGED" : "NO_CHANGE").append(")\n");
        log.info("[execute] v{} — GHOST: refactor-log entry written to {}",
                exec.getVersionNumber(), target);
    }

    /** Rehydrates the plan frozen onto the run at start time. */
    private PlanResponse readPlanPayload(ExecuteRefactorEntity exec) {
        try {
            return objectMapper.readValue(exec.getPlanPayload(), PlanResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the stored plan for execute-refactor "
                    + exec.getExecuteRefactorId() + ": " + e.getMessage(), e);
        }
    }

    /** Logs which plan version is being executed and warns when none was explicitly approved. */
    private void logPinnedVersion(PlanResponse plan) {
        if (plan.activePlanVersionId() == null) return;
        if ("APPROVED".equalsIgnoreCase(plan.approvalState())) {
            log.info("[execute] running APPROVED plan version v{} (id={}) for planId={}",
                    plan.activeVersionNumber(), plan.activePlanVersionId(), plan.planId());
        } else {
            log.warn("[execute] no APPROVED plan version for planId={} — running latest version v{} (state={})",
                    plan.planId(), plan.activeVersionNumber(), plan.approvalState());
        }
    }

    // ─── Async pipeline ──────────────────────────────────────────────────────

    void runPipeline(String executeRefactorId, PlanResponse plan) {
        StringBuilder execLog = new StringBuilder();
        String projectRoot = config.getProjectRoot();
        ExecuteRefactorEntity exec = null;
        // Gates whether cleanup may delete a branch: nothing to remove before step 2 succeeded.
        boolean branchCheckedOut = false;
        // Set when step 3c runs a prerequisite, so the commit can credit it. Some of the files this
        // run publishes are its output, and a message naming only the operation hides that.
        String fulfilledPrerequisite = null;
        // Gates whether recovery must touch Docker at all. Only once step 6's `down` has run is
        // the runtime disturbed; before that the parent's containers are still serving its code,
        // and their image references are pinned at creation, so a rebuild would be pure waste.
        // A holder rather than a plain local because `redeploy` is what learns the answer.
        AtomicBoolean stackTornDown = new AtomicBoolean(false);

        try {
            exec = executeRefactorRepository.findById(executeRefactorId)
                    .orElseThrow(() -> new IllegalStateException("ExecuteRefactor not found: " + executeRefactorId));
            // Step 2 — Git branch
            setStatus(exec, ExecuteStatus.MUTATING, execLog);
            removeTokenInsteadOfRewrites(projectRoot);
            logGitContext(exec.getVersionNumber(), projectRoot, execLog);

            runGit(execLog, projectRoot, "-C", projectRoot, "config", "user.name", config.getGitUserName());
            runGit(execLog, projectRoot, "-C", projectRoot, "config", "user.email", config.getGitUserEmail());
            Map<String, String> fetchAuthEnv = githubAuthEnv();
            if (fetchAuthEnv.isEmpty()) {
                log.warn("[execute] v{} step 2 — GITHUB_TOKEN not set; git fetch may fail if remote is private", exec.getVersionNumber());
            } else {
                log.info("[execute] v{} step 2 — GITHUB_TOKEN passed to git fetch via environment", exec.getVersionNumber());
            }
            runGit(execLog, projectRoot, fetchAuthEnv, "-C", projectRoot, "fetch", "origin");
            String startPoint = resolveBranchStartPoint(exec, projectRoot, execLog);
            runGit(execLog, projectRoot, "-C", projectRoot, "checkout", "-B", exec.getBranchName(), startPoint);
            branchCheckedOut = true;
            // The compose file may differ on this branch; drop any topology cached from another one.
            topologyResolver.invalidate();
            loopIterationService.markBranchStatus(
                    exec.getIterationId(), LoopIterationService.BRANCH_EXECUTING_REFACTOR);
            log.info("[execute] v{} step 2 — branch '{}' created from {} (parent='{}', sourceVersion={})",
                    exec.getVersionNumber(), exec.getBranchName(), startPoint,
                    exec.getParentBranchName(), exec.getSourceVersionId());

            // Ghost mode — stop here. Everything past this point (mutation, tests, image, deploy)
            // is what makes an iteration take minutes, and none of it produces anything the UI
            // needs. The CLAUDE.md log entry is deliberately NOT written yet: the operator's
            // verdict is what decides its Result, so recordVerdict() writes and commits it.
            if (config.isGhostMode()) {
                execLog.append("\n[GHOST] ghost mode — no mutation, no test gate, no image build, "
                             + "no deploy; the running stack is untouched.\n")
                       .append("[GHOST] branch '").append(exec.getBranchName())
                       .append("' created; awaiting operator verdict "
                             + "(POST /execute/verdict).\n");
                setStatus(exec, ExecuteStatus.AWAITING_VERDICT, execLog);
                log.info("[execute] v{} — GHOST: parked at AWAITING_VERDICT on branch '{}'",
                        exec.getVersionNumber(), exec.getBranchName());
                return;
            }

            // Step 3 — Claude code mutation
            String effectiveInvocation = (plan.enrichedSkillInvocation() != null
                    && !plan.enrichedSkillInvocation().isBlank())
                    ? plan.enrichedSkillInvocation() : plan.skillInvocation();
            log.info("[execute] v{} step 3 — applying mutation: skill='{}' ({})",
                    exec.getVersionNumber(), effectiveInvocation,
                    plan.enrichedSkillInvocation() != null && !plan.enrichedSkillInvocation().isBlank()
                            ? "enriched" : "basic");
            applyMutation(exec, plan, execLog);
            log.info("[execute] v{} step 3 — mutation complete", exec.getVersionNumber());

            // Step 3b — Did the skill declare the operation inapplicable?
            // A skill that hits an architectural stop (e.g. merge-aggregate's Ownership Classifier)
            // changes nothing but its CLAUDE.md log entry, and leaves a restriction block there for
            // us to persist. Detect it before the commit so we neither build nor deploy a non-change.
            // A case-4 (sibling) merge emits a prerequisite instead: the pair is not mergeable *yet*,
            // and the chain that would make it so is recorded non-blocking so the loop keeps a path
            // forward. Checked first — a prerequisite is the more specific verdict.
            Optional<Map<String, String>> stop = readPrerequisite(execLog);
            boolean isPrerequisite = stop.isPresent();
            if (!isPrerequisite) stop = readRestriction(execLog);

            // A stop verdict only counts for *this* operation on *this* pair, and cannot be reconciled
            // with a working tree full of changes. Either check failing means the block is not this
            // run's verdict, so it is discarded and the run continues down the normal build path.
            if (stop.isPresent()) {
                // Both checks, deliberately not short-circuited: when a block is rejected for scope
                // the log should still record whether the tree corroborates it. Reading only the
                // first reason once hid the fact that nothing but CLAUDE.md had changed.
                boolean inScope = stopBelongsToRun(stop.get(), exec, execLog);
                boolean codeChanged = changedBeyondLogEntry(projectRoot, exec, execLog);
                if (!inScope || codeChanged) {
                    stop = Optional.empty();
                    isPrerequisite = false;
                }
            }

            // Step 3c — Fulfil a runnable prerequisite, then retry the operation.
            //
            // A prerequisite whose block carries `runFirst:` names a domain skill that makes the
            // operation applicable (create-replica with no sync event names `new-event`). Running
            // it as its own `claude` process is what makes this affordable: --max-turns is per
            // process, so the prerequisite and the retry each get a full budget instead of sharing
            // the operation's. One attempt only — a prerequisite that does not clear the block on
            // the first try will not clear it on the second, and retrying would loop.
            if (stop.isPresent() && isPrerequisite) {
                String runFirst = runnablePrerequisite(stop.get(), exec, execLog);
                if (runFirst != null) {
                    fulfilPrerequisite(exec, plan, runFirst, execLog);
                    fulfilledPrerequisite = runFirst;

                    // Re-read the verdict the retry wrote. Note the deliberate asymmetry with the
                    // pre-fulfilment checks above: the tree is now dirty with the prerequisite's
                    // files, so `changedBeyondLogEntry` would discard any block as "not this run's".
                    stop = readPrerequisite(execLog);
                    isPrerequisite = stop.isPresent();
                    if (!isPrerequisite) stop = readRestriction(execLog);
                    if (stop.isPresent() && !stopBelongsToRun(stop.get(), exec, execLog)) {
                        stop = Optional.empty();
                        isPrerequisite = false;
                    }

                    if (stop.isPresent()) {
                        // The prerequisite ran and the operation still cannot proceed. Its files are
                        // in the tree but the refactoring they were for is not, and committing that
                        // would publish a version carrying scaffolding nobody asked for on its own.
                        // Recovery discards it, so the branch ends where it started.
                        fail(exec, execLog, "prerequisite '" + runFirst
                                + "' ran but " + plan.operation() + " still reports a stop verdict");
                        recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                                execLog, "prerequisite did not make the operation applicable");
                        return;
                    }
                    log.info("[execute] v{} — prerequisite '{}' cleared the stop; operation applied",
                            exec.getVersionNumber(), runFirst);
                }
            }

            if (stop.isPresent()) {
                persistStop(stop.get(), isPrerequisite, exec, execLog);
                commitLogEntryOnly(exec, projectRoot, execLog,
                        "refactor(v" + exec.getVersionNumber() + "): " + plan.operation()
                                + " not applicable to " + plan.targetService());
                setStatus(exec, ExecuteStatus.NOT_APPLICABLE, execLog);
                exec.setCompletedAt(Instant.now());
                executeRefactorRepository.save(exec);
                log.info("[execute] v{} — operation not applicable; {} recorded, no build or deploy",
                        exec.getVersionNumber(), isPrerequisite ? "prerequisite chain" : "restriction");
                return;
            }

            // Step 3d — A tree containing only the skill's own log entry is not a refactoring.
            //
            // Holds whatever the stop block said, or failed to say: malformed, out-of-scope or
            // absent, a run that changed no code did not perform the operation. Without this the
            // CLAUDE.md entry counts as one changed file, gets committed under a normal
            // "refactor(vN): …" message, and becomes a CodeVersion whose entire delta is a note
            // explaining why the refactoring could not be done — poisoning the version tree and
            // everything Monitor measures against it.
            if (countChangedFiles(projectRoot, appTarget.gitPathspec(),
                    appTarget.claudeMdExcludePathspec()) == 0) {
                execLog.append("\n[NO-OP] Nothing outside CLAUDE.md changed — the operation did not "
                             + "run. Recording its log entry and stopping before build.\n");
                log.warn("[execute] v{} — no code changed, only the skill's CLAUDE.md entry; "
                        + "marking NOT_APPLICABLE instead of publishing an empty version",
                        exec.getVersionNumber());
                commitLogEntryOnly(exec, projectRoot, execLog,
                        "refactor(v" + exec.getVersionNumber() + "): " + plan.operation()
                                + " made no change to " + plan.targetService());
                setStatus(exec, ExecuteStatus.NOT_APPLICABLE, execLog);
                exec.setCompletedAt(Instant.now());
                executeRefactorRepository.save(exec);
                return;
            }

            // Step 4 — Git commit
            log.info("[execute] v{} step 4 — committing changes", exec.getVersionNumber());
            int changeCount = countChangedFiles(projectRoot, appTarget.gitPathspec());
            if (changeCount == 0) {
                execLog.append("\n[WARN] No files changed by mutation — nothing to stage.\n");
                log.warn("[execute] v{} step 4 — mutation produced no file changes", exec.getVersionNumber());
            } else {
                // Stage all changes atomically: modifications, new files, deletions, and renames.
                // Per-file git add fails when the containing directory has been deleted by the mutation.
                runGit(execLog, projectRoot, "-C", projectRoot, "add", "-A", "--", appTarget.gitPathspec());
                log.info("[execute] v{} step 4 — staged {} file(s) via git add -A", exec.getVersionNumber(), changeCount);
                runGit(execLog, projectRoot, "-C", projectRoot, "commit",
                        "-m", commitMessage(exec.getVersionNumber(), plan, fulfilledPrerequisite));
                String sha = captureGitSha(projectRoot);
                exec.setGitCommitSha(sha);
                executeRefactorRepository.save(exec);
                log.info("[execute] v{} step 4 — committed sha={}", exec.getVersionNumber(), sha);
            }

            // The branch is deliberately NOT pushed. Publishing a refactoring is a human decision:
            // the team compares versions in the evolution tree and pushes the one it selects.
            log.info("[execute] v{} branch '{}' left local — publishing is a manual team step",
                    exec.getVersionNumber(), exec.getBranchName());
            execLog.append("\n[git] branch '").append(exec.getBranchName())
                   .append("' committed locally; not pushed. Publish manually when the team selects this version.\n");

            buildTestAndDeploy(exec, plan, execLog, projectRoot, branchCheckedOut, stackTornDown);

        } catch (Exception e) {
            if (exec != null) {
                fail(exec, execLog, "Unexpected error: " + e.getMessage());
                log.error("[execute] v{} FAILED — {}", exec.getVersionNumber(), e.getMessage(), e);
                // Catches git and mutator failures — the runs most likely to leave an empty branch.
                recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                        execLog, "Unexpected error: " + e.getMessage());
            } else {
                log.error("[execute] pipeline FAILED before entity load (executeRefactorId={}) — {}", executeRefactorId, e.getMessage(), e);
            }
        }
    }

    /**
     * Steps 5·0–6b: host-port preflight, test gate, runtime image, redeploy and health wait, on the
     * branch already checked out with the refactor committed. Shared by a fresh run (after step 4) and
     * by {@link #resumeFromBuild}, which re-enters here without repeating the mutation. Returns early
     * after failing the run and recovering; unexpected exceptions propagate to the caller's handler.
     */
    private void buildTestAndDeploy(ExecuteRefactorEntity exec, PlanResponse plan, StringBuilder execLog,
                                    String projectRoot, boolean branchCheckedOut,
                                    AtomicBoolean stackTornDown) throws Exception {
        setStatus(exec, ExecuteStatus.BUILDING, execLog);

        // Step 5·0 — Host-port preflight. A port another compose project already publishes fails
        // `compose up` only at step 6, after the test gate and the image build — and after `down` has
        // stopped the running version. Checked here, it costs seconds and touches nothing.
        List<HostPortInspector.PortConflict> portConflicts = hostPortConflicts(exec, execLog);
        if (!portConflicts.isEmpty()) {
            String reason = "host-port preflight: " + portConflicts.stream()
                    .map(HostPortInspector.PortConflict::describe)
                    .collect(Collectors.joining("; "))
                    + " — step 6 would fail; nothing built or deployed. Free the port and resume, or"
                    + " remap the service and start a new run";
            log.warn("[execute] v{} step 5·0 — {}", exec.getVersionNumber(), reason);
            fail(exec, execLog, reason);
            recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot, execLog, reason);
            return;
        }

        // Step 5a — Test gate
        if (config.isSkipTests()) {
            execLog.append("\n[SKIP] Test gates skipped (rt.execute.skip-tests=true).\n");
            log.info("[execute] v{} step 5a — test gates skipped by configuration", exec.getVersionNumber());
        } else if (!"TEST_CHANGE".equalsIgnoreCase(plan.operation())) {
            log.info("[execute] v{} step 5a — running functional test gate (this may take several minutes)", exec.getVersionNumber());
            boolean dedicatedBuilder = ensureTestGateBuilder(projectRoot, execLog);
            if (!dedicatedBuilder) {
                execLog.append("\n[WARN] builder '").append(TEST_GATE_BUILDER)
                       .append("' unavailable — test gate runs on the default builder; output past 2 MiB is clipped.\n");
                log.warn("[execute] v{} step 5a — builder '{}' unavailable; falling back to the default builder",
                        exec.getVersionNumber(), TEST_GATE_BUILDER);
            }
            List<String> testCmd = buildDockerTestCmd(dedicatedBuilder);
            log.debug("[execute] v{} step 5a — command: {}", exec.getVersionNumber(), String.join(" ", testCmd));

            Instant testStart = Instant.now();
            ProcessResult testBuild = runLogged("test-gate", testCmd, projectRoot,
                    BUILDKIT_ENV, config.getTestTimeoutSeconds(), exec.getVersionNumber(), exec, execLog);
            long testSecs = Duration.between(testStart, Instant.now()).toSeconds();
            String testTotals = surefireSummary(testBuild.stdout());

            if (!testBuild.succeeded()) {
                String reason = testBuild.timedOut()
                        ? "Functional test gate timed out after " + config.getTestTimeoutSeconds() + "s"
                        : "Functional test gate failed";
                log.warn("[execute] v{} step 5a — {} in {}s (exit={}{})",
                        exec.getVersionNumber(), reason, testSecs, testBuild.exitCode(),
                        testTotals == null ? "" : ", " + testTotals);
                fail(exec, execLog, reason);
                recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                    execLog, reason);
                return;
            }
            log.info("[execute] v{} step 5a — test gate PASSED in {}s{}", exec.getVersionNumber(), testSecs,
                    testTotals == null ? "" : " (" + testTotals + ")");

            BehavioralPreservationChecker.PreservationReport coverage =
                    preservationChecker.check(plan.operation(), testBuild.combined());
            execLog.append(coverage.toLogBlock());
        } else {
            execLog.append("\n[SKIP] Test gate skipped for test-change operation.\n");
            log.info("[execute] v{} step 5a — test gate skipped (test-change)", exec.getVersionNumber());
        }

        // Step 5b — Runtime image
        String imageTag  = appTarget.versionTag(exec.getVersionNumber());
        String branchTag = appTarget.branchTag(exec.getBranchName());
        log.info("[execute] v{} step 5b — building runtime image '{}' (also tagged '{}')",
                exec.getVersionNumber(), imageTag, branchTag);

        Instant buildStart = Instant.now();
        ProcessResult runtimeBuild = runLogged("image-build",
                buildDockerRuntimeCmd(imageTag, branchTag), projectRoot, BUILDKIT_ENV,
                config.getBuildTimeoutSeconds(), exec.getVersionNumber(), exec, execLog);
        long buildSecs = Duration.between(buildStart, Instant.now()).toSeconds();

        if (!runtimeBuild.succeeded()) {
            String reason = runtimeBuild.timedOut()
                    ? "Runtime image build timed out after " + config.getBuildTimeoutSeconds() + "s"
                    : "Runtime image build failed";
            log.warn("[execute] v{} step 5b — {} in {}s (exit={})",
                    exec.getVersionNumber(), reason, buildSecs, runtimeBuild.exitCode());
            fail(exec, execLog, reason);
            recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                    execLog, reason);
            return;
        }
        log.info("[execute] v{} step 5b — image '{}' built in {}s", exec.getVersionNumber(), imageTag, buildSecs);
        ProcessResult prune = processRunner.run(List.of("docker", "image", "prune", "-f"), projectRoot);
        execLog.append(prune.toLogEntry("docker-image-prune"));
        exec.setImageTag(imageTag);
        executeRefactorRepository.save(exec);

        // Step 6 — Redeploy the rediscovered topology
        setStatus(exec, ExecuteStatus.DEPLOYING, execLog);

        // Resolved only now, because the compose file on disk is the mutated one: a
        // refactoring that renamed, removed or added a microservice (merge, split, create,
        // remove) is picked up here without any configuration change.
        DeployTopology topology = topologyResolver.resolve(
                config.getDeployTarget(), true, this::lastDeployedServices);
        List<String> services = topology.serviceNames();
        recordTopology(exec, topology);

        if (services.isEmpty()) {
            // `docker compose up` with no service arguments starts the ENTIRE project —
            // both monoliths on port 8080 plus every test-* container running `mvn test`.
            fail(exec, execLog, "topology resolution produced no services — refusing to deploy");
            recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                    execLog, "topology resolution produced no services");
            return;
        }

        // The redeploy re-interpolates these from rt-app's env — record them so the log
        // captures exactly which runtime configuration the version was deployed with.
        String deployMode = String.format("VERSION_MODE=%s COMM_LAYER=%s",
                envOrEmpty("VERSION_MODE"), envOrEmpty("COMM_LAYER"));
        log.info("[execute] v{} step 6 — redeploying {} service(s) [{}] from {}: {}",
                exec.getVersionNumber(), services.size(), deployMode, topology.source(), services);
        execLog.append("\n[deploy] ").append(deployMode)
               .append(" source=").append(topology.source())
               .append(" services=").append(services).append("\n");

        if (!redeploy(topology, exec.getVersionNumber(), execLog, stackTornDown)) {
            fail(exec, execLog, "docker compose deploy failed");
            recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                    execLog, "docker compose deploy failed");
            return;
        }

        // Step 6b — Wait for the services to become healthy before declaring the version
        // deployed, so the next Monitor phase does not measure a still-starting system.
        if (!waitForServicesHealthy(topology, exec.getVersionNumber(), execLog)) {
            fail(exec, execLog, "services did not become healthy within "
                    + config.getDeployHealthTimeoutSeconds() + "s");
            recoverToParentBranch(exec, branchCheckedOut, stackTornDown.get(), projectRoot,
                    execLog, "services did not become healthy");
            return;
        }

        exec.setStatus(ExecuteStatus.DEPLOYED);
        exec.setCompletedAt(Instant.now());
        exec.setExecuteLog(execLog.toString());
        executeRefactorRepository.save(exec);
        loopIterationService.markBranchStatus(
                exec.getIterationId(), LoopIterationService.BRANCH_BUILD_SUCCESS);
        loopIterationService.updateFromExecuteStatus(
                exec.getIterationId(), ExecuteStatus.DEPLOYED.name());
        log.info("[execute] v{} DEPLOYED — branch='{}' image='{}' total={}s",
                exec.getVersionNumber(), exec.getBranchName(), imageTag,
                Duration.between(exec.getStartedAt(), exec.getCompletedAt()).toSeconds());
        recordVersionAndFollowUp(exec);
    }

    // ─── Step helpers ────────────────────────────────────────────────────────

    private void applyMutation(ExecuteRefactorEntity exec, PlanResponse plan,
                               StringBuilder execLog) throws Exception {
        applyMutation(exec, plan, execLog, null);
    }

    /**
     * @param retryContext appended to the plan document on a prerequisite retry. Without it the
     *                     retry is byte-identical to the first attempt, so the skill cannot tell
     *                     that the artefacts now in the tree were produced for it moments ago — and
     *                     the create-replica case that delegates its refresh path to {@code
     *                     /new-event} reports NO_CHANGE over work this run had just done.
     */
    private void applyMutation(ExecuteRefactorEntity exec, PlanResponse plan,
                               StringBuilder execLog, String retryContext) throws Exception {
        String skillInvocation = (plan.enrichedSkillInvocation() != null
                && !plan.enrichedSkillInvocation().isBlank())
                ? plan.enrichedSkillInvocation() : plan.skillInvocation();
        if (skillInvocation == null || skillInvocation.isBlank()) {
            log.warn("[execute] v{} step 3 — no skillInvocation in plan, mutation skipped", exec.getVersionNumber());
            execLog.append("\n[SKIP] No skillInvocation — mutation skipped.");
            return;
        }

        if ("test-change".equals(skillInvocation)) {
            String target = appTarget.absoluteClaudeMd(config.getProjectRoot());
            String line = "\n<!-- test-run: " + Instant.now() + " -->\n";
            Files.write(Paths.get(target), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            execLog.append("\n[test-change] Appended timestamp to CLAUDE.md\n");
            log.info("[execute] v{} step 3 — test-change: timestamp written to CLAUDE.md", exec.getVersionNumber());
            return;
        }

        String fullPrompt = (plan.skillPrompt() != null && !plan.skillPrompt().isBlank())
                ? plan.skillPrompt() : skillInvocation;

        // Prefer AI-enriched plan document; fall back to template-generated plan
        String planContext = (plan.enrichedDocument() != null && !plan.enrichedDocument().isBlank())
                ? plan.enrichedDocument()
                : plan.document();
        if (retryContext != null && !retryContext.isBlank()) {
            planContext = (planContext == null ? "" : planContext) + retryContext;
        }

        String appDir = appTarget.absoluteDir(config.getProjectRoot());
        log.info("[execute] v{} step 3 — skill={} appDir={} planContext={}",
                exec.getVersionNumber(), skillInvocation, appDir,
                planContext != null ? (plan.enrichedDocument() != null && !plan.enrichedDocument().isBlank()
                        ? "enriched" : "original") : "none");

        Instant mutStart = Instant.now();
        claudeCommandMutator.apply(skillInvocation, fullPrompt, planContext, appDir, execLog, exec.getVersionNumber());
        long mutSecs = Duration.between(mutStart, Instant.now()).toSeconds();
        log.info("[execute] v{} step 3 — mutation finished in {}s", exec.getVersionNumber(), mutSecs);
    }

    private void logGitContext(int version, String projectRoot, StringBuilder execLog) {
        execLog.append("\n[git-context] projectRoot=").append(projectRoot).append("\n");
        log.info("[execute] v{} step 2 — git context: projectRoot={}", version, projectRoot);

        String[][] diagnostics = {
            { "branch",  "-C", projectRoot, "branch", "--show-current" },
            { "remote",  "-C", projectRoot, "remote", "-v" },
            { "status",  "-C", projectRoot, "status", "--short", "--branch" },
            { "log",     "-C", projectRoot, "log", "--oneline", "-5" },
        };

        for (String[] d : diagnostics) {
            String label = d[0];
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (int i = 1; i < d.length; i++) cmd.add(d[i]);
            try {
                ProcessResult r = processRunner.run(cmd, projectRoot);
                String out = SecretRedactor.redact(r.combined().trim());
                execLog.append("[git-").append(label).append("]\n").append(out).append("\n");
                log.info("[execute] v{} git-{}: {}", version, label, out.replace("\n", " | "));
            } catch (Exception e) {
                execLog.append("[git-").append(label).append(" ERROR] ").append(e.getMessage()).append("\n");
                log.warn("[execute] v{} git-{} failed: {}", version, label, e.getMessage());
            }
        }

        String token = System.getenv("GITHUB_TOKEN");
        String tokenStatus = (token != null && !token.isBlank()) ? "set (" + token.length() + " chars)" : "NOT SET";
        execLog.append("[git-context] GITHUB_TOKEN=").append(tokenStatus).append("\n");
        log.info("[execute] v{} GITHUB_TOKEN={}", version, tokenStatus);
    }

    private void runGit(StringBuilder execLog, String workDir, String... args) throws Exception {
        runGit(execLog, workDir, Map.of(), args);
    }

    private void runGit(StringBuilder execLog, String workDir, Map<String, String> env, String... args)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));

        log.debug("[execute] git {}", SecretRedactor.redact(String.join(" ", cmd.subList(1, cmd.size()))));
        ProcessResult result = processRunner.run(cmd, workDir, env);
        execLog.append(SecretRedactor.redact(result.toLogEntry("git " + args[args.length - 1])));

        if (!result.succeeded()) {
            log.warn("[execute] git {} failed (exit={}): {}", args[args.length - 1], result.exitCode(),
                    SecretRedactor.redact(result.stderr()));
            throw new RuntimeException("git " + args[args.length - 1] + " failed:\n"
                    + SecretRedactor.redact(result.combined()));
        }
    }

    /**
     * Authenticates one git invocation against github.com with {@code GITHUB_TOKEN}, through
     * git's environment-only config ({@code GIT_CONFIG_COUNT}/{@code KEY}/{@code VALUE}). The
     * token is never written to a gitconfig, never appears in argv, and is not inherited by
     * later processes such as the Claude mutator. Empty when the token is not set.
     */
    private static Map<String, String> githubAuthEnv() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) return Map.of();
        String basic = Base64.getEncoder()
                .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader",
                "GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basic);
    }

    /**
     * Removes {@code url.<token-url>.insteadOf} rewrites that earlier versions wrote to the
     * global gitconfig. They leaked the token into {@code git remote -v} output and into every
     * git process started afterwards. Best effort: failures are only logged.
     */
    private void removeTokenInsteadOfRewrites(String projectRoot) {
        try {
            ProcessResult list = processRunner.run(List.of("git", "config", "--global", "--name-only",
                    "--get-regexp", "^url\\..*\\.insteadof$"), projectRoot);
            for (String key : list.stdout().lines().map(String::trim).toList()) {
                if (!key.contains("@github.com")) continue;
                processRunner.run(List.of("git", "config", "--global", "--unset-all", key), projectRoot);
                log.info("[execute] removed token-bearing git insteadOf rewrite from global gitconfig");
            }
        } catch (Exception e) {
            log.warn("[execute] could not clean global gitconfig insteadOf rewrites: {}",
                    SecretRedactor.redact(e.getMessage()));
        }
    }

    private int countChangedFiles(String projectRoot, String... pathSpecs) {
        try {
            // git status --porcelain sees modifications, deletions (even in removed dirs),
            // untracked new files, and renames — unlike git diff HEAD which misses new files.
            List<String> cmd = new ArrayList<>(List.of(
                    "git", "-C", projectRoot,
                    "status", "--porcelain", "--"));
            cmd.addAll(List.of(pathSpecs));
            ProcessResult r = processRunner.run(cmd, projectRoot);
            if (!r.succeeded() || r.stdout().isBlank()) return 0;
            return (int) r.stdout().lines().filter(l -> !l.isBlank()).count();
        } catch (Exception e) {
            log.warn("[execute] countChangedFiles failed: {}", e.getMessage());
            return 0;
        }
    }

    private String captureGitSha(String projectRoot) {
        try {
            ProcessResult r = processRunner.run(
                    List.of("git", "-C", projectRoot, "rev-parse", "HEAD"), projectRoot);
            return r.succeeded() ? r.stdout().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Restores the system after a failed run: marks the branch BUILD_FAILED, returns the working
     * tree to the parent branch, and — only when the runtime was actually disturbed — brings the
     * parent's stack back up.
     *
     * <p>The parent branch, not the configured base branch: the base may be many versions behind,
     * whereas the parent is the last code that deployed successfully.
     *
     * <p>Docker is touched only when step 6's {@code down} already ran. A failure at the mutation,
     * test-gate or image-build stage never stopped a container — the parent's services are still
     * serving the parent's code, and a container's image reference is fixed at creation, so a
     * the app's :latest tag left pointing at the failed build cannot affect them. It is also
     * self-correcting: every run rebuilds that tag at step 5b before deploying. The one consumer
     * that would otherwise read the poisoned tag is this recovery's own {@code up --no-build},
     * which is exactly why the rebuild lives inside the torn-down branch and nowhere else.
     *
     * <p>Branch disposal is unchanged: step 2 resets the branch with {@code checkout -B}, so a null
     * commit SHA means it holds nothing and is deleted; a branch carrying a commit is kept for
     * inspection.
     */
    /**
     * Records the CodeVersion this run produced and binds a fresh iteration to it, so the next
     * Monitor run measures the code that was just deployed.
     *
     * <p>Without the follow-up iteration the new version has no snapshot, and
     * {@code VersionEvolutionService} derives a version's quality scores solely from its
     * iterations' snapshots — so the version would stay unscored and invisible in the evolution
     * tree until someone bound an iteration to it by hand.
     *
     * <p>The new iteration needs no branch fields: {@code ensureBranch} derives its parent lazily
     * from the code version's branch, which is the branch this run just deployed.
     *
     * <p>Failures here are logged and swallowed — the deploy succeeded, and bookkeeping must never
     * turn a good deploy into a reported failure.
     */
    // Package-private so the guard conditions can be asserted directly, as with runPipeline.
    void recordVersionAndFollowUp(ExecuteRefactorEntity exec) {
        try {
            CodeVersionService.CodeVersionSummary newVersion =
                    codeVersionService.createFromExecution(exec);

            // Guarded twice: re-running the same row must not fork a second iteration, and runs
            // outside an iteration (test runs, direct API calls) must not accumulate orphan ones.
            if (exec.getIterationId() == null || exec.getNextIterationId() != null) return;

            LoopIterationService.LoopIterationSummary next =
                    loopIterationService.create(null, newVersion.getVersionId());
            exec.setNextIterationId(next.getIterationId());
            executeRefactorRepository.save(exec);
            log.info("[execute] v{} → follow-up iteration {} ('{}') bound to version {}",
                    exec.getVersionNumber(), next.getIterationId(), next.getName(),
                    newVersion.getVersionLabel());
        } catch (Exception e) {
            log.warn("[execute] v{} follow-up version/iteration creation failed (non-fatal): {}",
                    exec.getVersionNumber(), e.getMessage());
        }
    }

    // Package-private so the recovery decision table can be asserted directly, as with runPipeline.
    void recoverToParentBranch(ExecuteRefactorEntity exec, boolean branchCheckedOut,
                               boolean stackTornDown, String projectRoot,
                               StringBuilder execLog, String reason) {
        String parentBranch = exec.getParentBranchName() != null && !exec.getParentBranchName().isBlank()
                ? exec.getParentBranchName()
                : config.getBaseBranch();

        try {
            loopIterationService.markBranchStatus(
                    exec.getIterationId(), LoopIterationService.BRANCH_BUILD_FAILED);
            loopIterationService.recordBuildFailure(exec.getIterationId(), reason);

            boolean refactorLanded = exec.getGitCommitSha() != null;

            // Untracked files only survive when the commit was skipped — `git add -A` stages new
            // files too, so a landed refactor leaves nothing loose behind.
            if (branchCheckedOut && !refactorLanded) {
                ProcessResult clean = processRunner.run(
                        List.of("git", "-C", projectRoot, "clean", "-fd", "--", appTarget.gitPathspec()),
                        projectRoot);
                execLog.append(clean.toLogEntry("git-clean-mutation"));
            }

            // --force discards the mutation's uncommitted edits; a plain checkout fails on a dirty tree.
            ProcessResult back = processRunner.run(
                    List.of("git", "-C", projectRoot, "checkout", "--force", parentBranch), projectRoot);
            execLog.append(back.toLogEntry("git-recover-parent"));
            topologyResolver.invalidate();
            log.info("[execute] v{} recovery — returned to parent branch '{}'",
                    exec.getVersionNumber(), parentBranch);

            if (stackTornDown && back.succeeded()) {
                restoreParentDeployment(exec, projectRoot, execLog, parentBranch);
            } else if (!stackTornDown) {
                execLog.append("\n[recovery] runtime untouched — parent's services keep running; "
                             + "no rebuild or redeploy needed.\n");
            }

            // git refuses to delete the branch that is currently checked out.
            if (!branchCheckedOut || !back.succeeded()) return;

            if (refactorLanded) {
                execLog.append("\n[git] branch '").append(exec.getBranchName())
                       .append("' kept — it carries commit ").append(exec.getGitCommitSha())
                       .append("; inspect it before discarding.\n");
                log.info("[execute] v{} kept branch '{}' — carries commit {}",
                        exec.getVersionNumber(), exec.getBranchName(), exec.getGitCommitSha());
                return;
            }

            ProcessResult del = processRunner.run(
                    List.of("git", "-C", projectRoot, "branch", "-D", exec.getBranchName()), projectRoot);
            execLog.append(del.toLogEntry("git-branch-delete"));
            log.info("[execute] v{} deleted empty branch '{}' — no refactoring was committed",
                    exec.getVersionNumber(), exec.getBranchName());
        } catch (Exception e) {
            execLog.append("\n[RECOVERY ERROR] ").append(e.getMessage());
            log.error("[execute] v{} recovery to '{}' failed: {}",
                    exec.getVersionNumber(), parentBranch, e.getMessage(), e);
        } finally {
            exec.setExecuteLog(execLog.toString());
            executeRefactorRepository.save(exec);
        }
    }

    /**
     * Rebuilds the parent branch's image and redeploys its topology. Called only when the stack was
     * already torn down, so the cost of a cold start is unavoidable rather than self-inflicted.
     */
    private void restoreParentDeployment(ExecuteRefactorEntity exec, String projectRoot,
                                         StringBuilder execLog, String parentBranch)
            throws IOException, InterruptedException {
        log.info("[execute] v{} recovery — rebuilding and redeploying parent branch '{}'",
                exec.getVersionNumber(), parentBranch);
        execLog.append("\n[recovery] stack was torn down — rebuilding '").append(parentBranch)
               .append("' and redeploying it.\n");

        // Mandatory: the :latest tag currently points at the failed build, and the redeploy below
        // runs --no-build. Without this the parent's topology would come back up on failed code.
        ProcessResult rebuild = runLogged("image-build:recovery",
                buildDockerRuntimeCmd(appTarget.latestTag(), appTarget.latestTag()), projectRoot, BUILDKIT_ENV,
                config.getBuildTimeoutSeconds(), exec.getVersionNumber(), exec, execLog);
        if (!rebuild.succeeded()) {
            execLog.append("\n[RECOVERY ERROR] parent rebuild failed — the stack stays down and "
                         + appTarget.latestTag() + " is NOT trustworthy. Rebuild manually before deploying.\n");
            log.error("[execute] v{} recovery rebuild FAILED (exit={}) — stack left down",
                    exec.getVersionNumber(), rebuild.exitCode());
            return;
        }

        DeployTopology topology = topologyResolver.resolve(
                config.getDeployTarget(), true, this::lastDeployedServices);
        if (topology.serviceNames().isEmpty()) {
            execLog.append("\n[RECOVERY ERROR] parent topology resolved to no services — "
                         + "refusing to deploy.\n");
            return;
        }

        AtomicBoolean ignored = new AtomicBoolean(false);
        if (redeploy(topology, exec.getVersionNumber(), execLog, ignored)
                && waitForServicesHealthy(topology, exec.getVersionNumber(), execLog)) {
            execLog.append("\n[recovery] parent branch '").append(parentBranch)
                   .append("' redeployed and healthy: ").append(topology.serviceNames()).append("\n");
            log.info("[execute] v{} recovery — parent '{}' healthy on {}",
                    exec.getVersionNumber(), parentBranch, topology.serviceNames());
        } else {
            execLog.append("\n[RECOVERY WARN] parent branch did not come back healthy.\n");
            log.warn("[execute] v{} recovery — parent '{}' did not come back healthy",
                    exec.getVersionNumber(), parentBranch);
        }
    }

    /**
     * The BuildKit builder step 5a runs on — the one `test-gate` (rt-app/docker/test-gate.sh, its
     * {@code BUILDER}) creates, so the name and driver options must match that script. The default
     * builder clips each step's output at 2 MiB ("[output clipped, log limit 2MiB reached]"), which
     * on a full suite run drops the rest of the tests and surefire's report from the execute log and
     * from what {@link BehavioralPreservationChecker} reads. Sharing it with the mutator's last check
     * also shares its cache, so the base stage is already built when the gate runs.
     */
    static final String TEST_GATE_BUILDER = "rt-test-gate";

    /**
     * Makes sure {@link #TEST_GATE_BUILDER} exists, creating it as test-gate.sh does. Its config lives
     * in the client (this container), so after an rt-app redeploy it is recreated here and re-attaches
     * to the builder container that is still running. Returns false when it cannot be had; the gate
     * then runs on the default builder, which only loses log output, never the verdict.
     */
    private boolean ensureTestGateBuilder(String projectRoot, StringBuilder execLog) throws InterruptedException {
        try {
            if (processRunner.run(List.of("docker", "buildx", "inspect", TEST_GATE_BUILDER), projectRoot).succeeded()) {
                return true;
            }
            ProcessResult created = processRunner.run(List.of(
                    "docker", "buildx", "create", "--name", TEST_GATE_BUILDER, "--driver", "docker-container",
                    "--driver-opt", "env.BUILDKIT_STEP_LOG_MAX_SIZE=-1",
                    "--driver-opt", "env.BUILDKIT_STEP_LOG_MAX_SPEED=-1"), projectRoot);
            execLog.append(created.toLogEntry("docker-buildx-create"));
            return created.succeeded();
        } catch (IOException e) {
            log.warn("[execute] could not set up builder '{}': {}", TEST_GATE_BUILDER, e.getMessage());
            return false;
        }
    }

    /**
     * Host ports step 6 would fail to bind: published by a container outside the application's compose
     * project, or claimed twice within it. Checked for exactly what step 6 starts — the topology the
     * compose file on disk now describes, plus the always-up services.
     *
     * <p>Never blocks a run on its own failure: if docker cannot be asked, the run goes on as it did
     * before this check existed, and step 6 remains the backstop.
     */
    private List<HostPortInspector.PortConflict> hostPortConflicts(ExecuteRefactorEntity exec,
                                                                   StringBuilder execLog) {
        try {
            DeployTopology planned = topologyResolver.resolve(
                    config.getDeployTarget(), true, this::lastDeployedServices);
            List<String> roots = DeployCommandRenderer.upServices(
                    topologyConfig.getAlwaysUpList(), planned.serviceNames());
            List<HostPortInspector.PortConflict> conflicts = hostPortInspector.findConflicts(roots);
            execLog.append("\n[preflight] host ports: ")
                   .append(conflicts.isEmpty() ? "no conflict" : conflicts.size() + " conflict(s)")
                   .append(" for ").append(roots).append("\n");
            return conflicts;
        } catch (Exception e) {
            log.warn("[execute] v{} step 5·0 — host-port preflight skipped: {}",
                    exec.getVersionNumber(), e.getMessage());
            execLog.append("\n[WARN] host-port preflight skipped — ").append(e.getMessage()).append("\n");
            return List.of();
        }
    }

    private List<String> buildDockerTestCmd(boolean dedicatedBuilder) {
        return buildDockerTestCmdForProfile(config.getBuildProfiles(), dedicatedBuilder);
    }

    private List<String> buildDockerTestCmdForProfile(String profile, boolean dedicatedBuilder) {
        List<String> cmd = new ArrayList<>(dedicatedBuilder
                ? List.of("docker", "buildx", "build", "--builder", TEST_GATE_BUILDER)
                : List.of("docker", "build"));
        cmd.addAll(List.of(
                "--progress=plain",
                "--target", "test",
                // The Claude mutator runs this same build as its last step (`test-gate`, from
                // rt-app/docker/test-gate.sh). Without this, a context identical to one already
                // green would be served from cache and the gate would not re-run the suite.
                "--no-cache-filter", "test",
                "--build-arg", "PROFILE=" + profile,
                "-f", appTarget.absoluteDockerfile(config.getProjectRoot()),
                config.getProjectRoot()
        ));
        return cmd;
    }

    /**
     * Builds the runtime image the same way {@code docker compose build} does: no {@code --target},
     * so Docker builds the Dockerfile's last stage.
     *
     * <p>Deliberately not {@code --target final}. Each run checks out the branch start point — the
     * parent version's commit — so the Dockerfile it builds is whatever that commit contained, not
     * whatever is on the base branch. Depending on a stage *name* therefore breaks against any
     * historical commit where the final stage was unnamed, and no fix to the base branch can reach
     * those runs. Building the last stage works on every commit.
     */
    /**
     * Forces BuildKit for image builds. The daemon keeps images in the containerd store, which the
     * legacy builder cannot restore cached layers from — {@code --cache-from} below fails it with
     * "failed to read diff archive: InvalidArgument" long after Maven has reported BUILD SUCCESS,
     * which reads like a code failure and is not one. ProcessRunner merges this into the inherited
     * environment rather than replacing it.
     */
    private static final Map<String, String> BUILDKIT_ENV = Map.of("DOCKER_BUILDKIT", "1");

    private List<String> buildDockerRuntimeCmd(String imageTag, String branchTag) {
        return List.of(
                "docker", "build", "--progress=plain",
                "--cache-from", appTarget.latestTag(),
                "--build-arg", "PROFILE=" + config.getBuildProfiles(),
                "-t", imageTag,
                "-t", branchTag,
                "-t", appTarget.latestTag(),
                "-f", appTarget.absoluteDockerfile(config.getProjectRoot()),
                config.getProjectRoot()
        );
    }

    /**
     * Resolves the CodeVersion this run branches from — the version the iteration is refactoring.
     *
     * <p>Falls back to the current ACTIVE version, then the baseline, so runs started outside an
     * iteration (test runs, direct API calls) still record a parent.
     */
    private String resolveSourceVersionId(String iterationId) {
        if (iterationId != null && !iterationId.isBlank()) {
            String fromIteration = loopIterationRepository.findById(iterationId)
                    .map(LoopIterationEntity::getCodeVersionId)
                    .orElse(null);
            if (fromIteration != null) {
                return fromIteration;
            }
            log.warn("[execute] iteration {} has no codeVersionId — falling back to ACTIVE version", iterationId);
        }
        return codeVersionService.findActiveOrBaseline()
                .map(CodeVersionEntity::getVersionId)
                .orElse(null);
    }

    /**
     * Git start point for the new branch.
     *
     * <p>A refactoring version is a <b>commit</b>: v4 branches from v3's exact commit, which is what
     * makes the evolution tree reproducible, so its stored SHA is used verbatim.
     *
     * <p>The <b>baseline is a branch</b>, not a commit — it names wherever {@code base-branch}
     * currently points. Storing a SHA for it can never stay current: updating the stored value is
     * itself a commit, so the record is always at least one behind. Its tip is therefore resolved
     * here, at run time, and any SHA a seeded or legacy row happens to carry is deliberately
     * ignored rather than merely absent, so an existing database is corrected without a wipe.
     */
    String resolveBranchStartPoint(ExecuteRefactorEntity exec, String projectRoot, StringBuilder execLog) {
        CodeVersionEntity parent = exec.getSourceVersionId() == null ? null
                : codeVersionService.findById(exec.getSourceVersionId()).orElse(null);

        boolean baseline = parent == null
                || parent.getVersionNumber() == 0
                || CodeVersionEntity.BASELINE.equals(parent.getStatus());

        if (!baseline) {
            String parentSha = parent.getGitCommitSha();
            if (parentSha != null && !parentSha.isBlank()) return parentSha;
        }

        String parentBranch = exec.getParentBranchName() != null && !exec.getParentBranchName().isBlank()
                ? exec.getParentBranchName()
                : config.getBaseBranch();
        return resolveBranchTip(parentBranch, projectRoot, execLog);
    }

    /**
     * The commit a branch currently points at.
     *
     * <p>The <b>local</b> ref is asked first, and that ordering is the point of the method: Execute
     * never pushes a refactor branch ({@code step 4}), so {@code origin/} lags every commit made
     * here — including the ones on the base branch that a run is meant to build on. Asking the
     * remote first would keep the staleness this method exists to remove.
     *
     * <p>Falls back to {@code origin/<branch>} for a fresh clone that has only remote-tracking refs,
     * and finally to the bare name so {@code checkout -B} resolves it or fails loudly, rather than
     * this method inventing a start point.
     */
    private String resolveBranchTip(String branch, String projectRoot, StringBuilder execLog) {
        for (String ref : List.of(branch, "origin/" + branch)) {
            try {
                ProcessResult r = processRunner.run(
                        List.of("git", "-C", projectRoot, "rev-parse", "--verify", ref), projectRoot);
                String sha = r.succeeded() ? r.stdout().trim() : "";
                if (!sha.isEmpty()) {
                    execLog.append("\n[git] start point: ").append(ref).append(" → ").append(sha).append("\n");
                    log.info("[execute] start point resolved from '{}' → {}", ref, sha);
                    return sha;
                }
            } catch (Exception e) {
                log.debug("[execute] could not resolve '{}': {}", ref, e.getMessage());
            }
        }
        execLog.append("\n[WARN] neither '").append(branch).append("' nor 'origin/").append(branch)
               .append("' resolved; letting git resolve the bare name\n");
        log.warn("[execute] no ref resolved for '{}' — passing the bare name to checkout", branch);
        return branch;
    }

    /**
     * Tears the project down and brings the resolved topology back up.
     *
     * <p>A full teardown rather than a per-service hot-swap, because the service set itself can
     * change: after a merge the old services are no longer defined in the compose file (so
     * {@code docker compose rm} cannot name them), and after a split the new ones need their
     * databases created. {@code down} + {@code up} handles merge, split, create and remove with
     * the same two commands, at the cost of a cold start.
     *
     * <p>A failed {@code up} is retried once, on fresh containers, when it was an infrastructure
     * dependency that failed to start (see {@link #isInfraDependencyFailure}). Diagnostics are
     * captured after every failed {@code up}, before the next teardown deletes the evidence.
     *
     * @return true when teardown and {@code up} succeeded
     */
    private boolean redeploy(DeployTopology topology, int versionNumber, StringBuilder execLog,
                             AtomicBoolean stackTornDown)
            throws IOException, InterruptedException {
        String projectRoot = config.getProjectRoot();

        // `gateway` runs the simulator image, so it is never discovered — but --no-build means
        // compose will not build it either. Fail with a clear message instead of a compose error.
        String gatewayImage = appTarget.getGatewayImage();
        if (!imageExists(gatewayImage, projectRoot)) {
            execLog.append("\n[deploy] missing image ").append(gatewayImage)
                   .append(" — build the simulator image before deploying\n");
            log.error("[execute] v{} step 6 — image '{}' not found; --no-build cannot create it",
                    versionNumber, gatewayImage);
            return false;
        }

        // From here the runtime is disturbed, so a failure must restore the parent branch's stack.
        stackTornDown.set(true);
        for (int attempt = 1; ; attempt++) {
            if (!tearDown(versionNumber, execLog)) return false;

            ProcessResult up = runLogged("compose-up", buildComposeUp(topology.serviceNames()),
                    projectRoot, Map.of(), config.getBuildTimeoutSeconds(), versionNumber, null, execLog);
            if (up.succeeded()) {
                execLog.append("\n[deploy] cold start: infrastructure was recreated, so the health wait "
                             + "below covers full service startup, not just a restart.\n");
                return true;
            }
            log.warn("[execute] v{} step 6 — compose up FAILED (exit={}, attempt {}/{})",
                    versionNumber, up.exitCode(), attempt, DEPLOY_ATTEMPTS);
            // Before the next teardown, which deletes the failed containers and their logs.
            captureDeployDiagnostics(List.of(), versionNumber, execLog);

            // A database whose init hung (e.g. pg_ctl rejecting its own server after a VM clock
            // step) is transient: fresh containers get fresh volumes and init again. Anything else
            // — a taken port, a missing image — fails the same way twice, so it is not retried.
            if (attempt >= DEPLOY_ATTEMPTS || !isInfraDependencyFailure(up.stdout())) return false;
            log.warn("[execute] v{} step 6 — an infrastructure dependency failed to start; retrying "
                     + "on fresh containers", versionNumber);
            execLog.append("\n[deploy] an infrastructure dependency failed to start — retrying the "
                         + "deploy once on fresh containers.\n");
        }
    }

    /** Compose up attempts per redeploy: the first, plus one retry for a transient infra failure. */
    private static final int DEPLOY_ATTEMPTS = 2;

    /**
     * Stops and removes the whole project. {@code rm -v} goes first because it is the only way to
     * delete the per-service databases' anonymous volumes: {@code down} leaves them behind (about
     * ten per deploy), and its own {@code -v} would also delete the named volumes m2_cache (a full
     * Maven re-download) and rabbitmq_data. The databases come back empty either way, which is fine
     * because every service runs with ddl-auto: create.
     *
     * <p>{@code --remove-orphans} is required: after a merge the containers of the dissolved
     * services are no longer backed by any compose service, and a plain {@code down} would leave
     * them running and holding their ports. Their anonymous volumes are not reached by {@code rm}.
     *
     * @return false only when {@code down} fails; a failed volume cleanup is just a leak
     */
    private boolean tearDown(int versionNumber, StringBuilder execLog) throws IOException, InterruptedException {
        String projectRoot = config.getProjectRoot();
        log.info("[execute] v{} step 6 — tearing down the project before redeploy", versionNumber);
        ProcessResult rm = runLogged("compose-rm", List.of(
                "docker", "compose", "-f", config.getComposeFile(), "rm", "--stop", "--force", "-v"),
                projectRoot, Map.of(), config.getBuildTimeoutSeconds(), versionNumber, null, execLog);
        if (!rm.succeeded()) {
            log.warn("[execute] v{} step 6 — compose rm FAILED (exit={}); anonymous volumes may leak",
                    versionNumber, rm.exitCode());
            execLog.append("\n[WARN] compose rm failed — continuing with down; anonymous volumes may leak.\n");
        }
        ProcessResult down = runLogged("compose-down", List.of(
                "docker", "compose", "-f", config.getComposeFile(), "down", "--remove-orphans"),
                projectRoot, Map.of(), config.getBuildTimeoutSeconds(), versionNumber, null, execLog);
        if (!down.succeeded()) {
            log.warn("[execute] v{} step 6 — compose down FAILED (exit={})", versionNumber, down.exitCode());
            return false;
        }
        return true;
    }

    /**
     * True when {@code compose up} failed because a dependency never became healthy. Nothing in the
     * project depends on an application service, so this always names infrastructure (a database,
     * eureka, rabbitmq). The message is on stderr, which {@code runStreaming} merges into stdout.
     */
    static boolean isInfraDependencyFailure(String composeOutput) {
        return composeOutput != null && composeOutput.contains("dependency failed to start");
    }

    /**
     * The deploy command. No {@code --force-recreate} or {@code --no-deps}: everything was just
     * destroyed by {@code down}, and the per-service databases plus eureka/rabbitmq/jaeger must be
     * pulled in as dependencies. {@code --no-build} is kept because step 5b already built the
     * image and {@code pull_policy: build} would otherwise trigger a rebuild.
     */
    private List<String> buildComposeUp(List<String> services) {
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "compose",
                "-f", config.getComposeFile(),
                "up", "-d", "--no-build"
        ));
        // Services that are never discovered but are required for a usable stack (the gateway
        // runs a different image). Shared with the per-version deploy command shown in the UI.
        cmd.addAll(DeployCommandRenderer.upServices(topologyConfig.getAlwaysUpList(), services));
        return cmd;
    }

    private boolean imageExists(String tag, String projectRoot) throws IOException, InterruptedException {
        return processRunner.run(List.of("docker", "image", "inspect", tag), projectRoot).succeeded();
    }

    /** Persists the topology this version deployed, before the deploy runs, so a crash still records it. */
    private void recordTopology(ExecuteRefactorEntity exec, DeployTopology topology) {
        try {
            exec.setDeployedServices(objectMapper.writeValueAsString(topology.serviceNames()));
            exec.setDeployedTopology(objectMapper.writeValueAsString(topology));
            executeRefactorRepository.save(exec);
        } catch (Exception e) {
            log.warn("[execute] v{} could not persist deployed topology (non-fatal): {}",
                    exec.getVersionNumber(), e.getMessage());
        }
    }

    /**
     * Service names of the most recent successful deploy, used only as a fallback when compose
     * discovery fails. Returns empty when nothing has ever deployed.
     */
    private List<String> lastDeployedServices() {
        return executeRefactorRepository.findTopByStatusOrderByVersionNumberDesc(ExecuteStatus.DEPLOYED)
                .map(ExecuteRefactorEntity::getDeployedServices)
                .map(this::readServiceNames)
                .orElseGet(List::of);
    }

    private List<String> readServiceNames(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[execute] could not parse persisted deployed_services: {}", e.getMessage());
            return List.of();
        }
    }

    private String envOrEmpty(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    /** A row of {@code docker compose ps --format json}. */
    private record ComposeStatus(String state, String health) {}

    /**
     * Polls {@code docker compose ps} until every deployed service is ready, or the configured
     * timeout elapses. A freshly created service reports {@code starting} for its healthcheck
     * {@code start_period}; declaring the version deployed before then would let the next Monitor
     * phase measure a still-booting system.
     *
     * <p>Readiness is {@code healthy} for services that define a healthcheck and {@code running}
     * for those that do not — the monolith targets have none and could otherwise never pass.
     *
     * @return true when all services are ready, false on timeout
     */
    private boolean waitForServicesHealthy(DeployTopology topology, int versionNumber, StringBuilder execLog)
            throws IOException, InterruptedException {
        List<String> services = topology.serviceNames();
        long timeoutMs = config.getDeployHealthTimeoutSeconds() * 1000L;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        long pollMs = TimingConstants.HEALTH_POLL_INTERVAL_MS;
        String projectRoot = config.getProjectRoot();

        List<String> cmd = new ArrayList<>(List.of(
                "docker", "compose", "-f", config.getComposeFile(), "ps", "--format", "json"));
        cmd.addAll(services);

        boolean stderrLogged = false;
        while (true) {
            ProcessResult ps = processRunner.run(cmd, projectRoot);
            // `docker compose ps` reports an unknown service name on stderr and prints nothing to
            // stdout. Without this, such a run looks identical to "nothing healthy yet" and burns
            // the entire timeout before failing with no explanation.
            if (!ps.stderr().isBlank() && !stderrLogged) {
                log.warn("[execute] v{} step 6b — compose ps stderr: {}", versionNumber, ps.stderr());
                execLog.append("\n[deploy] compose ps stderr: ").append(ps.stderr()).append("\n");
                stderrLogged = true;
            }
            Map<String, ComposeStatus> statuses = parseComposeStatuses(ps.stdout());

            List<String> notReady = topology.services().stream()
                    .filter(s -> !isReady(s, statuses.get(s.composeService())))
                    .map(DeployService::composeService)
                    .toList();

            if (notReady.isEmpty()) {
                log.info("[execute] v{} step 6b — all {} service(s) ready", versionNumber, services.size());
                execLog.append("\n[deploy] all services ready: ").append(services).append("\n");
                return true;
            }

            if (System.nanoTime() >= deadline) {
                log.warn("[execute] v{} step 6b — health timeout; not ready: {}", versionNumber, notReady);
                execLog.append("\n[deploy] health timeout after ")
                        .append(config.getDeployHealthTimeoutSeconds()).append("s; not ready: ")
                        .append(notReady).append("\n");
                // The caller's recovery tears the stack down next, taking the logs with it.
                captureDeployDiagnostics(notReady, versionNumber, execLog);
                return false;
            }

            log.info("[execute] v{} step 6b — waiting for {} service(s): {}",
                    versionNumber, notReady.size(), notReady);
            Thread.sleep(pollMs);
        }
    }

    private boolean isReady(DeployService service, ComposeStatus status) {
        if (status == null) return false;
        return service.hasHealthcheck()
                ? "healthy".equalsIgnoreCase(status.health())
                : "running".equalsIgnoreCase(status.state());
    }

    /**
     * Parses {@code docker compose ps --format json} into service name → state and health.
     * Compose emits either a JSON array or one JSON object per line depending on version, so both
     * shapes are handled.
     */
    private Map<String, ComposeStatus> parseComposeStatuses(String json) {
        Map<String, ComposeStatus> byService = new java.util.HashMap<>();
        if (json == null || json.isBlank()) return byService;
        try {
            String trimmed = json.trim();
            List<com.fasterxml.jackson.databind.JsonNode> rows = new ArrayList<>();
            if (trimmed.startsWith("[")) {
                objectMapper.readTree(trimmed).forEach(rows::add);
            } else {
                for (String line : trimmed.split("\\R")) {
                    if (!line.isBlank()) rows.add(objectMapper.readTree(line));
                }
            }
            for (com.fasterxml.jackson.databind.JsonNode row : rows) {
                String name = row.path("Service").asText(null);
                if (name == null || name.isBlank()) continue;
                byService.put(name, new ComposeStatus(row.path("State").asText(""),
                                                      row.path("Health").asText("")));
            }
        } catch (Exception e) {
            // Unparseable output (e.g. a transient compose error) leaves the map empty, so every
            // service reads as not-yet-ready and the caller simply polls again.
            log.debug("[execute] could not parse compose ps output: {}", e.getMessage());
        }
        return byService;
    }

    /** Lines of each suspect container's log kept by {@link #captureDeployDiagnostics}. */
    private static final int DIAG_LOG_TAIL = 80;
    private static final int DIAG_TIMEOUT_SECONDS = 60;

    /**
     * Writes what a failed deploy looked like into the execute log: every container of the project
     * that is not running or not healthy — including databases, which the topology does not list —
     * and the tail of their logs. Must run before the next teardown, which deletes all of it.
     * Best effort: a failure here is logged and never changes the deploy's outcome.
     *
     * @param suspects services already known to be failing (e.g. the not-ready ones at a timeout)
     */
    private void captureDeployDiagnostics(Collection<String> suspects, int versionNumber,
                                          StringBuilder execLog) throws InterruptedException {
        String projectRoot = config.getProjectRoot();
        try {
            ProcessResult ps = processRunner.run(List.of(
                    "docker", "compose", "-f", config.getComposeFile(), "ps", "--all", "--format", "json"),
                    projectRoot);
            Set<String> failing = new LinkedHashSet<>(suspects);
            List<String> states = new ArrayList<>();
            parseComposeStatuses(ps.stdout()).forEach((service, status) -> {
                boolean down = !"running".equalsIgnoreCase(status.state());
                boolean sick = "unhealthy".equalsIgnoreCase(status.health())
                            || "starting".equalsIgnoreCase(status.health());
                if (down || sick || failing.contains(service)) {
                    failing.add(service);
                    states.add(service + "(" + status.state()
                            + (status.health().isBlank() ? "" : "/" + status.health()) + ")");
                }
            });
            log.warn("[execute] v{} diag — not running/healthy: {}", versionNumber, states);
            execLog.append("\n[diag] not running/healthy: ").append(states).append("\n");
            if (failing.isEmpty()) return;

            List<String> logs = new ArrayList<>(List.of(
                    "docker", "compose", "-f", config.getComposeFile(), "logs",
                    "--no-color", "--timestamps", "--tail", String.valueOf(DIAG_LOG_TAIL)));
            logs.addAll(failing);
            runLogged("diag-logs", logs, projectRoot, Map.of(), DIAG_TIMEOUT_SECONDS,
                    versionNumber, null, execLog);
        } catch (IOException e) {
            log.warn("[execute] v{} diag — could not capture deploy diagnostics: {}",
                    versionNumber, e.getMessage());
            execLog.append("\n[diag] could not capture diagnostics: ").append(e.getMessage()).append("\n");
        }
    }

    /** Minimum gap between execute-log saves while a streamed process is running. */
    private static final Duration LOG_FLUSH_INTERVAL = Duration.ofSeconds(5);

    private static final Pattern SUREFIRE_TOTALS = Pattern.compile(
            "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)(?![\\d,])");

    /**
     * Runs a long process (docker build, compose down/up) with its output streamed line by line to
     * the application log and the execute log, under {@code [tag]}. When {@code exec} is given the
     * execute log is saved every {@link #LOG_FLUSH_INTERVAL}, so the frontend's status polling shows
     * progress during a build instead of nothing until it returns. The full output is still in the
     * result for callers that parse it.
     */
    private ProcessResult runLogged(String tag, List<String> command, String workingDir,
                                    Map<String, String> env, int timeoutSeconds, int versionNumber,
                                    ExecuteRefactorEntity exec, StringBuilder execLog)
            throws IOException, InterruptedException {
        execLog.append("\n=== ").append(tag).append(" ===\n");
        long[] lastFlush = {System.nanoTime()};
        ProcessResult result = processRunner.runStreaming(command, workingDir, env,
                Duration.ofSeconds(timeoutSeconds), rawLine -> {
                    String line = SecretRedactor.redact(rawLine);
                    execLog.append('[').append(tag).append("] ").append(line).append('\n');
                    log.info("[{}] v{} {}", tag, versionNumber, line);
                    if (exec != null && System.nanoTime() - lastFlush[0] >= LOG_FLUSH_INTERVAL.toNanos()) {
                        exec.setExecuteLog(execLog.toString());
                        executeRefactorRepository.save(exec);
                        lastFlush[0] = System.nanoTime();
                    }
                });
        execLog.append("=== ").append(tag).append(" (exit=").append(result.exitCode()).append(") ===\n");
        if (result.timedOut()) {
            execLog.append("[TIMEOUT] ").append(tag).append(" killed after ").append(timeoutSeconds).append("s\n");
        }
        return result;
    }

    /** The last surefire totals line ("Tests run: X, Failures: Y, Errors: Z, Skipped: W"), or null. */
    static String surefireSummary(String output) {
        Matcher m = SUREFIRE_TOTALS.matcher(output == null ? "" : output);
        String last = null;
        while (m.find()) {
            last = "tests=" + m.group(1) + " failures=" + m.group(2)
                 + " errors=" + m.group(3) + " skipped=" + m.group(4);
        }
        return last;
    }

    private void setStatus(ExecuteRefactorEntity exec, ExecuteStatus status, StringBuilder execLog) {
        exec.setStatus(status);
        exec.setExecuteLog(execLog.toString());
        executeRefactorRepository.save(exec);
        log.info("[execute] v{} → {}", exec.getVersionNumber(), status);
    }

    private void fail(ExecuteRefactorEntity exec, StringBuilder execLog, String reason) {
        execLog.append("\n[FAILED] ").append(reason);
        exec.setStatus(ExecuteStatus.FAILED);
        exec.setCompletedAt(Instant.now());
        exec.setExecuteLog(execLog.toString());
        executeRefactorRepository.save(exec);
        // Drive the iteration to its terminal state here so every failure exit is covered — an
        // iteration must never be left sitting at EXECUTING once its run has finished.
        loopIterationService.updateFromExecuteStatus(exec.getIterationId(), ExecuteStatus.FAILED.name());
        log.warn("[execute] v{} FAILED: {}", exec.getVersionNumber(), reason);
    }

    private String slugify(String operation, String targetService) {
        String op = operation == null ? "unknown" : operation.toLowerCase().replace('_', '-');
        String svc = targetService == null ? "" :
                "-" + targetService.toLowerCase().replace("-service", "").replace("service", "");
        return op + svc;
    }

    private String commitMessage(int version, PlanResponse plan, String fulfilledPrerequisite) {
        // Both operands: "CREATE_REPLICA tournament" names half an operation, and the half it names
        // is the target, which reads as the source to anyone scanning git log.
        String secondary = plan.secondaryTarget() != null && !plan.secondaryTarget().isBlank()
                ? " ← " + plan.secondaryTarget() : "";
        String subject = "refactor(v" + version + "): " + plan.operation() + " "
                + plan.targetService() + secondary;
        // Credit the prerequisite in the body. Its files are in this commit, and a reader comparing
        // the message against `git show --stat` should not have to guess where they came from.
        return fulfilledPrerequisite == null || fulfilledPrerequisite.isBlank()
                ? subject
                : subject + "\n\nPrerequisite applied in this run: /" + fulfilledPrerequisite;
    }

    // ─── Inapplicable operations ──────────────────────────────────────────────

    /**
     * Reads the restriction block the skill wrote into the target module's {@code CLAUDE.md}.
     *
     * <p>The file, not {@code execLog}: the skill appends the block with {@code echo >> CLAUDE.md},
     * so the log only sees it incidentally and line-wrapped behind {@code ClaudeCommandMutator}'s
     * {@code [claude-cmd] } prefix.
     */
    private Optional<Map<String, String>> readRestriction(StringBuilder execLog) {
        return readClaudeMdBlock(execLog, "restriction", RestrictionParser::parse);
    }

    /**
     * Domain skills a prerequisite may ask Execute to run before retrying the operation.
     *
     * <p>An allowlist, not a convention check. {@code runFirst:} is written by a model into a file
     * the pipeline reads back, so it is untrusted input that turns into a subprocess; anything not
     * named here is refused and the run stops as an ordinary prerequisite. The four refactoring
     * operations are deliberately absent — each is a pipeline iteration of its own, with its own
     * plan, classifier and version, and nesting one inside another would hide a whole operation
     * inside another's log entry.
     */
    private static final Set<String> RUNNABLE_PREREQUISITE_SKILLS = Set.of(
            "new-event", "new-functionality", "new-aggregate",
            "intra-invariant", "inter-invariant", "service-guard"
    );

    /**
     * The {@code runFirst:} invocation of a prerequisite block, when Execute may run it.
     *
     * @return the invocation, or {@code null} when the block names none, names a skill outside
     *         {@link #RUNNABLE_PREREQUISITE_SKILLS}, or is malformed
     */
    private String runnablePrerequisite(Map<String, String> fields, ExecuteRefactorEntity exec,
                                        StringBuilder execLog) {
        String runFirst = fields.get("runFirst");
        if (runFirst == null || runFirst.isBlank()) return null;

        String invocation = runFirst.trim();
        if (invocation.startsWith("/")) invocation = invocation.substring(1);
        String skill = invocation.split("\\s+")[0];

        if (!RUNNABLE_PREREQUISITE_SKILLS.contains(skill)) {
            execLog.append("\n[prerequisite] runFirst names '").append(skill)
                   .append("', which Execute may not run — recording the prerequisite instead.\n");
            log.warn("[execute] v{} — prerequisite runFirst '{}' is not a runnable domain skill; "
                    + "allowed: {}", exec.getVersionNumber(), skill, RUNNABLE_PREREQUISITE_SKILLS);
            return null;
        }
        return invocation;
    }

    /**
     * Runs the prerequisite skill, then re-runs the operation — two {@code claude} processes, so
     * each gets its own {@code --max-turns} budget.
     */
    private void fulfilPrerequisite(ExecuteRefactorEntity exec, PlanResponse plan,
                                    String runFirst, StringBuilder execLog) throws Exception {
        String appDir = appTarget.absoluteDir(config.getProjectRoot());

        execLog.append("\n[prerequisite] fulfilling '").append(runFirst)
               .append("' before retrying ").append(plan.operation()).append("\n");
        log.info("[execute] v{} step 3c — running prerequisite '{}' (own turn budget)",
                exec.getVersionNumber(), runFirst);

        Instant t0 = Instant.now();
        claudeCommandMutator.applyPrerequisite(runFirst, appDir, execLog, exec.getVersionNumber());
        log.info("[execute] v{} step 3c — prerequisite finished in {}s",
                exec.getVersionNumber(), Duration.between(t0, Instant.now()).toSeconds());

        execLog.append("\n[prerequisite] retrying ").append(plan.operation()).append("\n");
        log.info("[execute] v{} step 3c — retrying the operation", exec.getVersionNumber());
        applyMutation(exec, plan, execLog, prerequisiteAppliedNote(runFirst, plan));
    }

    /**
     * Tells the retry that the uncommitted artefacts around it are its own prerequisite's output.
     *
     * <p>A prerequisite is run to make the operation applicable, and some prerequisites deliver part
     * of the operation on the way — {@code /new-event} builds the subscription, handler, poller and
     * refresh method that a replica's sync path consists of. The retry then finds nothing left to
     * add and, judging only by what it wrote itself, reports NO_CHANGE. That is accurate about the
     * retry and wrong about the run: the operation was carried out, by a command this pipeline
     * issued on the operation's behalf. Naming the delegation makes the difference reportable.
     */
    private String prerequisiteAppliedNote(String runFirst, PlanResponse plan) {
        return """


                ## Prerequisite Applied — This Run

                Before this attempt, the pipeline ran `/%s` to clear the stop your previous pass
                emitted. Every uncommitted change in the tree is that command's output, produced for
                **this** %s, and it is not yet committed.

                Judge the operation by the state of the module, not by what you write in this pass:

                - If the prerequisite already delivered what this operation would have added, the
                  operation **is done**. Report `Result: CHANGED` and add one line
                  `Delegated-to: /%s` naming the command that performed it. Say in `Summary:` which
                  artefacts came from the prerequisite and which reads, if any, you replaced.
                - Report `Result: NO_CHANGE` only when the module still lacks what this operation
                  requires *and* you could not add it. That is a different outcome and the pipeline
                  treats it differently.

                Do not re-create anything the prerequisite already built, and do not emit a stop
                block: the prerequisite has cleared it.
                """.formatted(runFirst, plan.operation(), runFirst);
    }

    private Optional<Map<String, String>> readPrerequisite(StringBuilder execLog) {
        return readClaudeMdBlock(execLog, "prerequisite", RestrictionParser::parsePrerequisite);
    }

    private Optional<Map<String, String>> readClaudeMdBlock(
            StringBuilder execLog, String what,
            Function<String, Optional<Map<String, String>>> parser) {
        String path = appTarget.absoluteClaudeMd(config.getProjectRoot());
        try {
            return parser.apply(Files.readString(Paths.get(path), StandardCharsets.UTF_8));
        } catch (Exception e) {
            execLog.append("\n[WARN] Could not read ").append(path)
                   .append(" to check for a ").append(what).append(": ")
                   .append(e.getMessage()).append("\n");
            log.warn("[execute] could not read {} for {} check: {}", path, what, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether a stop block describes the operation this run was actually asked to perform.
     *
     * <p>A skill may only record a verdict about its own invocation. Without this, a block naming any
     * other pair is persisted as an architect restriction and blocks that pair for every future run —
     * which is how a {@code course ↔ question} merge came to block {@code tournament ↔ answer}.
     *
     * <p>The pair is compared <b>unordered</b>: merge-aggregate is symmetric on its operands, so a
     * block naming {@code (question, course)} is about the same pair as a run of {@code (course,
     * question)}. A block that omits {@code secondaryTarget} where the run has one is a broader,
     * different rule — a wildcard over every pair sharing that primary — and is rejected too.
     */
    private boolean stopBelongsToRun(Map<String, String> fields, ExecuteRefactorEntity exec,
                                     StringBuilder execLog) {
        if (stopMatchesRun(fields, exec.getOperation(),
                exec.getTargetService(), exec.getSecondaryTarget())) {
            return true;
        }
        return rejectStop(execLog, fields.get("operation"), fields.get("targetService"),
                fields.get("secondaryTarget"), exec);
    }

    /** The decision itself, free of logging and entity access so it can be tested directly. */
    static boolean stopMatchesRun(Map<String, String> fields, String runOp,
                                  String runPrimary, String runSecondary) {
        String blockOp = fields.get("operation");
        if (!isBlank(blockOp) && runOp != null && !blockOp.strip().equalsIgnoreCase(runOp)) {
            return false;
        }

        String blockPrimary = fields.get("targetService");
        String blockSecondary = fields.get("secondaryTarget");
        // Neither side named: the block inherits the run's own pair in persistStop, so it matches.
        if (isBlank(blockPrimary) && isBlank(blockSecondary)) return true;

        // The run does not know its own second operand — rows written before secondaryTarget was
        // carried through from the plan. Validate on the primary alone rather than rejecting: a
        // half-pair can never equal a full one, so the strict comparison below would discard every
        // in-scope verdict for CREATE_REPLICA and MERGE_AGGREGATES, which is the whole point of the
        // NOT_APPLICABLE path. A genuine pair mismatch is still caught by the primary check.
        if (isBlank(runSecondary) && !isBlank(blockSecondary)) {
            return !isBlank(blockPrimary) && !isBlank(runPrimary)
                    && blockPrimary.strip().equalsIgnoreCase(runPrimary.strip());
        }

        return unorderedPair(blockPrimary, blockSecondary)
                .equals(unorderedPair(runPrimary, runSecondary));
    }

    /** Always returns false — the caller reads it as "this block is not this run's verdict". */
    private boolean rejectStop(StringBuilder execLog, String blockOp, String blockPrimary,
                               String blockSecondary, ExecuteRefactorEntity exec) {
        String found = (blockOp == null ? exec.getOperation() : blockOp)
                + " " + blockPrimary + " ↔ " + blockSecondary;
        String expected = exec.getOperation()
                + " " + exec.getTargetService() + " ↔ " + exec.getSecondaryTarget();
        execLog.append("\n[WARN] Ignoring a stop verdict for ").append(found)
               .append(" — this run is ").append(expected)
               .append(". A skill may only record a verdict about the operation it was given.\n");
        log.warn("[execute] v{} — ignoring a stop verdict for {}; this run is {}",
                exec.getVersionNumber(), found, expected);
        return false;
    }

    /**
     * Whether the run changed anything beyond its own {@code CLAUDE.md} entry.
     *
     * <p>The cheapest contradiction check there is: a stop verdict claims the operation was not
     * applicable, so the only file it may have written is the log entry recording that. Anything more
     * and the claim is false regardless of how well-formed the block is.
     */
    private boolean changedBeyondLogEntry(String projectRoot, ExecuteRefactorEntity exec,
                                          StringBuilder execLog) {
        int changed = countChangedFiles(projectRoot,
                appTarget.gitPathspec(), appTarget.claudeMdExcludePathspec());
        if (changed == 0) return false;

        execLog.append("\n[WARN] Ignoring a stop verdict: the skill changed ").append(changed)
               .append(" file(s) beyond its CLAUDE.md entry, so the operation cannot have been "
                       + "inapplicable. Continuing with the normal build.\n");
        log.warn("[execute] v{} — ignoring a stop verdict: {} file(s) changed beyond CLAUDE.md",
                exec.getVersionNumber(), changed);
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Lower-cased, order-independent view of a service pair; blanks drop out. */
    private static Set<String> unorderedPair(String a, String b) {
        Set<String> pair = new TreeSet<>();
        if (!isBlank(a)) pair.add(a.strip().toLowerCase());
        if (!isBlank(b)) pair.add(b.strip().toLowerCase());
        return pair;
    }

    /**
     * Persists the emitted restriction so Analyze stops proposing the operation.
     *
     * <p>Stored in <b>both orderings</b>: {@code ConstraintService} matches {@code targetService}
     * and {@code secondaryTarget} positionally, so a rule saved as {@code (question, course)} would
     * not match a candidate generated as {@code (course, question)} — and a merge that is
     * infeasible one way is infeasible the other. The reason is prefixed so an architect reading the
     * Config page can tell a tool-recorded rule from one they wrote themselves.
     */
    private void persistStop(Map<String, String> fields, boolean isPrerequisite,
                             ExecuteRefactorEntity exec, StringBuilder execLog) {
        String operation = fields.getOrDefault("operation", exec.getOperation());
        String primary   = fields.getOrDefault("targetService", exec.getTargetService());
        String secondary = fields.getOrDefault("secondaryTarget", exec.getSecondaryTarget());
        String reason    = truncate("[auto] " + fields.getOrDefault("reason", fields.get("chain")), 512);

        // A prerequisite is never blocking: the pair *is* reachable, just not in one step, so the
        // architect must keep seeing it as a candidate carrying its chain.
        boolean blocking = !isPrerequisite
                && !"false".equalsIgnoreCase(fields.getOrDefault("blocking", "true"));
        String chain = isPrerequisite ? truncate(fields.get("chain"), 512) : null;

        try {
            constraintService.createIfAbsent(RefactorConstraintEntity.builder()
                    .operation(operation).targetService(primary).secondaryTarget(secondary)
                    .reason(reason).blocking(blocking).prerequisiteChain(chain).build());
            if (secondary != null && !secondary.isBlank()) {
                constraintService.createIfAbsent(RefactorConstraintEntity.builder()
                        .operation(operation).targetService(secondary).secondaryTarget(primary)
                        .reason(reason).blocking(blocking).prerequisiteChain(chain).build());
            }
            String kind = isPrerequisite ? "prerequisite" : "restriction";
            execLog.append("\n[").append(kind).append("] recorded ").append(operation)
                   .append(" ").append(primary).append(" ↔ ").append(secondary)
                   .append(blocking ? " (blocking)" : " (warning)").append(": ").append(reason);
            if (chain != null) execLog.append("\n[prerequisite] chain: ").append(chain);
            execLog.append("\n");
            log.info("[execute] {} recorded: {} {} <-> {} — {}{}", kind, operation, primary, secondary,
                    reason, chain != null ? " | chain: " + chain : "");
        } catch (Exception e) {
            // Never fail the run over bookkeeping — the CLAUDE.md entry still records the verdict.
            execLog.append("\n[WARN] Could not persist the verdict: ").append(e.getMessage()).append("\n");
            log.warn("[execute] could not persist verdict: {}", e.getMessage());
        }
    }

    /**
     * Commits the CLAUDE.md entry and nothing else.
     *
     * <p>The entry is the audit record of an attempt that deliberately changed no code — either a
     * skill reporting its operation inapplicable, or a ghost run's verdict. Committing it also
     * leaves the working tree clean, which matters because {@code recoverToParentBranch} would
     * otherwise {@code git clean} it away before the next candidate runs.
     */
    private void commitLogEntryOnly(ExecuteRefactorEntity exec, String projectRoot,
                                    StringBuilder execLog, String message) throws Exception {
        if (countChangedFiles(projectRoot, appTarget.gitPathspec()) == 0) return;
        runGit(execLog, projectRoot, "-C", projectRoot, "add", "-A", "--", appTarget.gitPathspec());
        runGit(execLog, projectRoot, "-C", projectRoot, "commit", "-m", message);
        exec.setGitCommitSha(captureGitSha(projectRoot));
        executeRefactorRepository.save(exec);
    }

    private String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max - 1) + "…";
    }

    private String previousImageTag(int currentVersion) {
        int prev = currentVersion - 1;
        return prev < 1 ? null : appTarget.versionTag(prev);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
