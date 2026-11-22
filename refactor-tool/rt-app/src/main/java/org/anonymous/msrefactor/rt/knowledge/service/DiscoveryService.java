package org.anonymous.msrefactor.rt.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.execute.service.ClaudeCommandMutator;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.DiscoveryGraphEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.DiscoveryGraphRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryService {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?})\\s*```", Pattern.DOTALL);
    private static final Pattern BARE_JSON   = Pattern.compile("(\\{[\\s\\S]*\"services\"[\\s\\S]*})", Pattern.DOTALL);

    @Value("${rt.monitor.static-analysis.git-repo-path:../..}")
    private String gitRepoPath;

    private final ClaudeCommandMutator claudeMutator;
    private final DiscoveryGraphRepository repository;
    private final CodeVersionRepository codeVersionRepository;
    private final ObjectMapper objectMapper;

    public Optional<DiscoveryGraph> getLatestForVersion(String codeVersionId) {
        return repository.findTopByCodeVersionIdOrderByDiscoveredAtDesc(codeVersionId)
                .map(this::deserialize);
    }

    /**
     * Triggers discovery for a specific CodeVersion (by ID).
     * Uses the version's label to annotate the graph; always scans the current live filesystem.
     */
    public DiscoveryGraph discoverForVersion(String codeVersionId) {
        CodeVersionEntity version = codeVersionRepository.findById(codeVersionId)
                .orElseThrow(() -> new IllegalArgumentException("CodeVersion not found: " + codeVersionId));
        // "baseline" maps to "current" for the prompt; otherwise use the label directly
        String versionTag = "baseline".equals(version.getVersionLabel()) ? "current" : version.getVersionLabel();
        DiscoveryGraph graph = discoverInternal(null, codeVersionId, versionTag);
        log.info("[discovery] Saved discovery graph for codeVersionId={}", codeVersionId);
        return graph;
    }

    private DiscoveryGraph discoverInternal(String snapshotId, String codeVersionId, String version) {
        String workingDir = resolveWorkingDir();
        String prompt = buildPrompt();

        log.info("[discovery] Running Claude analysis snapshotId={} codeVersionId={} version={} workingDir={}",
                snapshotId, codeVersionId, version, workingDir);

        String rawOutput;
        try {
            ProcessResult result = claudeMutator.runDiscovery(prompt, workingDir);
            rawOutput = result.combined();
            log.info("[discovery] Claude finished, output length={}", rawOutput.length());
        } catch (Exception e) {
            log.error("[discovery] Claude invocation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Discovery failed: " + e.getMessage(), e);
        }

        DiscoveryGraph graph = parseGraph(rawOutput, snapshotId, version);
        graph.setCodeVersionId(codeVersionId);

        String graphJson;
        try {
            graphJson = objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            graphJson = "{}";
        }

        DiscoveryGraphEntity entity = DiscoveryGraphEntity.builder()
                .id(UUID.randomUUID().toString())
                .snapshotId(snapshotId)
                .codeVersionId(codeVersionId)
                .version(version)
                .graphJson(graphJson)
                .rawOutput(rawOutput.length() > 8000 ? rawOutput.substring(0, 8000) : rawOutput)
                .discoveredAt(Instant.now())
                .build();

        repository.save(entity);
        return graph;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String resolveWorkingDir() {
        // Claude runs relative to the project root (parent of applications/)
        java.nio.file.Path root = java.nio.file.Paths.get(gitRepoPath).toAbsolutePath().normalize();
        return root.toString();
    }

    private String buildPrompt() {
        return """
                Analyse the Java source code under:
                  %s/

                For each subdirectory (= service), scan the aggregate/ directory (direct .java files only,
                skip the sagas/ sub-directory) and identify:
                  1. Aggregate / entity classes (non-abstract, non-excluded classes).
                     Exclude files ending with: Repository, Factory, Dto, State, Type.
                     Exclude files starting with: Saga.
                  2. Invariant methods: methods matching `(boolean|void) (invariant\\w+|interInvariant\\w+)\\s*\\(`.
                     Mark as type "intra" if prefix is "invariant", "inter" if "interInvariant".
                  3. External relations: entity class names that contain keywords from other services.
                     Use these keyword-to-service mappings:
                       execution → CourseExecution, Execution
                       course    → Course
                       user      → Student, Creator, Participant, User
                       topic     → Topic
                       question  → Question
                       quiz      → Quiz
                       answer    → Answer
                       tournament→ Tournament
                  4. Service operations: public methods in the service class (files under service/ directory).

                Also scan the commandHandler/ and coordination/ directories briefly for any saga/workflow names.

                Return ONLY a single JSON object (no markdown, no explanation) in this exact schema:
                {
                  "version": "current",
                  "services": [
                    {
                      "name": "<service-directory-name>",
                      "aggregates": [
                        {
                          "name": "<ClassName>",
                          "entities": ["<EntityClass>", ...],
                          "invariants": [{"name": "<methodName>", "type": "intra|inter"}, ...],
                          "externalRelations": [{"to": "<service-name>", "via": "<EntityClass>"}, ...]
                        }
                      ],
                      "serviceOperations": ["<methodName>", ...]
                    }
                  ]
                }

                Output the JSON and nothing else.
                """.formatted(AppTargetConstants.SOURCE_TREE_PATH + "/"
                        + AppTargetConstants.MICROSERVICES_PACKAGE_PATH);
    }

    private DiscoveryGraph parseGraph(String raw, String snapshotId, String version) {
        // Try JSON code block first, then bare JSON
        String json = null;
        Matcher m = JSON_BLOCK.matcher(raw);
        if (m.find()) {
            json = m.group(1).trim();
        } else {
            Matcher m2 = BARE_JSON.matcher(raw);
            if (m2.find()) {
                json = m2.group(1).trim();
            }
        }

        if (json != null) {
            try {
                DiscoveryGraph graph = objectMapper.readValue(json, DiscoveryGraph.class);
                graph.setSnapshotId(snapshotId);
                graph.setVersion(version != null ? version : "current");
                graph.setDiscoveredAt(Instant.now().toString());
                return graph;
            } catch (Exception e) {
                log.warn("[discovery] Failed to parse JSON from Claude output: {}", e.getMessage());
            }
        }

        log.warn("[discovery] Could not extract JSON from Claude output — returning empty graph");
        return DiscoveryGraph.builder()
                .snapshotId(snapshotId)
                .version(version != null ? version : "current")
                .discoveredAt(Instant.now().toString())
                .services(List.of())
                .build();
    }

    private DiscoveryGraph deserialize(DiscoveryGraphEntity entity) {
        try {
            return objectMapper.readValue(entity.getGraphJson(), DiscoveryGraph.class);
        } catch (Exception e) {
            log.warn("[discovery] Failed to deserialize stored graph: {}", e.getMessage());
            return DiscoveryGraph.builder()
                    .snapshotId(entity.getSnapshotId())
                    .version(entity.getVersion())
                    .discoveredAt(entity.getDiscoveredAt().toString())
                    .services(List.of())
                    .build();
        }
    }
}
