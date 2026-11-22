package org.anonymous.msrefactor.rt.analyze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.AnalysisRequest;
import org.anonymous.msrefactor.rt.analyze.AnalysisResult;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.ConsistencyEvidence;
import org.anonymous.msrefactor.rt.analyze.CrossVersionClues;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.HotspotThresholds;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.MetricImpact;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;
import org.anonymous.msrefactor.rt.analyze.RuntimeEvidence;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.analyze.detector.HotspotDetector;
import org.anonymous.msrefactor.rt.analyze.extractor.EvidenceExtractor;
import org.anonymous.msrefactor.rt.analyze.attribution.EntityAttributionService;
import org.anonymous.msrefactor.rt.analyze.deepener.CandidateDeepener;
import org.anonymous.msrefactor.rt.analyze.generator.CandidateGenerator;
import org.anonymous.msrefactor.rt.analyze.persistence.AnalysisResultEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.AnalysisResultRepository;
import org.anonymous.msrefactor.rt.analyze.persistence.RefactorConstraintEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeEntity;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeRepository;
import org.anonymous.msrefactor.rt.analyze.ranking.SimpleRankingStrategy;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.anonymous.msrefactor.rt.config.service.AnalysisConfigService;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.ProjectConfig;
import org.anonymous.msrefactor.rt.monitor.service.MonitorService;
import org.anonymous.msrefactor.rt.quality.QualityAttributeScore;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.quality.QualityScoreService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the Analyze phase of the MAPE-K loop.
 *
 * <p>Pipeline: snapshot → evidence extraction → hotspot detection →
 * candidate generation → scoring/ranking → {@link AnalysisResult}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeService {

    private static final String PLAN_BOUNDARY =
            "Final operation selection, parameter binding, and execution ordering belong to the Plan phase.";

    private final MonitorService              monitorService;
    private final ProjectConfig               projectConfig;
    private final AnalysisConfigService       analysisConfigService;
    private final EvidenceExtractor           evidenceExtractor;
    private final HotspotDetector             hotspotDetector;
    private final EntityAttributionService    entityAttributionService;
    private final CandidateGenerator          candidateGenerator;
    private final CandidateDeepener           candidateDeepener;
    private final SimpleRankingStrategy       simpleRankingStrategy;
    private final OperationQaScoringService   operationQaScoringService;
    private final QualityAttributeRepository  qualityAttributeRepository;
    private final ConstraintService           constraintService;
    private final AnalysisResultRepository    analysisResultRepository;
    private final LoopIterationRepository     loopIterationRepository;
    private final ObjectMapper                objectMapper;
    private final ApplicationContext          applicationContext;
    private final QualityScoreService         qualityScoreService;
    private final AnalysisThresholdsConfig    thresholds;

    /**
     * Runs the full Analyze pipeline for the snapshot identified by the request.
     *
     * @param request analysis parameters (null snapshotId → latest snapshot)
     * @return analysis result, or empty if no matching snapshot exists
     */
    public Optional<AnalysisResult> analyze(AnalysisRequest request) {
        // 1. Resolve snapshot
        Optional<MonitorSnapshot> snapOpt = resolveSnapshot(request);
        if (snapOpt.isEmpty()) {
            log.warn("Analyze: no snapshot found for request {}", request);
            return Optional.empty();
        }
        MonitorSnapshot snapshot = snapOpt.get();

        // 2. Load config snapshot (also the source of the default priority order)
        AnalysisConfig config = analysisConfigService.current();

        // 2b. Normalise priorities (string form for ranking, enum form for hotspot ordering).
        //     Unknown enum names in the request arrive as null elements (the shared mapper reads
        //     unknown enum values as null), so they are filtered before the empty check.
        List<String> priorityNames = request.getQualityPriorities() != null
                ? request.getQualityPriorities().stream()
                        .filter(Objects::nonNull)
                        .map(QualityAttribute::name)
                        .collect(Collectors.toList())
                : List.of();
        if (priorityNames.isEmpty()) {
            priorityNames = config.getDefaultQualityPriorities();
        }
        // Custom catalog QAs have no enum value: they rank (resolved by name) but do not
        // influence hotspot ordering, which is routed through the enum-keyed config map.
        List<QualityAttribute> qaOrder = priorityNames.stream()
                .map(AnalyzeService::toQualityAttributeOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("Analyze: running on snapshot {} with priorities {}", snapshot.getSnapshotId(), priorityNames);

        // 4. Extract evidence
        HotspotThresholds   thresholds  = projectConfig.getThresholds();
        StructuralEvidence  structural  = evidenceExtractor.extractStructural(snapshot, thresholds);
        RuntimeEvidence     runtime     = evidenceExtractor.extractRuntime(snapshot, thresholds);
        ConsistencyEvidence consistency = evidenceExtractor.extractConsistency(snapshot, thresholds);
        CrossVersionClues   crossVer    = evidenceExtractor.extractCrossVersion(snapshot);

        // 5. Compute quality attribute scores (used by all ranking modes and included in result)
        List<QualityAttributeScore> qualityScores = qualityScoreService.computeFromSnapshot(snapshot);

        // 6. Detect hotspots
        List<Hotspot> hotspots = hotspotDetector.detect(structural, runtime, consistency, thresholds);

        // 6b. Sort hotspots so the user's top QA priority drives candidate generation
        hotspots = sortHotspotsByQaPriority(hotspots, qaOrder, config);

        // 6c. Step 4.2 — entity-level attribution: enrich each hotspot with the entity classes
        //     inside the affected service(s) that are the root cause of the detected problem
        hotspots = entityAttributionService.attribute(hotspots, structural);

        // 7. No DiscoveryGraph: graphs are keyed by code version, never by snapshot, so the
        //    snapshot-keyed lookup this used to do never found one. Every selector is null-safe.
        DiscoveryGraph graph = null;

        // 8. Generate service-level partial candidates (step 5.2 only — entity/aggregate names unresolved)
        List<CandidateOperation> raw = candidateGenerator.generate(hotspots, structural, runtime, consistency, config, graph);

        // 8b. Step 5.3 — deepen each candidate: resolve specific entity/aggregate pair
        List<CandidateOperation> deepened = candidateDeepener.deepen(raw, structural, runtime, consistency, graph);

        // 8c. Drop MERGE_AGGREGATES candidates without a resolved secondary target (no coupling partner found);
        // keep blocked placeholders (blockedBy set).
        deepened = deepened.stream()
                .filter(c -> c.getBlockedBy() != null
                          || c.getOperation() != OperationType.MERGE_AGGREGATES
                          || c.getSecondaryTarget() != null)
                .collect(Collectors.toList());

        // 8d. Hold aside selection-time blocked placeholders so ranking cannot filter them out;
        //     they go straight to the blocked section via applyConstraints.
        List<CandidateOperation> placeholders = deepened.stream()
                .filter(c -> c.getBlockedBy() != null).collect(Collectors.toList());
        List<CandidateOperation> rankable = deepened.stream()
                .filter(c -> c.getBlockedBy() == null).collect(Collectors.toList());

        // 9. Rank
        List<CandidateOperation> ranked = new ArrayList<>(
                runSimpleRank(rankable, priorityNames, hotspots, qualityScores));
        ranked.addAll(placeholders);

        // 10. Apply architect constraints
        ranked = applyConstraints(ranked);

        // 11. Build result
        List<OperationType> tentativeRanking = ranked.stream()
                .map(CandidateOperation::getOperation)
                .collect(Collectors.toList());

        AnalysisResult result = AnalysisResult.builder()
                .snapshotId(snapshot.getSnapshotId())
                .analyzedAt(Instant.now())
                .qualityPriorities(qaOrder)
                .structuralEvidence(structural)
                .runtimeEvidence(runtime)
                .consistencyEvidence(consistency)
                .crossVersionClues(crossVer)
                .hotspots(hotspots)
                .candidateOperations(ranked)
                .tentativeRanking(tentativeRanking)
                .analyzePlanBoundary(PLAN_BOUNDARY)
                .finalRecommendation(buildRecommendation(ranked, priorityNames))
                .analysisConfig(config)
                .qualityScores(qualityScores)
                .activeConstraints(constraintService.getAllSnapshots())
                .build();

        log.info("Analyze: {} hotspot(s), {} candidate(s), top={}",
                hotspots.size(), ranked.size(),
                ranked.isEmpty() ? "none" : ranked.get(0).getOperation());

        // Persist — the effective priority names (config defaults included, custom QAs preserved)
        String analysisId = persistResult(result, priorityNames);
        result.setAnalysisId(analysisId);

        if (request.getIterationId() != null && !request.getIterationId().isBlank()) {
            linkToIteration(request.getIterationId(), analysisId);
        }

        return Optional.of(result);
    }

    // ─── Ranking ──────────────────────────────────────────────────────────────

    private List<CandidateOperation> runSimpleRank(List<CandidateOperation> raw,
                                                    List<String> priorityNames,
                                                    List<Hotspot> hotspots,
                                                    List<QualityAttributeScore> qualityScores) {
        // Resolve QA entities in priority order
        List<QualityAttributeEntity> qaOrder = new ArrayList<>();
        for (String name : priorityNames) {
            qualityAttributeRepository.findByName(name.toUpperCase()).ifPresent(qaOrder::add);
        }
        if (qaOrder.isEmpty()) {
            // Fallback: first active QA
            qaOrder = qualityAttributeRepository.findAllByActiveTrueOrderBySortOrderAsc();
            if (!qaOrder.isEmpty()) qaOrder = qaOrder.subList(0, 1);
        }

        // Load merged score matrix for this project
        Map<String, Map<String, Integer>> scores = operationQaScoringService.getScoresForProject("1");

        // Build current-score map from pre-computed scores (avoids second DB round-trip)
        Map<QualityAttribute, Double> currentScores = new EnumMap<>(QualityAttribute.class);
        for (QualityAttributeScore s : qualityScores) {
            currentScores.put(s.getAttribute(), s.getScore());
        }

        return simpleRankingStrategy.rank(raw, qaOrder, scores, hotspots, currentScores);
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    private String persistResult(AnalysisResult result, List<String> priorityNames) {
        String analysisId = UUID.randomUUID().toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Analyze: failed to serialize result — storing empty JSON: {}", e.getMessage());
            json = "{}";
        }
        String priorityStr = String.join(",", priorityNames);
        AnalysisResultEntity entity = AnalysisResultEntity.builder()
                .analysisId(analysisId)
                .snapshotId(result.getSnapshotId())
                .qualityPriorities(priorityStr)
                .resultJson(json)
                .analyzedAt(result.getAnalyzedAt())
                .build();
        analysisResultRepository.save(entity);
        log.info("Analyze: persisted analysisId={}", analysisId);
        return analysisId;
    }

    /** Retrieve a previously persisted analysis result by ID. */
    public Optional<AnalysisResult> getById(String analysisId) {
        return analysisResultRepository.findById(analysisId)
                .map(this::deserialize);
    }

    /**
     * Returns the last-used quality priorities for the given iteration, or globally
     * last-used if no iterationId is provided. Falls back to the configured project
     * default when no analysis exists yet (or an old row recorded an empty list).
     */
    public Optional<List<String>> getLastPriorities(String iterationId) {
        Optional<AnalysisResultEntity> entity;
        if (iterationId != null && !iterationId.isBlank()) {
            entity = loopIterationRepository.findById(iterationId)
                    .map(it -> it.getAnalysisId())
                    .filter(id -> id != null && !id.isBlank())
                    .flatMap(analysisResultRepository::findById);
        } else {
            entity = analysisResultRepository.findTopByOrderByAnalyzedAtDesc();
        }
        return entity.map(e -> parsePriorityCsv(e.getQualityPriorities()))
                .filter(list -> !list.isEmpty())
                .or(() -> Optional.of(analysisConfigService.current().getDefaultQualityPriorities()));
    }

    /** Maps a QA name to its enum value, or null for custom catalog QAs. */
    private static QualityAttribute toQualityAttributeOrNull(String name) {
        try {
            return QualityAttribute.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> parsePriorityCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.asList(csv.split(","));
    }

    private AnalysisResult deserialize(AnalysisResultEntity entity) {
        try {
            AnalysisResult r = objectMapper.readValue(entity.getResultJson(), AnalysisResult.class);
            r.setAnalysisId(entity.getAnalysisId());
            dropUnsupportedOperations(r, entity.getAnalysisId());
            return r;
        } catch (Exception e) {
            log.warn("Analyze: failed to deserialize analysisId={}: {}", entity.getAnalysisId(), e.getMessage());
            return AnalysisResult.builder()
                    .analysisId(entity.getAnalysisId())
                    .snapshotId(entity.getSnapshotId())
                    .build();
        }
    }

    /**
     * Strips candidates whose operation is no longer in the catalogue, or is not enabled by
     * {@code rt.analyze.enabled-operations}.
     *
     * <p>An analysis stored while the catalogue was wider deserializes those entries with a null
     * operation (the shared mapper reads unknown enum values as null instead of throwing, so one
     * retired name cannot take the whole stored analysis down). They are dropped here rather than
     * rendered as an operation nothing can plan or execute. Disabled operations are dropped for the
     * same reason: an analysis stored before the operation was disabled must not keep offering it.
     */
    private void dropUnsupportedOperations(AnalysisResult r, String analysisId) {
        int before = size(r.getCandidateOperations());
        r.setCandidateOperations(withKnownOperation(r.getCandidateOperations()));
        if (r.getTentativeRanking() != null) {
            r.setTentativeRanking(r.getTentativeRanking().stream()
                    .filter(thresholds::isEnabled).collect(Collectors.toList()));
        }
        int after = size(r.getCandidateOperations());
        if (after < before) {
            log.info("Analyze: analysisId={} — dropped {} candidate(s) naming a retired or disabled operation",
                    analysisId, before - after);
        }
    }

    private List<CandidateOperation> withKnownOperation(List<CandidateOperation> candidates) {
        if (candidates == null) return null;
        return candidates.stream()
                .filter(c -> c != null && thresholds.isEnabled(c.getOperation()))
                .collect(Collectors.toList());
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /**
     * Lazily links an analysis to a LoopIteration — uses ApplicationContext to avoid
     * a circular dependency with LoopIterationService (which depends on AnalyzeService).
     */
    private void linkToIteration(String iterationId, String analysisId) {
        try {
            var loopSvc = applicationContext.getBean(
                    "loopIterationService",
                    org.anonymous.msrefactor.rt.knowledge.service.LoopIterationService.class);
            loopSvc.linkAnalysis(iterationId, analysisId);
        } catch (Exception e) {
            log.warn("Analyze: could not link analysisId={} to iterationId={}: {}",
                    analysisId, iterationId, e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Annotates each candidate with constraint information and partitions the list
     * so unblocked candidates come first, then blocked ones at the end.
     */
    private List<CandidateOperation> applyConstraints(List<CandidateOperation> candidates) {
        List<CandidateOperation> unblocked = new ArrayList<>();
        List<CandidateOperation> blocked   = new ArrayList<>();

        for (CandidateOperation candidate : candidates) {
            // Selection-time blocked placeholders already carry a blockedBy reason — keep them blocked
            // even if no stored constraint matches their (op, target, secondary) tuple.
            String preBlocked = candidate.getBlockedBy();

            String op        = candidate.getOperation() != null ? candidate.getOperation().name() : null;
            String target    = candidate.getTargetService();
            String secondary = candidate.getSecondaryTarget();

            List<RefactorConstraintEntity> matching =
                    constraintService.findApplicableTo(op, target, secondary, candidate.getAffectedEntities());

            if (matching.isEmpty()) {
                if (preBlocked != null) blocked.add(candidate);
                else                    unblocked.add(candidate);
                continue;
            }

            List<String> blockReasons   = new ArrayList<>();
            List<String> warningReasons = new ArrayList<>();
            List<String> prerequisites  = new ArrayList<>();
            if (preBlocked != null) blockReasons.add(preBlocked);
            for (RefactorConstraintEntity c : matching) {
                if (c.isBlocking()) blockReasons.add(c.getReason());
                else                warningReasons.add(c.getReason());
                // A sibling merge recorded the sequence that would make this pair reachable. Surface
                // it as the candidate's dependencies so the plan document and the candidate list show
                // a path forward rather than a dead end.
                if (c.getPrerequisiteChain() != null && !c.getPrerequisiteChain().isBlank()) {
                    for (String step : c.getPrerequisiteChain().split(",")) {
                        String trimmed = step.strip();
                        if (!trimmed.isEmpty() && !prerequisites.contains(trimmed)) {
                            prerequisites.add(trimmed);
                        }
                    }
                }
            }

            CandidateOperation annotated = candidate.toBuilder()
                    .blockedBy(blockReasons.isEmpty() ? null : String.join("; ", blockReasons))
                    .warnings(warningReasons.isEmpty() ? null : warningReasons)
                    .dependencies(mergeDependencies(prerequisites, candidate.getDependencies()))
                    .build();

            if (!blockReasons.isEmpty()) blocked.add(annotated);
            else                         unblocked.add(annotated);
        }

        List<CandidateOperation> result = new ArrayList<>(unblocked);
        result.addAll(blocked);
        return result;
    }

    /**
     * Unions the two channels that feed a candidate's {@code dependencies}, reactive first.
     *
     * <p><b>Reactive</b> prerequisites come from a {@code <!-- REFACTOR_PREREQUISITE -->} block: a
     * skill tried the operation, hit the wall, and named the sequence that would clear it.
     * <b>Derived</b> ones come from {@code CandidateGenerator}, deduced from what the cut would break
     * before anything is run.
     *
     * <p>This used to <em>replace</em> rather than union, so any reactive prerequisite erased every
     * derived step. It never showed, because at the time no generator produced derived steps — the
     * six construction sites all passed {@code List.of()}. Now that a split derives its own chain the
     * same candidate can carry both, and reactive goes first: it is evidence from an execution that
     * actually failed, not an inference.
     */
    static List<String> mergeDependencies(List<String> reactive, List<String> derived) {
        List<String> merged = new ArrayList<>(reactive);
        if (derived != null) {
            derived.stream().filter(d -> !merged.contains(d)).forEach(merged::add);
        }
        return merged.isEmpty() ? derived : merged;
    }

    private List<Hotspot> sortHotspotsByQaPriority(List<Hotspot> hotspots,
                                                    List<QualityAttribute> priorities,
                                                    AnalysisConfig config) {
        Map<QualityAttribute, List<HotspotType>> qaMap = config.getQaToHotspotTypes();
        if (qaMap == null || priorities.isEmpty()) return hotspots;

        Map<HotspotType, Integer> rankByType = new EnumMap<>(HotspotType.class);
        for (int i = 0; i < priorities.size(); i++) {
            List<HotspotType> types = qaMap.getOrDefault(priorities.get(i), List.of());
            for (HotspotType ht : types) rankByType.putIfAbsent(ht, i);
        }

        return hotspots.stream()
                .sorted(Comparator.comparingInt(h ->
                        rankByType.getOrDefault(h.getType(), priorities.size())))
                .collect(Collectors.toList());
    }

    private Optional<MonitorSnapshot> resolveSnapshot(AnalysisRequest request) {
        if (request.getSnapshotId() != null && !request.getSnapshotId().isBlank()) {
            return monitorService.getById(request.getSnapshotId());
        }
        return monitorService.getLatest();
    }

    private String buildRecommendation(List<CandidateOperation> ranked,
                                       List<String> priorities) {
        if (ranked.isEmpty()) {
            return "No refactoring candidates identified. Snapshot has insufficient evidence.";
        }
        CandidateOperation top = ranked.get(0);
        String qaName = priorities.isEmpty() ? "default" : priorities.get(0);
        String metricSummary = buildMetricSummary(top);
        return String.format(
                "[%s] %s on '%s' — primaryQaScore=%+d. %s%s",
                qaName,
                top.getOperation(),
                top.getTargetService() != null ? top.getTargetService() : "system",
                top.getPrimaryQaScore(),
                top.getWhyApplicable() != null ? top.getWhyApplicable() : "",
                metricSummary.isEmpty() ? "" : " | " + metricSummary);
    }

    private String buildMetricSummary(CandidateOperation op) {
        RefactorBlueprint bp = op.getBlueprint();
        if (bp == null) return "";
        MetricImpact m = bp.getMetricImpact();
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        if (m.getP95LatencyMsBefore() != null && m.getP95LatencyMsAfter() != null) {
            double pct = (m.getP95LatencyMsAfter() - m.getP95LatencyMsBefore()) / m.getP95LatencyMsBefore() * 100;
            sb.append(String.format("p95 %+.0f%%", pct));
        }
        if (m.getM2Before() != null && m.getM2After() != null) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(String.format("M2 %.3f→%.3f", m.getM2Before(), m.getM2After()));
        }
        if (m.getCompensationRateBefore() != null && m.getCompensationRateAfter() != null) {
            if (!sb.isEmpty()) sb.append(", ");
            double pct = (m.getCompensationRateAfter() - m.getCompensationRateBefore()) / m.getCompensationRateBefore() * 100;
            sb.append(String.format("compensation %+.0f%%", pct));
        }
        return sb.toString();
    }
}
