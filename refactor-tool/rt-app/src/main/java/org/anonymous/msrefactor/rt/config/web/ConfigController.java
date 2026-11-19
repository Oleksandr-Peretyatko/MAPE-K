package org.anonymous.msrefactor.rt.config.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.HotspotThresholds;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeRepository;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.anonymous.msrefactor.rt.config.ClaudeConfig;
import org.anonymous.msrefactor.rt.config.service.AnalysisConfigService;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ProjectConfig;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.collector.StaticAnalysisCollector;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployTopology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Read-only configuration and architecture graph endpoints consumed by the Config UI tab.
 *
 * <pre>
 * GET /config/settings   — effective runtime configuration values
 * GET /config/graph      — entity-level architecture graph derived from static analysis
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ExecuteConfig              executeConfig;
    private final StaticAnalysisCollector    staticAnalysisCollector;
    private final ProjectConfig              projectConfig;
    private final AnalysisConfigService      analysisConfigService;
    private final ComposeTopologyResolver    topologyResolver;
    private final AnalysisThresholdsConfig    thresholds;
    private final ClaudeConfig               claudeConfig;
    private final QualityAttributeRepository qualityAttributeRepository;

    @Value("${rt.monitor.jaeger.base-url:http://localhost:16686}")
    private String jaegerBaseUrl;

    @Value("${rt.monitor.jaeger.collection-window-minutes:2}")
    private int collectionWindowMinutes;

    @Value("${rt.monitor.jaeger.known-services:}")
    private List<String> knownServices;

    @Value("${rt.monitor.locust.default-host:http://localhost:8080}")
    private String locustTargetHost;

    // ─── Settings ─────────────────────────────────────────────────────────────

    /**
     * Returns the effective runtime configuration as a nested JSON document.
     *
     * <p>Read-only here, but genuinely effective: every value is read from its
     * {@code @ConfigurationProperties} bean, so an {@code RT_*} environment override is reflected
     * after a container restart. (The Analyze thresholds used to be read straight from the
     * compiled constants, which meant this endpoint reported values an override could not change.)
     */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> execute = new HashMap<>();
        execute.put("baseBranch",    executeConfig.getBaseBranch());
        execute.put("buildProfiles", executeConfig.getBuildProfiles());
        execute.put("projectRoot",   executeConfig.getProjectRoot());
        execute.put("composeFile",   executeConfig.getComposeFile());
        execute.put("deployTarget",  executeConfig.getDeployTarget());
        execute.put("gitUserName",   executeConfig.getGitUserName());
        execute.put("gitUserEmail",  executeConfig.getGitUserEmail());
        // Boot-time only (RT_EXECUTE_GHOST_MODE). Surfaced so the UI can warn that a run will
        // create a branch and a log entry but mutate, test, build and deploy nothing.
        execute.put("ghostMode",     executeConfig.isGhostMode());

        // Which services the deploy actually targets, derived from the compose file.
        DeployTopology topology = topologyResolver.current();
        execute.put("deployMode",
                ComposeTopologyResolver.AUTO.equalsIgnoreCase(executeConfig.getDeployTarget())
                        ? "AUTO" : "EXPLICIT");
        execute.put("deployServices",           topology.serviceNames());
        execute.put("deployTopologySource",     topology.source().name());
        execute.put("deployTopologyResolvedAt", topology.resolvedAt().toString());

        Map<String, Object> monitor = new HashMap<>();
        monitor.put("jaegerBaseUrl",            jaegerBaseUrl);
        monitor.put("collectionWindowMinutes",   collectionWindowMinutes);
        // Jaeger identifies services by spring.application.name, so these are unsuffixed
        // (tournament, not tournament-service) and derived from the deployed topology.
        monitor.put("knownServices",             topology.jaegerServiceNames());
        monitor.put("knownServicesSeed",         knownServices);
        monitor.put("locustTargetHost",          locustTargetHost);

        // Hotspot detection thresholds are not listed here: they are per-project data, read and
        // edited through GET/PUT /config/heuristics.
        Map<String, Object> analyze = new HashMap<>();
        analyze.put("candidateLimit", thresholds.getCandidateLimit());
        analyze.put("enabledOperations", thresholds.getEnabledOperations().stream()
                .map(Enum::name).sorted().toList());

        // Effective Claude parameters — defaults live in ClaudeConstants, overridable via
        // RT_CLAUDE_* environment variables, so these reflect the running values.
        Map<String, Object> claude = new HashMap<>();
        claude.put("cliModel",          claudeConfig.getCliModel());
        claude.put("mutatorMaxTurns",   claudeConfig.getMutatorMaxTurns());
        claude.put("discoveryMaxTurns", claudeConfig.getDiscoveryMaxTurns());
        claude.put("allowedTools",      claudeConfig.getAllowedTools());

        Map<String, Object> result = new HashMap<>();
        result.put("execute", execute);
        result.put("monitor", monitor);
        result.put("analyze", analyze);
        result.put("claude",  claude);

        return ResponseEntity.ok(result);
    }

    // ─── Heuristic Thresholds ─────────────────────────────────────────────────

    /** Returns the currently active per-project hotspot detection thresholds. */
    @GetMapping("/heuristics")
    public ResponseEntity<HotspotThresholds> getHeuristics() {
        return ResponseEntity.ok(projectConfig.getThresholds());
    }

    /**
     * Persists new hotspot detection thresholds for this project.
     * Validates that numeric values are non-negative and that ratio fields
     * ({@code highCompensationRate}, {@code highErrorRate}) are ≤ 1.0.
     */
    @PutMapping("/heuristics")
    public ResponseEntity<?> updateHeuristics(@RequestBody HotspotThresholds body) {
        if (body.highLatencyP95Ms() < 0 || body.highSagaLatencyMs() < 0
                || body.highCrossSvcCalls() < 0 || body.highCompensationRate() < 0
                || body.highErrorRate() < 0 || body.readHeavyRatio() < 0
                || body.highCouplingM2() < 0 || body.lowCohesionM1() < 0
                || body.highEntityCount() < 0 || body.highExternalRels() < 0) {
            return ResponseEntity.badRequest().body("All threshold values must be non-negative");
        }
        if (body.highCompensationRate() > 1.0 || body.highErrorRate() > 1.0) {
            return ResponseEntity.badRequest().body("highCompensationRate and highErrorRate must be ≤ 1.0");
        }
        return ResponseEntity.ok(projectConfig.updateThresholds(body));
    }

    // ─── Analysis Config ──────────────────────────────────────────────────────

    /** Returns the currently active Analyze-phase configuration. */
    @GetMapping("/analysis-config")
    public ResponseEntity<AnalysisConfig> getAnalysisConfig() {
        return ResponseEntity.ok(analysisConfigService.current());
    }

    /**
     * Persists a new Analyze-phase configuration.
     * The change applies to the next analysis; existing results retain their original snapshot.
     *
     * <p>{@code defaultQualityPriorities} may be omitted (null) to keep the factory defaults;
     * when present it must be a non-empty, duplicate-free list of active quality attribute names.
     */
    @PutMapping("/analysis-config")
    public ResponseEntity<?> updateAnalysisConfig(@RequestBody AnalysisConfig body) {
        if (body.getHotspotRules() == null || body.getHotspotRules().isEmpty()) {
            return ResponseEntity.badRequest().body("hotspotRules must not be empty");
        }
        List<String> priorities = body.getDefaultQualityPriorities();
        if (priorities != null) {
            if (priorities.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body("defaultQualityPriorities must not be empty (omit the field to use defaults)");
            }
            List<String> normalized = priorities.stream()
                    .map(p -> p == null ? "" : p.trim().toUpperCase())
                    .toList();
            if (normalized.stream().anyMatch(String::isBlank)) {
                return ResponseEntity.badRequest().body("defaultQualityPriorities must not contain blank names");
            }
            if (new HashSet<>(normalized).size() != normalized.size()) {
                return ResponseEntity.badRequest().body("defaultQualityPriorities must not contain duplicates");
            }
            for (String name : normalized) {
                boolean activeQa = qualityAttributeRepository.findByName(name)
                        .map(QualityAttributeEntity::isActive)
                        .orElse(false);
                if (!activeQa) {
                    return ResponseEntity.badRequest().body("Unknown or inactive quality attribute: " + name);
                }
            }
            body.setDefaultQualityPriorities(normalized);
        }
        return ResponseEntity.ok(analysisConfigService.save(body));
    }

    /** Resets the Analyze-phase configuration to factory defaults. */
    @PutMapping("/analysis-config/reset")
    public ResponseEntity<AnalysisConfig> resetAnalysisConfig() {
        return ResponseEntity.ok(analysisConfigService.reset());
    }

    // ─── Architecture Graph ───────────────────────────────────────────────────

    /**
     * Returns entity-level architecture graph data for the current source version.
     *
     * <p>Shaped for Cytoscape: services as parent nodes, entities as child nodes,
     * external relations as edges between entity nodes.
     */
    @GetMapping("/graph")
    public ResponseEntity<Map<String, Object>> getGraph() {
        VersionAnalysisResult current;
        try {
            current = staticAnalysisCollector.collectVersion("current");
        } catch (Exception e) {
            log.warn("Config graph: static analysis failed — {}", e.getMessage());
            return ResponseEntity.ok(Map.of("services", List.of(), "error", e.getMessage()));
        }

        StaticMetrics metrics = current.getMetrics();
        if (metrics == null || metrics.getServices() == null) {
            return ResponseEntity.ok(Map.of("services", List.of()));
        }

        List<Map<String, Object>> services = new ArrayList<>();
        for (Map.Entry<String, ServiceStaticMetrics> entry : metrics.getServices().entrySet()) {
            String serviceName = entry.getKey();
            ServiceStaticMetrics svc = entry.getValue();

            Map<String, Object> serviceNode = new HashMap<>();
            serviceNode.put("name",              serviceName);
            serviceNode.put("entityCount",       svc.getEntityCount());
            serviceNode.put("entities",          svc.getEntityNames() != null ? svc.getEntityNames() : List.of());
            serviceNode.put("internalRelations", svc.getInternalRelations());
            serviceNode.put("externalRelations", svc.getExternalRelations() != null ? svc.getExternalRelations() : Map.of());
            serviceNode.put("m1Contribution",    svc.getM1Contribution());
            serviceNode.put("invariants",        svc.getInvariants() != null
                    ? svc.getInvariants().stream().map(InvariantInfo::getName).toList() : List.of());
            services.add(serviceNode);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("services", services);
        result.put("m1", metrics.getM1());
        result.put("m2", metrics.getM2());
        result.put("serviceCount", metrics.getServiceCount());

        return ResponseEntity.ok(result);
    }
}
