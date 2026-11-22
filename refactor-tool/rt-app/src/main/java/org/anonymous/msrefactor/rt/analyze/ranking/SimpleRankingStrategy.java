package org.anonymous.msrefactor.rt.analyze.ranking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.persistence.QualityAttributeEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Transparent, user-controlled ranking algorithm (SIMPLE_RANK mode).
 *
 * <p>Steps:
 * <ol>
 *   <li>Filter — keep only candidates with {@code score(op, primaryQA) > 0}. When that leaves
 *       nothing, every candidate is ranked anyway and carries a warning saying none of them
 *       improves the primary QA: with the catalogue narrowed to merge/split, an empty list would
 *       hide the only feasible operations instead of telling the operator about the trade-off.</li>
 *   <li>Service severity — use the max hotspot severity among hotspots that implicate
 *       the candidate's targetService.</li>
 *   <li>Sort — (service severity desc, primaryQA score desc, secondaryQA score desc,
 *       tertiaryQA score desc).</li>
 *   <li>Annotate — set {@code primaryQaScore} and {@code rankReason} on each candidate.</li>
 * </ol>
 *
 * <p>Constraint application and result capping (top 20) are handled by the caller
 * ({@code AnalyzeService.runSimpleRank}).
 */
@Slf4j
@Component
public class SimpleRankingStrategy {

    /** Spring replaces this with the bean; the default keeps direct `new` usable in unit tests. */
    @Autowired(required = false)
    private AnalysisThresholdsConfig thresholds = new AnalysisThresholdsConfig();


