package org.anonymous.msrefactor.rt.knowledge.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;
import org.anonymous.msrefactor.rt.quality.QualityAttributeScore;
import org.anonymous.msrefactor.rt.quality.QualityScoreResult;
import org.anonymous.msrefactor.rt.quality.QualityScoreService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles the version evolution tree: every {@link CodeVersionEntity} with the refactoring
 * operation that produced it and the quality attribute scores measured against it.
 *
 * <p>A version's scores come from the <em>latest</em> iteration that carries a monitor snapshot.
 * Versions that were never monitored are returned with an empty score map so the UI can render
 * them as "not measured" rather than as zeros.
 *
 * <p>Scores are cached by {@code snapshotId}, but only once the snapshot's monitor pipeline has
 * reached a terminal status: the pipeline patches the same snapshot row stage by stage, so scores
 * computed mid-run are partial and must not outlive the run. Without the cache every page load
 * would re-deserialize and re-score each version's snapshot payload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionEvolutionService {

    private final CodeVersionRepository codeVersionRepository;
    private final LoopIterationRepository loopIterationRepository;
    private final ExecuteRefactorRepository executeRefactorRepository;
    private final QualityScoreService qualityScoreService;

    private final Map<String, Map<String, ScoreEntry>> scoreCache = new ConcurrentHashMap<>();

    // ─── DTOs ────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScoreEntry {
        private double score;
        private int    percentage;
        private String status;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VersionTreeNode {
        private String  versionId;
        private int     versionNumber;
        private String  versionLabel;
        private String  parentVersionId;
        private String  status;
        private String  branchName;
        private String  gitCommitSha;
        private String  imageTag;
        private String  createdAt;
        private String  deployedAt;
        private int     iterationCount;
        private String  deployCommand;

        /** Refactoring that produced this version. Null for the baseline. */
        private String  operation;
        private String  targetService;
        private String  secondaryTarget;

        /** Snapshot the scores were computed from. Null when the version was never monitored. */
        private String  snapshotId;

        /** Attribute name → score. Empty when never monitored. */
        private Map<String, ScoreEntry> scores;

        /** Attribute name → score delta vs the parent version. Empty at the root or when either side is unmeasured. */
        private Map<String, Double> deltas;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VersionTree {
        private List<VersionTreeNode> nodes;
        /** Attribute name → versionId scoring highest for it. Only measured versions are considered. */
        private Map<String, String> bestByAttribute;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public VersionTree buildTree() {
        List<CodeVersionEntity> versions = codeVersionRepository.findAllByOrderByVersionNumberAsc();

        List<VersionTreeNode> nodes = new ArrayList<>(versions.size());
        Map<String, VersionTreeNode> byId = new LinkedHashMap<>();

        for (CodeVersionEntity v : versions) {
            VersionTreeNode node = toNode(v);
            nodes.add(node);
            byId.put(node.getVersionId(), node);
        }

        // Deltas need every node scored first, so this is a second pass.
        for (VersionTreeNode node : nodes) {
            node.setDeltas(deltaVsParent(node, byId));
        }

        return VersionTree.builder()
                .nodes(nodes)
                .bestByAttribute(bestByAttribute(nodes))
                .build();
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private VersionTreeNode toNode(CodeVersionEntity v) {
        List<LoopIterationEntity> iterations =
                loopIterationRepository.findAllByCodeVersionIdOrderByIterationNumberAsc(v.getVersionId());

        String snapshotId = latestSnapshotId(iterations);
        Map<String, ScoreEntry> scores = snapshotId == null ? Map.of() : scoresFor(snapshotId);

        VersionTreeNode.VersionTreeNodeBuilder node = VersionTreeNode.builder()
                .versionId(v.getVersionId())
                .versionNumber(v.getVersionNumber())
                .versionLabel(v.getVersionLabel())
                .parentVersionId(v.getParentVersionId())
                .status(v.getStatus())
                .branchName(v.getBranchName())
                .gitCommitSha(v.getGitCommitSha())
                .imageTag(v.getImageTag())
                .createdAt(v.getCreatedAt() != null ? v.getCreatedAt().toString() : null)
                .deployedAt(v.getDeployedAt() != null ? v.getDeployedAt().toString() : null)
                .iterationCount(iterations.size())
                .deployCommand(v.getDeployCommand())
                .snapshotId(snapshotId)
                .scores(scores);

        if (v.getProducingExecutionId() != null) {
            executeRefactorRepository.findById(v.getProducingExecutionId()).ifPresent(exec -> node
                    .operation(exec.getOperation())
                    .targetService(exec.getTargetService())
                    .secondaryTarget(exec.getSecondaryTarget()));
        }

        return node.build();
    }

    /** Most recent iteration carrying a snapshot; iterations are ordered ascending by number. */
    private String latestSnapshotId(List<LoopIterationEntity> iterations) {
        for (int i = iterations.size() - 1; i >= 0; i--) {
            String snapshotId = iterations.get(i).getSnapshotId();
            if (snapshotId != null && !snapshotId.isBlank()) {
                return snapshotId;
            }
        }
        return null;
    }

    private Map<String, ScoreEntry> scoresFor(String snapshotId) {
        Map<String, ScoreEntry> cached = scoreCache.get(snapshotId);
        if (cached != null) {
            return cached;
        }

        QualityScoreResult result = qualityScoreService.calculateQualityScores(snapshotId).orElse(null);
        if (result == null || result.getScores() == null) {
            log.warn("[evolution] no quality scores for snapshot {}", snapshotId);
            return Map.of();
        }
        Map<String, ScoreEntry> byAttribute = new LinkedHashMap<>();
        for (QualityAttributeScore s : result.getScores()) {
            if (s.getAttribute() == null) continue;
            byAttribute.put(s.getAttribute().name(), ScoreEntry.builder()
                    .score(s.getScore())
                    .percentage(s.getPercentage())
                    .status(s.getStatus())
                    .build());
        }
        if (isTerminal(result.getPipelineStatus())) {
            scoreCache.put(snapshotId, byAttribute);
        }
        return byAttribute;
    }

    /**
     * A snapshot may only be cached once its pipeline stopped mutating it. A null status means the
     * snapshot predates the pipeline (written in one go), which is terminal too.
     */
    private static boolean isTerminal(String pipelineStatus) {
        return pipelineStatus == null
                || "DONE".equals(pipelineStatus)
                || "STOPPED".equals(pipelineStatus)
                || "FAILED".equals(pipelineStatus);
    }

    private Map<String, Double> deltaVsParent(VersionTreeNode node, Map<String, VersionTreeNode> byId) {
        VersionTreeNode parent = node.getParentVersionId() == null ? null : byId.get(node.getParentVersionId());
        if (parent == null || node.getScores().isEmpty() || parent.getScores().isEmpty()) {
            return Map.of();
        }
        Map<String, Double> deltas = new LinkedHashMap<>();
        node.getScores().forEach((attribute, entry) -> {
            ScoreEntry parentEntry = parent.getScores().get(attribute);
            if (parentEntry != null) {
                deltas.put(attribute, entry.getScore() - parentEntry.getScore());
            }
        });
        return deltas;
    }

    private Map<String, String> bestByAttribute(List<VersionTreeNode> nodes) {
        Map<String, String> best = new LinkedHashMap<>();
        Map<String, Double> bestScore = new LinkedHashMap<>();
        for (VersionTreeNode node : nodes) {
            node.getScores().forEach((attribute, entry) -> {
                Double current = bestScore.get(attribute);
                if (current == null || entry.getScore() > current) {
                    bestScore.put(attribute, entry.getScore());
                    best.put(attribute, node.getVersionId());
                }
            });
        }
        return best;
    }
}
