package org.anonymous.msrefactor.rt.knowledge.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.analyze.persistence.AnalysisResultRepository;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteStatus;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.DiscoveryGraphRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeVersionService {

    // The base branch is read from ExecuteConfig rather than re-declared with its own @Value default.
    // One owner, one default, one RT_EXECUTE_BASE_BRANCH override — a second declaration here could
    // name the baseline after one branch while Execute resolved its start point from another.
    private final ExecuteConfig executeConfig;

    private final CodeVersionRepository    codeVersionRepository;
    private final ExecuteRefactorRepository executeRefactorRepository;
    private final LoopIterationRepository  loopIterationRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final DiscoveryGraphRepository discoveryGraphRepository;
    private final DeployCommandRenderer    deployCommandRenderer;
    private final ObjectMapper             objectMapper;

    // ─── DTOs ────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeVersionSummary {
        private String versionId;
        private int    versionNumber;
        private String versionLabel;
        private String branchName;
        private String gitCommitSha;
        private String imageTag;
        private String status;
        private String producingExecutionId;
        private String parentVersionId;
        private String createdAt;
        private String deployedAt;
        private String notes;
        private String deployCommand;
        /** QA priority names (highest first) the producing analysis ran with; null when unknown. */
        private List<String> qualityPriorities;
        private int    iterationCount;
        private boolean hasDiscoveryGraph;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeVersionDetail {
        private String versionId;
        private int    versionNumber;
        private String versionLabel;
        private String branchName;
        private String gitCommitSha;
        private String imageTag;
        private String status;
        private String producingExecutionId;
        private String parentVersionId;
        private String createdAt;
        private String deployedAt;
        private String notes;
        private String deployCommand;
        /** QA priority names (highest first) the producing analysis ran with; null when unknown. */
        private List<String> qualityPriorities;
        private int    iterationCount;
        private boolean hasDiscoveryGraph;
        private List<LoopIterationService.LoopIterationSummary> iterations;
        private String discoveryGraphId;
    }

    // ─── Startup ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void initBaseline() {
        createBaseline(executeConfig.getBaseBranch());
        backfillDeployCommands();
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Creates the BASELINE version (v0) representing the original code. Idempotent.
     *
     * <p><b>{@code gitCommitSha} is deliberately left null.</b> The baseline is a <i>branch</i>, not
     * a commit: it means "wherever {@code base-branch} points now". A stored SHA can never stay
     * current — writing one down is itself a commit, so the record is immediately behind — and
     * {@code ExecuteService.resolveBranchStartPoint} therefore resolves the branch tip at run time
     * and ignores any value found here. Do not "complete" this builder by adding one.
     *
     * <p>Every other version does store its commit: that is what makes v4-branches-from-v3 exact
     * and the evolution tree reproducible. See {@link #createFromExecution}.
     */
    public CodeVersionSummary createBaseline(String branchName) {
        if (codeVersionRepository.existsByVersionNumber(0)) {
            return toSummary(codeVersionRepository.findByVersionNumber(0).orElseThrow());
        }
        CodeVersionEntity baseline = CodeVersionEntity.builder()
                .versionId(UUID.randomUUID().toString())
                .versionNumber(0)
                .versionLabel("baseline")
                .branchName(branchName)
                .status(CodeVersionEntity.BASELINE)
                .createdAt(Instant.now())
                .build();
        codeVersionRepository.save(baseline);
        log.info("[version] Created BASELINE version branchName={}", branchName);
        return toSummary(baseline);
    }

    /**
     * Creates a new CodeVersion from a successfully DEPLOYED execute-refactor record.
     * Marks the previous ACTIVE version as SUPERSEDED.
     * Called by ExecuteService after reaching DEPLOYED status.
     */
    public CodeVersionSummary createFromExecution(ExecuteRefactorEntity exec) {
        int vNum = exec.getVersionNumber();
        String label = "v" + vNum;

        if (codeVersionRepository.existsByVersionNumber(vNum)) {
            log.warn("[version] CodeVersion for versionNumber={} already exists — skipping", vNum);
            return toSummary(codeVersionRepository.findByVersionNumber(vNum).orElseThrow());
        }

        // Parent is the version this run actually branched from. Legacy rows carry no
        // sourceVersionId, so fall back to the current ACTIVE (else baseline) version.
        String parentVersionId = exec.getSourceVersionId() != null
                ? exec.getSourceVersionId()
                : findActiveOrBaseline().map(CodeVersionEntity::getVersionId).orElse(null);

        // Mark current ACTIVE as SUPERSEDED
        codeVersionRepository.findTopByStatusOrderByVersionNumberDesc(CodeVersionEntity.ACTIVE)
                .ifPresent(prev -> {
                    prev.setStatus(CodeVersionEntity.SUPERSEDED);
                    codeVersionRepository.save(prev);
                    log.info("[version] Marked v{} as SUPERSEDED", prev.getVersionNumber());
                });

        CodeVersionEntity version = CodeVersionEntity.builder()
                .versionId(UUID.randomUUID().toString())
                .versionNumber(vNum)
                .versionLabel(label)
                .branchName(exec.getBranchName())
                .gitCommitSha(exec.getGitCommitSha())
                .imageTag(exec.getImageTag())
                .status(CodeVersionEntity.ACTIVE)
                .producingExecutionId(exec.getExecuteRefactorId())
                .parentVersionId(parentVersionId)
                .createdAt(Instant.now())
                .deployedAt(exec.getCompletedAt())
                .deployCommand(deployCommandFor(exec))
                .qualityPriorities(prioritiesFor(exec))
                .build();

        codeVersionRepository.save(version);
        log.info("[version] Created CodeVersion v{} label={} branch={}", vNum, label, exec.getBranchName());
        return toSummary(version);
    }

    public List<CodeVersionSummary> listAll() {
        return codeVersionRepository.findAllByOrderByVersionNumberAsc()
                .stream().map(this::toSummary).collect(Collectors.toList());
    }

    public Optional<CodeVersionEntity> findById(String versionId) {
        return codeVersionRepository.findById(versionId);
    }

    /** The currently deployed version, or the baseline when nothing has been deployed yet. */
    public Optional<CodeVersionEntity> findActiveOrBaseline() {
        Optional<CodeVersionEntity> active =
                codeVersionRepository.findTopByStatusOrderByVersionNumberDesc(CodeVersionEntity.ACTIVE);
        return active.isPresent() ? active : codeVersionRepository.findByVersionNumber(0);
    }

    public Optional<CodeVersionDetail> getDetail(String versionId) {
        return codeVersionRepository.findById(versionId).map(this::toDetail);
    }

    /**
     * Fills {@code deploy_command} where it is missing, so versions deployed before the column
     * existed get one too. The baseline is re-rendered on every boot: it names the tip of the base
     * branch, whose compose file can change, rather than a fixed commit. Never fails startup.
     */
    public void backfillDeployCommands() {
        try {
            codeVersionRepository.findByVersionNumber(0).ifPresent(baseline -> {
                String branch = baseline.getBranchName() != null
                        ? baseline.getBranchName() : executeConfig.getBaseBranch();
                String command = deployCommandRenderer.render(
                        branch, 0, deployCommandRenderer.baselineServices(branch));
                if (!command.equals(baseline.getDeployCommand())) {
                    baseline.setDeployCommand(command);
                    codeVersionRepository.save(baseline);
                }
            });

            for (CodeVersionEntity cv : codeVersionRepository.findAllByOrderByVersionNumberAsc()) {
                if (cv.getVersionNumber() == 0 || cv.getDeployCommand() != null
                        || cv.getProducingExecutionId() == null) continue;
                executeRefactorRepository.findById(cv.getProducingExecutionId())
                        .map(this::deployCommandFor)
                        .ifPresent(command -> {
                            cv.setDeployCommand(command);
                            codeVersionRepository.save(cv);
                            log.info("[version] Back-filled deploy command for {}", cv.getVersionLabel());
                        });
            }
        } catch (Exception e) {
            log.warn("[version] deploy command back-fill failed (non-fatal): {}", e.getMessage());
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Rendered from the services the run actually deployed ({@code execute_refactor.deployed_services},
     * recorded before the deploy runs). Null when the run never got that far.
     */
    private String deployCommandFor(ExecuteRefactorEntity exec) {
        String json = exec.getDeployedServices();
        if (json == null || json.isBlank()) return null;
        try {
            List<String> services = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            if (services.isEmpty()) return null;
            String gitRef = exec.getBranchName() != null ? exec.getBranchName() : exec.getGitCommitSha();
            if (gitRef == null) return null;
            return deployCommandRenderer.render(gitRef, exec.getVersionNumber(), services);
        } catch (Exception e) {
            log.warn("[version] v{} could not render deploy command: {}", exec.getVersionNumber(), e.getMessage());
            return null;
        }
    }

    /**
     * CSV of the quality priorities the producing iteration's analysis ran with, resolved through
     * execution → iteration → analysis. Null when any link is missing (e.g. test runs outside an
     * iteration) — the version is still created.
     */
    private String prioritiesFor(ExecuteRefactorEntity exec) {
        try {
            if (exec.getIterationId() == null || exec.getIterationId().isBlank()) return null;
            return loopIterationRepository.findById(exec.getIterationId())
                    .map(it -> it.getAnalysisId())
                    .filter(id -> id != null && !id.isBlank())
                    .flatMap(analysisResultRepository::findById)
                    .map(a -> a.getQualityPriorities())
                    .filter(csv -> csv != null && !csv.isBlank())
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("[version] v{} could not resolve quality priorities: {}",
                    exec.getVersionNumber(), ex.getMessage());
            return null;
        }
    }

    private static List<String> parsePriorityCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.asList(csv.split(","));
    }

    private CodeVersionSummary toSummary(CodeVersionEntity e) {
        int iterCount = loopIterationRepository
                .findAllByCodeVersionIdOrderByIterationNumberAsc(e.getVersionId()).size();
        boolean hasGraph = discoveryGraphRepository
                .findTopByCodeVersionIdOrderByDiscoveredAtDesc(e.getVersionId()).isPresent();
        return CodeVersionSummary.builder()
                .versionId(e.getVersionId())
                .versionNumber(e.getVersionNumber())
                .versionLabel(e.getVersionLabel())
                .branchName(e.getBranchName())
                .gitCommitSha(e.getGitCommitSha())
                .imageTag(e.getImageTag())
                .status(e.getStatus())
                .producingExecutionId(e.getProducingExecutionId())
                .parentVersionId(e.getParentVersionId())
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                .deployedAt(e.getDeployedAt() != null ? e.getDeployedAt().toString() : null)
                .notes(e.getNotes())
                .deployCommand(e.getDeployCommand())
                .qualityPriorities(parsePriorityCsv(e.getQualityPriorities()))
                .iterationCount(iterCount)
                .hasDiscoveryGraph(hasGraph)
                .build();
    }

    private CodeVersionDetail toDetail(CodeVersionEntity e) {
        List<LoopIterationService.LoopIterationSummary> iterations =
                loopIterationRepository.findAllByCodeVersionIdOrderByIterationNumberAsc(e.getVersionId())
                        .stream()
                        .map(li -> LoopIterationService.LoopIterationSummary.builder()
                                .iterationId(li.getIterationId())
                                .iterationNumber(li.getIterationNumber())
                                .name(li.getName())
                                .status(li.getStatus())
                                .snapshotId(li.getSnapshotId())
                                .analysisId(li.getAnalysisId())
                                .planId(li.getPlanId())
                                .executeRefactorId(li.getExecuteRefactorId())
                                .codeVersionId(li.getCodeVersionId())
                                .startedAt(li.getStartedAt() != null ? li.getStartedAt().toString() : null)
                                .completedAt(li.getCompletedAt() != null ? li.getCompletedAt().toString() : null)
                                .notes(li.getNotes())
                                .build())
                        .collect(Collectors.toList());

        String discoveryGraphId = discoveryGraphRepository
                .findTopByCodeVersionIdOrderByDiscoveredAtDesc(e.getVersionId())
                .map(dg -> dg.getId())
                .orElse(null);

        return CodeVersionDetail.builder()
                .versionId(e.getVersionId())
                .versionNumber(e.getVersionNumber())
                .versionLabel(e.getVersionLabel())
                .branchName(e.getBranchName())
                .gitCommitSha(e.getGitCommitSha())
                .imageTag(e.getImageTag())
                .status(e.getStatus())
                .producingExecutionId(e.getProducingExecutionId())
                .parentVersionId(e.getParentVersionId())
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                .deployedAt(e.getDeployedAt() != null ? e.getDeployedAt().toString() : null)
                .notes(e.getNotes())
                .deployCommand(e.getDeployCommand())
                .qualityPriorities(parsePriorityCsv(e.getQualityPriorities()))
                .iterationCount(iterations.size())
                .hasDiscoveryGraph(discoveryGraphId != null)
                .iterations(iterations)
                .discoveryGraphId(discoveryGraphId)
                .build();
    }

}
