package org.anonymous.msrefactor.rt.plan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.constants.ProtocolConstants;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.plan.dto.PlanRequest;
import org.anonymous.msrefactor.rt.plan.dto.PlanResponse;
import org.anonymous.msrefactor.rt.plan.dto.PlanVersion;
import org.anonymous.msrefactor.rt.plan.dto.PlanVersionSummary;
import org.anonymous.msrefactor.rt.plan.persistence.PlanDocumentEntity;
import org.anonymous.msrefactor.rt.plan.persistence.PlanDocumentRepository;
import org.anonymous.msrefactor.rt.plan.persistence.PlanVersionEntity;
import org.anonymous.msrefactor.rt.plan.persistence.PlanVersionRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final SkillPromptBuilder skillPromptBuilder;
    private final PlanDocumentGenerator planDocumentGenerator;
    private final PlanDocumentRepository planDocumentRepository;
    private final ObjectMapper objectMapper;
    private final ExecuteConfig executeConfig;
    private final AppTargetConfig appTarget;
    private final PlanVersionRepository planVersionRepository;
    private final AnalysisThresholdsConfig thresholds;

    // Plan-version kinds and approval states — persisted values, see ProtocolConstants.
    // KIND_ENRICHED remains in ProtocolConstants: historic versions keep it, but nothing
    // creates new ones since the "Improve with AI" action was removed.
    private static final String KIND_ORIGINAL  = ProtocolConstants.KIND_ORIGINAL;
    private static final String KIND_USER_EDIT = ProtocolConstants.KIND_USER_EDIT;
    private static final String APPROVAL_PENDING  = ProtocolConstants.APPROVAL_PENDING;
    private static final String APPROVAL_APPROVED = ProtocolConstants.APPROVAL_APPROVED;
    private static final String APPROVAL_REJECTED = ProtocolConstants.APPROVAL_REJECTED;

    private volatile PlanResponse currentPlan;

    public PlanResponse generate(PlanRequest req) {
        // Analyze no longer offers a disabled operation, but a stale page or a direct API call can
        // still post one; refusing here keeps it from ever reaching Execute.
        if (!thresholds.isEnabled(req.operation())) {
            throw new IllegalArgumentException("Operation '" + req.operation()
                    + "' is not enabled (rt.analyze.enabled-operations=" + thresholds.getEnabledOperations() + ")");
        }
        // The invocation the frontend sends is derived from CandidateOperation.getCommandSignature()
        // and carries no project path, so it is normalized here — the single funnel through which
        // every plan (and therefore every Execute run and every enrichment) passes.
        String skillPrompt = appTarget.withProjectPath(
                (req.skillPrompt() != null && !req.skillPrompt().isBlank())
                        ? req.skillPrompt()
                        : skillPromptBuilder.build(req.operation(), req.targetService(),
                                                   req.secondaryTarget(), req.blueprint()));

        String planId          = UUID.randomUUID().toString();
        String now             = Instant.now().toString();
        String skillInvocation = appTarget.withProjectPath(req.skillInvocation());

        String document         = planDocumentGenerator.generate(req, skillInvocation);
        String enrichedDocument = null;

        PlanDocumentEntity entity =
                persist(planId, req, skillInvocation, document, enrichedDocument, now, skillPrompt);

        // Seed v1 (ORIGINAL) — the deterministic draft as the first immutable revision.
        appendVersion(planId, req.iterationId(), req.snapshotId(),
                KIND_ORIGINAL, skillInvocation, document, "deterministic", "plan-generator/v1", null);

        PlanResponse plan = toResponse(entity);
        currentPlan = plan;
        log.info("Plan generated: planId={} operation={} target={} skillInvocation='{}' v1=ORIGINAL",
                planId, req.operation(), req.targetService(), skillInvocation);
        return plan;
    }

    public Optional<PlanResponse> getCurrent() {
        if (currentPlan != null) return Optional.of(currentPlan);
        return planDocumentRepository.findAllByOrderByCreatedAtDesc().stream()
                .findFirst()
                .map(this::toResponse);
    }

    public Optional<PlanResponse> getPlan(String planId) {
        return planDocumentRepository.findById(planId).map(this::toResponse);
    }

    // ─── Plan-version operations ───────────────────────────────────────────────

    /** Lists all immutable versions of a plan, oldest first. */
    public List<PlanVersionSummary> listVersions(String planId) {
        return planVersionRepository.findByPlanIdOrderByVersionNumberAsc(planId).stream()
                .map(this::toVersionSummary)
                .collect(Collectors.toList());
    }

    public Optional<PlanVersion> getVersion(String planVersionId) {
        return planVersionRepository.findById(planVersionId).map(this::toVersionDto);
    }

    /** Saves a manually-edited document as a new USER_EDIT version. */
    public PlanVersion saveUserEdit(String planId, String document, String note) {
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("Document must not be empty");
        }
        PlanDocumentEntity doc = planDocumentRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        String skillInvocation = planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(planId)
                .map(PlanVersionEntity::getSkillInvocation)
                .orElse(doc.getSkillInvocation());
        PlanVersionEntity v = appendDerivedVersion(doc, KIND_USER_EDIT, skillInvocation, document, "user", null, note);
        refreshCurrentPlan(planId);
        log.info("[plan] planId={} appended USER_EDIT version v{}", planId, v.getVersionNumber());
        return toVersionDto(v);
    }

    /** Sets the approval state of a version. Approving demotes any other approved version to PENDING. */
    public PlanVersion setApproval(String planVersionId, String state, String note) {
        String normalized = state == null ? "" : state.trim().toUpperCase();
        if (!APPROVAL_APPROVED.equals(normalized)
                && !APPROVAL_REJECTED.equals(normalized)
                && !APPROVAL_PENDING.equals(normalized)) {
            throw new IllegalArgumentException("Invalid approval state: " + state);
        }
        PlanVersionEntity v = planVersionRepository.findById(planVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Plan version not found: " + planVersionId));

        if (APPROVAL_APPROVED.equals(normalized)) {
            List<PlanVersionEntity> approved =
                    planVersionRepository.findByPlanIdAndApprovalState(v.getPlanId(), APPROVAL_APPROVED);
            List<PlanVersionEntity> demoted = approved.stream()
                    .filter(a -> !a.getPlanVersionId().equals(planVersionId))
                    .peek(a -> a.setApprovalState(APPROVAL_PENDING))
                    .collect(Collectors.toList());
            if (!demoted.isEmpty()) planVersionRepository.saveAll(demoted);
        }
        v.setApprovalState(normalized);
        if (note != null && !note.isBlank()) v.setNote(note);
        planVersionRepository.save(v);
        refreshCurrentPlan(v.getPlanId());
        log.info("[plan] planVersionId={} (planId={} v{}) → approvalState={}",
                planVersionId, v.getPlanId(), v.getVersionNumber(), normalized);
        return toVersionDto(v);
    }

    /** The version the Execute phase should run: APPROVED if any, else the latest. */
    private Optional<PlanVersionEntity> resolveActiveVersion(String planId) {
        Optional<PlanVersionEntity> approved = planVersionRepository
                .findTopByPlanIdAndApprovalStateOrderByVersionNumberDesc(planId, APPROVAL_APPROVED);
        if (approved.isPresent()) return approved;
        return planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(planId);
    }

    private PlanVersionEntity appendDerivedVersion(PlanDocumentEntity doc, String kind, String skillInvocation,
                                                   String document, String model, String promptVersion, String note) {
        // Copy iteration_id from the latest existing version (plan_document does not store it).
        String iterationId = planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(doc.getPlanId())
                .map(PlanVersionEntity::getIterationId)
                .orElse(null);
        return appendVersion(doc.getPlanId(), iterationId, doc.getSnapshotId(),
                kind, skillInvocation, document, model, promptVersion, note);
    }

    private PlanVersionEntity appendVersion(String planId, String iterationId, String snapshotId,
                                            String kind, String skillInvocation, String document,
                                            String model, String promptVersion, String note) {
        int next = planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(planId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
        PlanVersionEntity version = PlanVersionEntity.builder()
                .planVersionId(UUID.randomUUID().toString())
                .planId(planId)
                .versionNumber(next)
                .iterationId(iterationId)
                .snapshotId(snapshotId)
                .kind(kind)
                .skillInvocation(skillInvocation)
                .document(document)
                .model(model)
                .promptVersion(promptVersion)
                .approvalState(APPROVAL_PENDING)
                .note(note)
                .createdAt(Instant.now())
                .build();
        return planVersionRepository.save(version);
    }

    private void refreshCurrentPlan(String planId) {
        if (currentPlan != null && currentPlan.planId().equals(planId)) {
            planDocumentRepository.findById(planId).ifPresent(e -> currentPlan = toResponse(e));
        }
    }

    private PlanVersion toVersionDto(PlanVersionEntity v) {
        return new PlanVersion(
                v.getPlanVersionId(),
                v.getPlanId(),
                v.getVersionNumber(),
                v.getKind(),
                v.getSkillInvocation(),
                v.getDocument(),
                v.getModel(),
                v.getPromptVersion(),
                v.getApprovalState(),
                v.getNote(),
                v.getCreatedAt().toString(),
                v.getIterationId(),
                v.getSnapshotId());
    }

    private PlanVersionSummary toVersionSummary(PlanVersionEntity v) {
        return new PlanVersionSummary(
                v.getPlanVersionId(),
                v.getVersionNumber(),
                v.getKind(),
                v.getModel(),
                v.getApprovalState(),
                v.getCreatedAt().toString());
    }

    // ─── Persistence helpers ──────────────────────────────────────────────────

    private PlanDocumentEntity persist(String planId, PlanRequest req, String skillInvocation,
                         String document, String enrichedDocument, String now, String skillPrompt) {
        String blueprintJson = null;
        if (req.blueprint() != null) {
            try {
                blueprintJson = objectMapper.writeValueAsString(req.blueprint());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize blueprint for planId={}: {}", planId, e.getMessage());
            }
        }
        PlanDocumentEntity entity = PlanDocumentEntity.builder()
                .planId(planId)
                .snapshotId(req.snapshotId())
                .operation(req.operation())
                .targetService(req.targetService())
                .secondaryTarget(req.secondaryTarget())
                .skillInvocation(skillInvocation)
                .skillPrompt(skillPrompt)
                .document(document)
                .enrichedDocument(enrichedDocument)
                .blueprintPayload(blueprintJson)
                .createdAt(Instant.parse(now))
                .status("scheduled")
                .build();
        return planDocumentRepository.save(entity);
    }

    private PlanResponse toResponse(PlanDocumentEntity e) {
        RefactorBlueprint blueprint = null;
        if (e.getBlueprintPayload() != null) {
            try {
                blueprint = objectMapper.readValue(e.getBlueprintPayload(), RefactorBlueprint.class);
            } catch (JsonProcessingException ex) {
                log.warn("Failed to deserialize blueprint for planId={}: {}", e.getPlanId(), ex.getMessage());
            }
        }

        // Reflect the active (approved, else latest) version: document = original draft (plan_document,
        // never overwritten); enrichedDocument/enrichedSkillInvocation = the active version when it is a
        // refinement (ENRICHED/USER_EDIT). This is exactly the shape ExecuteService already consumes.
        String skillInvocation         = e.getSkillInvocation();
        String enrichedDocument        = e.getEnrichedDocument();
        String enrichedSkillInvocation = e.getEnrichedSkillInvocation();
        String activePlanVersionId = null;
        Integer activeVersionNumber = null;
        String approvalState = null;

        Optional<PlanVersionEntity> active = resolveActiveVersion(e.getPlanId());
        if (active.isPresent()) {
            PlanVersionEntity a = active.get();
            activePlanVersionId = a.getPlanVersionId();
            activeVersionNumber = a.getVersionNumber();
            approvalState       = a.getApprovalState();
            if (KIND_ORIGINAL.equals(a.getKind())) {
                skillInvocation         = a.getSkillInvocation();
                enrichedDocument        = null;
                enrichedSkillInvocation = null;
            } else {
                enrichedDocument        = a.getDocument();
                enrichedSkillInvocation = a.getSkillInvocation();
            }
        }

        return new PlanResponse(
                e.getPlanId(),
                e.getStatus(),
                e.getOperation(),
                e.getTargetService(),
                e.getSecondaryTarget(),
                e.getSnapshotId(),
                skillInvocation,
                e.getCreatedAt().toString(),
                blueprint,
                e.getSkillPrompt(),
                e.getDocument(),
                readSkillContent(e.getSkillInvocation()),
                enrichedDocument,
                enrichedSkillInvocation,
                activePlanVersionId,
                activeVersionNumber,
                approvalState
        );
    }

    private String readSkillContent(String skillInvocation) {
        if (skillInvocation == null || skillInvocation.isBlank()) return "";
        try {
            String skillName = skillInvocation.trim().split("\\s+")[0];
            Path skillFile = Paths.get(executeConfig.getProjectRoot(),
                    "refactor-tool", ".claude", "skills", skillName, "SKILL.md");
            return Files.exists(skillFile) ? Files.readString(skillFile) : "";
        } catch (Exception e) {
            log.warn("Could not read SKILL.md for {}: {}", skillInvocation, e.getMessage());
            return "";
        }
    }

}
