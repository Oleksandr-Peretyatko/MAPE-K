package org.anonymous.msrefactor.rt.plan.service;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.AggregateSnapshot;
import org.anonymous.msrefactor.rt.analyze.EntityMove;
import org.anonymous.msrefactor.rt.analyze.InvariantChange;
import org.anonymous.msrefactor.rt.analyze.MetricImpact;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;
import org.anonymous.msrefactor.rt.plan.dto.PlanRequest;

import java.time.Instant;
import java.util.List;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;

/**
 * Builds a human-readable Markdown document for a refactoring plan.
 * All content is derived from the {@link PlanRequest} and its embedded
 * {@link RefactorBlueprint} — no external calls are made.
 */
@Component
public class PlanDocumentGenerator {

    public String generate(PlanRequest req, String skillInvocation) {
        RefactorBlueprint bp = req.blueprint();
        StringBuilder sb = new StringBuilder();

        header(sb, req);
        overview(sb, bp);
        structure(sb, bp);
        entityMoves(sb, bp);
        invariantChanges(sb, bp);
        implementationSteps(sb, bp);
        skillInvocationSection(sb, skillInvocation);
        validation(sb);
        deploy(sb);
        rollback(sb);
        metricImpact(sb, bp);

        return sb.toString();
    }

    // ─── Sections ─────────────────────────────────────────────────────────────

    private void header(StringBuilder sb, PlanRequest req) {
        String target = req.targetService();
        if (req.secondaryTarget() != null && !req.secondaryTarget().isBlank()) {
            target += " → " + req.secondaryTarget();
        }
        sb.append("# Refactoring Plan: ").append(req.operation()).append(" — ").append(target).append("\n\n");
        sb.append("**Generated:** ").append(Instant.now()).append("  \n");
        sb.append("**Snapshot:** ").append(nullSafe(req.snapshotId(), "—")).append("  \n");
        sb.append("**Operation:** ").append(req.operation()).append("  \n");
        sb.append("**Target:** ").append(target).append("  \n");
        sb.append("---\n\n");
    }

    private void overview(StringBuilder sb, RefactorBlueprint bp) {
        sb.append("## Overview\n\n");
        if (bp != null && bp.getSummary() != null) {
            sb.append(bp.getSummary()).append("\n\n");
        } else {
            sb.append("_No summary available._\n\n");
        }
    }

    private void structure(StringBuilder sb, RefactorBlueprint bp) {
        if (bp == null) return;
        List<AggregateSnapshot> before = bp.getBefore();
        List<AggregateSnapshot> after  = bp.getAfter();
        if (isEmpty(before) && isEmpty(after)) return;

        sb.append("## Current → Proposed Structure\n\n");
        sb.append("| Aggregate (Before) | Entities | Aggregate (After) | Entities |\n");
        sb.append("|--------------------|----------|-------------------|----------|\n");

        int rows = Math.max(size(before), size(after));
        for (int i = 0; i < rows; i++) {
            AggregateSnapshot b = i < size(before) ? before.get(i) : null;
            AggregateSnapshot a = i < size(after)  ? after.get(i)  : null;
            sb.append("| ").append(aggregateName(b))
              .append(" | ").append(entityList(b))
              .append(" | ").append(aggregateName(a)).append(a != null && a.isNew() ? " *(new)*" : "")
              .append(" | ").append(entityList(a))
              .append(" |\n");
        }
        sb.append("\n");
    }