    /**
     * Ranks candidates using the simple transparent algorithm.
     *
     * @param candidates  raw candidates from CandidateGenerator
     * @param qaOrder     QA entities in priority order (index 0 = primary)
     * @param scores      merged score matrix: operation → qaId → score
     * @param hotspots    detected hotspots (used for service severity)
     * @return filtered, sorted, annotated list (up to maxRankedResults)
     */
    public List<CandidateOperation> rank(
            List<CandidateOperation> candidates,
            List<QualityAttributeEntity> qaOrder,
            Map<String, Map<String, Integer>> scores,
            List<Hotspot> hotspots) {

        if (qaOrder.isEmpty()) {
            log.warn("SimpleRanking: no QA priorities provided — returning empty list");
            return List.of();
        }

        String primaryQaId = qaOrder.get(0).getId();
        String primaryQaName = qaOrder.get(0).getName();

        // Step 1: Filter — primary QA score must be > 0
        List<CandidateOperation> active = candidates.stream()
                .filter(c -> getScore(scores, c.getOperation() != null ? c.getOperation().name() : "", primaryQaId) > 0)
                .collect(java.util.stream.Collectors.toList());

        log.info("SimpleRanking: {}/{} candidates pass primaryQA={} filter",
                active.size(), candidates.size(), primaryQaName);

        boolean fallback = active.isEmpty() && !candidates.isEmpty();
        if (fallback) {
            log.info("SimpleRanking: no candidate improves {} — ranking all {} as fallback",
                    primaryQaName, candidates.size());
            active = new ArrayList<>(candidates);
        }
        if (active.isEmpty()) return List.of();

        // Step 2 & 3: Sort
        active.sort(buildComparator(qaOrder, scores, hotspots));

        // Step 4: Annotate with primaryQaScore and rankReason
        List<CandidateOperation> annotated = new ArrayList<>();
        for (CandidateOperation c : active) {
            String opName = c.getOperation() != null ? c.getOperation().name() : "";
            int primaryScore = getScore(scores, opName, primaryQaId);
            double severity = computeSeverity(c, hotspots);

            String reason = buildRankReason(c, qaOrder, scores, severity, primaryQaName);
            CandidateOperation.CandidateOperationBuilder b = c.toBuilder()
                    .primaryQaScore(primaryScore)
                    .rankReason(fallback ? "[fallback] " + reason : reason);
            if (fallback) b.warnings(withWarning(c.getWarnings(), fallbackWarning(primaryQaName)));
            annotated.add(b.build());
        }

        return annotated.subList(0, Math.min(thresholds.getMaxRankedResults(), annotated.size()));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Prefix the frontend matches to show the "none improves the primary QA" notice. */
    public static final String FALLBACK_WARNING_PREFIX = "No enabled operation improves ";

    private static String fallbackWarning(String primaryQaName) {
        return FALLBACK_WARNING_PREFIX + primaryQaName
                + " — ranked by hotspot severity and the remaining QA priorities as a fallback.";
    }

    private static List<String> withWarning(List<String> existing, String warning) {
        List<String> out = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        out.add(warning);
        return out;
    }

    private Comparator<CandidateOperation> buildComparator(
            List<QualityAttributeEntity> qaOrder,
            Map<String, Map<String, Integer>> scores,
            List<Hotspot> hotspots) {

        Comparator<CandidateOperation> cmp = Comparator
                .comparingDouble((CandidateOperation c) -> computeSeverity(c, hotspots))
                .reversed();

        for (QualityAttributeEntity qa : qaOrder) {
            final String qaId = qa.getId();
            cmp = cmp.thenComparing(
                    Comparator.comparingInt((CandidateOperation c) ->
                            getScore(scores, c.getOperation() != null ? c.getOperation().name() : "", qaId))
                            .reversed());
        }
        return cmp;
    }

    private double computeSeverity(CandidateOperation candidate, List<Hotspot> hotspots) {
        String target = candidate.getTargetService();
        String secondary = candidate.getSecondaryTarget();
        double max = 0.0;
        for (Hotspot h : hotspots) {
            List<String> affected = h.getAffectedServices();
            if (affected == null) continue;
            boolean matches = (target != null && affected.contains(target))
                    || (secondary != null && affected.contains(secondary));
            if (matches && h.getSeverity() > max) {
                max = h.getSeverity();
            }
        }
        return max;
    }

    private int getScore(Map<String, Map<String, Integer>> scores, String operation, String qaId) {
        Map<String, Integer> qaScores = scores.get(operation);
        if (qaScores == null) return 0;
        return qaScores.getOrDefault(qaId, 0);
    }

    private String buildRankReason(CandidateOperation c,
                                   List<QualityAttributeEntity> qaOrder,
                                   Map<String, Map<String, Integer>> scores,
                                   double severity,
                                   String primaryQaName) {
        String opName = c.getOperation() != null ? c.getOperation().name() : "?";
        String target = c.getTargetService() != null ? c.getTargetService() : "system";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Service '%s' (hotspot severity=%.0f%%)", target, severity * 100));
        sb.append("; ").append(opName).append(" scores:");
        for (QualityAttributeEntity qa : qaOrder) {
            int s = getScore(scores, opName, qa.getId());
            if (s != 0) {
                sb.append(String.format(" %s=%+d", qa.getName(), s));
            }
        }
        return sb.toString();
    }

    // ─── Deficit-based overload ───────────────────────────────────────────────

    /**
     * Ranks candidates using quality attribute deficit scores.
     *
     * <p>When {@code currentScores} is non-empty, each candidate's rank score is:
     * <pre>rankScore = deficit × impact</pre>
     * where {@code deficit = 1 - currentScores[primaryQA]} and
     * {@code impact = max(matrixScore, 0) / 2.0}.
     *
     * <p>Falls back to the standard comparator-based sort when {@code currentScores} is empty.
     */
    public List<CandidateOperation> rank(
            List<CandidateOperation> candidates,
            List<QualityAttributeEntity> qaOrder,
            Map<String, Map<String, Integer>> scores,
            List<Hotspot> hotspots,
            Map<QualityAttribute, Double> currentScores) {

        if (currentScores == null || currentScores.isEmpty()) {
            return rank(candidates, qaOrder, scores, hotspots);
        }

        if (qaOrder.isEmpty()) {
            log.warn("SimpleRanking(deficit): no QA priorities — returning empty list");
            return List.of();
        }

        String primaryQaId   = qaOrder.get(0).getId();
        String primaryQaName = qaOrder.get(0).getName();

        // Resolve primary QA to enum for deficit lookup
        QualityAttribute primaryEnum = tryParseQA(primaryQaName);
        double deficit = primaryEnum != null
                ? Math.max(1.0 - currentScores.getOrDefault(primaryEnum, 0.5), 0.0)
                : 0.5;

        // Step 1: Filter — primary QA matrix score must be > 0 (same rule as standard path)
        List<CandidateOperation> active = candidates.stream()
                .filter(c -> getScore(scores, c.getOperation() != null ? c.getOperation().name() : "", primaryQaId) > 0)
                .collect(java.util.stream.Collectors.toList());

        log.info("SimpleRanking(deficit): {}/{} pass primaryQA={} filter, deficit={}",
                active.size(), candidates.size(), primaryQaName, String.format("%.2f", deficit));

        // Every rankScore would be 0 (impact clamps negatives), so the deficit order says nothing;
        // the comparator path orders by severity and the remaining QA priorities instead.
        if (active.isEmpty() && !candidates.isEmpty()) {
            return rank(candidates, qaOrder, scores, hotspots);
        }
        if (active.isEmpty()) return List.of();

        // Step 2: Sort by rankScore descending
        active.sort(Comparator.comparingDouble((CandidateOperation c) -> {
            String opName = c.getOperation() != null ? c.getOperation().name() : "";
            int matrixScore = getScore(scores, opName, primaryQaId);
            double impact = Math.max(matrixScore, 0) / 2.0;
            return deficit * impact;
        }).reversed());

        // Step 3: Annotate
        List<CandidateOperation> annotated = new ArrayList<>();
        for (CandidateOperation c : active) {
            String opName = c.getOperation() != null ? c.getOperation().name() : "";
            int matrixScore = getScore(scores, opName, primaryQaId);
            double impact = Math.max(matrixScore, 0) / 2.0;
            double rankScore = deficit * impact;

            String reason = String.format(
                    "%s (deficit=%.0f%%, impact=%.0f%%) → rankScore=%.3f",
                    c.getTargetService() != null ? c.getTargetService() : "system",
                    deficit * 100, impact * 100, rankScore);

            annotated.add(c.toBuilder()
                    .primaryQaScore(matrixScore)
                    .rankReason(reason)
                    .build());
        }

        return annotated.subList(0, Math.min(thresholds.getMaxRankedResults(), annotated.size()));
    }

    private QualityAttribute tryParseQA(String name) {
        try {
            return QualityAttribute.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