    private void entityMoves(StringBuilder sb, RefactorBlueprint bp) {
        if (bp == null || isEmpty(bp.getEntityMoves())) return;
        sb.append("## Entity Moves\n\n");
        for (EntityMove m : bp.getEntityMoves()) {
            sb.append("- **").append(m.getEntityName()).append("**: `")
              .append(m.getFromAggregate()).append("` → `").append(m.getToAggregate()).append("`");
            if (m.getRationale() != null) sb.append(" — ").append(m.getRationale());
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void invariantChanges(StringBuilder sb, RefactorBlueprint bp) {
        if (bp == null || isEmpty(bp.getInvariantChanges())) return;
        sb.append("## Invariant Changes\n\n");
        for (InvariantChange c : bp.getInvariantChanges()) {
            sb.append("- **").append(c.getInvariantName()).append("** (`")
              .append(c.getChangeType()).append("`)");
            if (c.getRationale() != null) sb.append(": ").append(c.getRationale());
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void implementationSteps(StringBuilder sb, RefactorBlueprint bp) {
        sb.append("## Implementation Steps\n\n");
        if (bp != null && !isEmpty(bp.getImplementationSteps())) {
            int n = 1;
            for (String step : bp.getImplementationSteps()) {
                sb.append(n++).append(". ").append(step).append("\n");
            }
        } else {
            sb.append("_No implementation steps defined._\n");
        }
        sb.append("\n");
    }

    private void skillInvocationSection(StringBuilder sb, String skillInvocation) {
        sb.append("## Skill Invocation\n\n");
        sb.append("The following Claude Code skill command implements this refactoring:\n\n");
        sb.append("```\n").append(nullSafe(skillInvocation, "—")).append("\n```\n\n");
    }

    private void validation(StringBuilder sb) {
        sb.append("## Validation\n\n");
        sb.append("After the refactoring is applied by the Execute phase, run the full test suite:\n\n");
        sb.append("```bash\n");
        sb.append("cd ").append(AppTargetConstants.PATH).append("\n");
        sb.append("mvn clean -Ptest-sagas test\n");
        sb.append("```\n\n");
        sb.append("A successful refactoring produces **zero test failures**. Pay particular attention to:\n\n");
        sb.append("- Saga compensation flows (`AddParticipantFunctionalitySagas` and related)\n");
        sb.append("- Invariant method signatures — renames or moves will cause compilation failures\n\n");
    }

    private void deploy(StringBuilder sb) {
        sb.append("## Deploy\n\n");
        sb.append("Once all tests pass, trigger the Execute phase to build and deploy the new version:\n\n");
        sb.append("```\nPOST /execute/run\n```\n\n");
        sb.append("Monitor build progress at `GET /execute/status` until status reaches `DEPLOYED`.\n\n");
    }

    private void rollback(StringBuilder sb) {
        sb.append("## Rollback\n\n");
        sb.append("If the build fails, tests regress, or the deployment is unstable, roll back immediately:\n\n");
        sb.append("```\nPOST /execute/rollback\n```\n\n");
        sb.append("This reverts to the previous Docker image tag and restores the pre-refactoring git branch.\n");
        sb.append("No data migration is required — the database schema is unchanged by structural refactoring.\n\n");
    }

    private void metricImpact(StringBuilder sb, RefactorBlueprint bp) {
        if (bp == null || bp.getMetricImpact() == null) return;
        MetricImpact mi = bp.getMetricImpact();

        sb.append("## Expected Metric Impact\n\n");
        sb.append("| Metric | Before | After | Direction |\n");
        sb.append("|--------|--------|-------|-----------|\n");

        appendMetricRow(sb, "M1 cohesion",      fmt(mi.getM1Before()),      fmt(mi.getM1After()),      direction(mi.getM1Before(), mi.getM1After(), true));
        appendMetricRow(sb, "M2 coupling",      fmt(mi.getM2Before()),      fmt(mi.getM2After()),      direction(mi.getM2Before(), mi.getM2After(), false));
        appendMetricRow(sb, "Cohesion (cluster factor)", fmt(mi.getCohesionBefore()), fmt(mi.getCohesionAfter()), direction(mi.getCohesionBefore(), mi.getCohesionAfter(), true));
        appendMetricRow(sb, "Coupling density", fmt(mi.getCouplingDensityBefore()), fmt(mi.getCouplingDensityAfter()), direction(mi.getCouplingDensityBefore(), mi.getCouplingDensityAfter(), false));
        appendMetricRow(sb, "Local step ratio", fmt(mi.getLocalStepRatioBefore()), fmt(mi.getLocalStepRatioAfter()), direction(mi.getLocalStepRatioBefore(), mi.getLocalStepRatioAfter(), true));
        appendMetricRow(sb, "Entity count",     mi.getEntityCountBefore(),  mi.getEntityCountAfter(),  null);
        appendMetricRow(sb, "External rels (σ)",fmt(mi.getExternalRelsBefore()), fmt(mi.getExternalRelsAfter()), direction(toDouble(mi.getExternalRelsBefore()), toDouble(mi.getExternalRelsAfter()), false));
        appendMetricRow(sb, "p95 latency (ms)", fmt(mi.getP95LatencyMsBefore()), fmt(mi.getP95LatencyMsAfter()), direction(mi.getP95LatencyMsBefore(), mi.getP95LatencyMsAfter(), false));
        appendMetricRow(sb, "Compensation rate", fmt(mi.getCompensationRateBefore()), fmt(mi.getCompensationRateAfter()), direction(mi.getCompensationRateBefore(), mi.getCompensationRateAfter(), false));

        if (mi.getEstimationNote() != null) {
            sb.append("\n> ").append(mi.getEstimationNote()).append("\n");
        }
        sb.append("\n");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void appendMetricRow(StringBuilder sb, String label, String before, String after, String dir) {
        if ("—".equals(before) && "—".equals(after)) return;
        sb.append("| ").append(label)
          .append(" | ").append(nullSafe(before, "—"))
          .append(" | ").append(nullSafe(after, "—"))
          .append(" | ").append(nullSafe(dir, "→"))
          .append(" |\n");
    }

    private String aggregateName(AggregateSnapshot s) {
        return s == null ? "" : nullSafe(s.getAggregateName(), "—");
    }

    private String entityList(AggregateSnapshot s) {
        if (s == null || isEmpty(s.getEntities())) return "—";
        return String.join(", ", s.getEntities());
    }

    private String direction(Double before, Double after, boolean higherIsBetter) {
        if (before == null || after == null) return "→";
        int cmp = Double.compare(after, before);
        if (cmp == 0) return "→ (unchanged)";
        boolean improved = higherIsBetter ? cmp > 0 : cmp < 0;
        return improved ? "↑ (better)" : "↓ (worse)";
    }

    private String fmt(Double d)  { return d == null ? "—" : String.format("%.3f", d); }
    private String fmt(Integer i) { return i == null ? "—" : i.toString(); }

    private Double toDouble(Integer i) { return i == null ? null : i.doubleValue(); }

    private String nullSafe(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }

    private boolean isEmpty(List<?> list) { return list == null || list.isEmpty(); }

    private int size(List<?> list) { return list == null ? 0 : list.size(); }
}
