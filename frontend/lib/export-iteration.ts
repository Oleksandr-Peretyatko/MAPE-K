import * as XLSX from "xlsx"
import type {
  LoopIterationDetail,
  PlanResponse,
  ArchGraph,
  DiscoveryGraph,
  AppSettings,
} from "@/lib/api"

export interface ExportData {
  detail: LoopIterationDetail
  plan: PlanResponse | null
  archGraph: ArchGraph | null
  discoveryGraph: DiscoveryGraph | null
  settings: AppSettings | null
}

type Row = unknown[]

export function buildIterationWorkbook(data: ExportData): XLSX.WorkBook {
  const wb = XLSX.utils.book_new()
  const { detail, plan, archGraph, discoveryGraph, settings } = data

  function addSheet(name: string, rows: Row[]) {
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(rows), name)
  }

  // ── Overview ────────────────────────────────────────────────────────────────
  addSheet("Overview", [
    ["Field", "Value"],
    ["Iteration ID", detail.iterationId],
    ["Number", detail.iterationNumber],
    ["Name", detail.name],
    ["Status", detail.status],
    ["Code Version ID", detail.codeVersionId ?? "—"],
    ["Code Version Label", detail.codeVersionLabel ?? "—"],
    ["Started At", detail.startedAt],
    ["Completed At", detail.completedAt ?? "—"],
    ["Notes", detail.notes ?? "—"],
    [],
    ["Phase IDs", ""],
    ["Snapshot ID", detail.snapshotId ?? "—"],
    ["Analysis ID", detail.analysisId ?? "—"],
    ["Plan ID", detail.planId ?? "—"],
    ["Execute ID", detail.executeRefactorId ?? "—"],
  ])

  // ── Aggregates ──────────────────────────────────────────────────────────────
  const aggRows: Row[] = [[
    "Service", "Avg Read (ms)", "Avg Write (ms)", "P95 (ms)",
    "Error Rate", "Op Freq (/min)", "Bandwidth (B/s)", "R/W Ratio",
    "Total Spans", "Latency Source",
  ]]
  for (const m of Object.values(detail.snapshot?.aggregateMetrics ?? {})) {
    aggRows.push([
      m.serviceName, m.avgReadLatencyMs, m.avgWriteLatencyMs, m.p95LatencyMs,
      m.errorRate, m.operationFrequency, m.bandwidthBytesPerSec, m.readWriteRatio,
      m.totalSpanCount, m.latencySource ?? "—",
    ])
  }
  addSheet("Aggregates", aggRows)

  // ── SAGA ────────────────────────────────────────────────────────────────────
  const sagaRows: Row[] = [[
    "Workflow", "Exec Count", "Avg Latency (ms)", "P95 Latency (ms)",
    "Avg Cross-Svc Calls", "Compensation Rate", "Involved Services",
  ]]
  for (const m of Object.values(detail.snapshot?.sagaMetrics ?? {})) {
    sagaRows.push([
      m.workflowName, m.executionCount, m.avgTotalLatencyMs, m.p95TotalLatencyMs,
      m.avgCrossServiceCalls, m.compensationRate, m.involvedServices.join("; "),
    ])
  }
  addSheet("SAGA", sagaRows)

  // ── Locust ──────────────────────────────────────────────────────────────────
  const locustRows: Row[] = [[
    "Scenario", "Source File", "Requests", "Failures", "Error Rate",
    "Median (ms)", "P95 (ms)", "P99 (ms)", "RPS",
  ]]
  for (const s of detail.snapshot?.locustScenarios ?? []) {
    locustRows.push([
      s.scenarioName, s.sourceFile ?? "—", s.requestCount, s.failureCount, s.errorRate,
      s.medianResponseMs, s.p95ResponseMs, s.p99ResponseMs, s.requestsPerSec,
    ])
  }
  addSheet("Locust", locustRows)

  // ── Static Analysis ─────────────────────────────────────────────────────────
  const staticRows: Row[] = [[
    "Version", "M1 (global)", "M2 (global)", "Service", "Entity Count",
    "Internal Rels", "M1 Contrib", "External Relations", "Entities", "Invariants",
  ]]
  for (const sv of detail.snapshot?.staticVersions ?? []) {
    for (const svc of Object.values(sv.metrics.services ?? {})) {
      staticRows.push([
        sv.version, sv.metrics.m1, sv.metrics.m2,
        svc.serviceName, svc.entityCount, svc.internalRelations, svc.m1Contribution,
        Object.entries(svc.externalRelations).map(([k, v]) => `${k}:${v}`).join("; "),
        svc.entityNames?.join("; ") ?? "—",
        svc.invariants?.map(i => i.name).join("; ") ?? "—",
      ])
    }
  }
  addSheet("Static Analysis", staticRows)

  // ── Hotspots ────────────────────────────────────────────────────────────────
  const hotspotRows: Row[] = [["Type", "Severity", "Affected Services", "Evidence"]]
  for (const h of detail.analysis?.hotspots ?? []) {
    hotspotRows.push([h.type, h.severity, h.affectedServices.join("; "), h.evidence])
  }
  addSheet("Hotspots", hotspotRows)

  // ── Candidates ──────────────────────────────────────────────────────────────
  const candRows: Row[] = [[
    "Operation", "Target", "Secondary Target", "Primary QA Score",
    "Primary QA", "Rank Reason",
    "Why Applicable", "Preconditions", "Risks", "Dependencies",
    "Affected Entities", "Blocked By", "Warnings",
  ]]
  for (const c of detail.analysis?.candidateOperations ?? []) {
    candRows.push([
      c.operation, c.targetService, c.secondaryTarget ?? "—",
      c.primaryQaScore ?? "—",
      c.primaryQaTarget, c.rankReason ?? "—",
      c.whyApplicable,
      c.preconditions.join("; "), c.risks.join("; "), c.dependencies.join("; "),
      c.affectedEntities?.join("; ") ?? "—",
      c.blockedBy ?? "—",
      c.warnings?.join("; ") ?? "—",
    ])
  }
  addSheet("Candidates", candRows)

  // ── Constraints ─────────────────────────────────────────────────────────────
  const constraintRows: Row[] = [["Type", "Operation", "Target Service", "Secondary", "Entity", "Reason", "Created At"]]
  const activeConstraints = detail.analysis?.activeConstraints ?? []
  if (activeConstraints.length > 0) {
    for (const c of activeConstraints) {
      constraintRows.push([
        c.blocking ? "BLOCKING" : "WARNING",
        c.operation ?? "ANY",
        c.targetService ?? "ANY",
        c.secondaryTarget ?? "ANY",
        c.targetEntity ?? "ANY",
        c.reason,
        c.createdAt ?? "—",
      ])
    }
  } else {
    constraintRows.push(["—", "No constraints were active during this analysis", "", "", "", "", ""])
  }
  addSheet("Constraints", constraintRows)

  // ── Recommendation ──────────────────────────────────────────────────────────
  const a = detail.analysis
  addSheet("Recommendation", [
    ["Field", "Value"],
    ["Final Recommendation", a?.finalRecommendation ?? "—"],
    ["Analyze-Plan Boundary", a?.analyzePlanBoundary ?? "—"],
    ["Tentative Ranking", a?.tentativeRanking?.join(", ") ?? "—"],
    ["Quality Priorities", a?.qualityPriorities?.join(", ") ?? "—"],
    [],
    ["Structural Evidence", ""],
    ["M1 (global)", a?.structuralEvidence?.m1 ?? "—"],
    ["M2 (global)", a?.structuralEvidence?.m2 ?? "—"],
    ["Service Count", a?.structuralEvidence?.serviceCount ?? "—"],
    ["Source Version", a?.structuralEvidence?.sourceVersion ?? "—"],
    ["High Coupling Pairs", a?.structuralEvidence?.highCouplingPairs?.map(p => `${p[0]}↔${p[1]}`).join("; ") ?? "—"],
    ["Low Cohesion Services", a?.structuralEvidence?.lowCohesionServices?.join("; ") ?? "—"],
    ["High Entity Count Services", a?.structuralEvidence?.highEntityCountServices?.join("; ") ?? "—"],
    [],
    ["Runtime Evidence", ""],
    ["High Latency Services", a?.runtimeEvidence?.highLatencyServices?.join("; ") ?? "—"],
    ["Read-Heavy Services", a?.runtimeEvidence?.readHeavyServices?.join("; ") ?? "—"],
    ["High Bandwidth Services", a?.runtimeEvidence?.highBandwidthServices?.join("; ") ?? "—"],
    ["High SAGA Latency Workflows", a?.runtimeEvidence?.highSagaLatencyWorkflows?.join("; ") ?? "—"],
    [],
    ["Consistency Evidence", ""],
    ["Violation Count", a?.consistencyEvidence?.violationCount ?? "—"],
    ["Avg Compensation Rate", a?.consistencyEvidence?.avgCompensationRate ?? "—"],
    ["High Compensation Workflows", a?.consistencyEvidence?.highCompensationWorkflows?.join("; ") ?? "—"],
    [],
    ["Cross-Version Clues", ""],
    ["M1 Trend", a?.crossVersionClues?.m1Trend ?? "—"],
    ["M2 Trend", a?.crossVersionClues?.m2Trend ?? "—"],
    ["Versions Analyzed", a?.crossVersionClues?.versionsAnalyzed?.join(", ") ?? "—"],
    ["Notes", a?.crossVersionClues?.notes?.join("; ") ?? "—"],
  ])

  // ── Plan ────────────────────────────────────────────────────────────────────
  const planRows: Row[] = [
    ["Field", "Value"],
    ["Plan ID", plan?.planId ?? "—"],
    ["Status", plan?.status ?? "—"],
    ["Operation", plan?.operation ?? "—"],
    ["Target Service", plan?.targetService ?? "—"],
    ["Secondary Target", plan?.secondaryTarget ?? "—"],
    ["Skill Invocation", plan?.skillInvocation ?? "—"],
    ["Created At", plan?.createdAt ?? "—"],
  ]
  if (plan?.blueprint) {
    planRows.push([], ["Blueprint Summary", plan.blueprint.summary ?? "—"])
    if (plan.blueprint.implementationSteps?.length) {
      planRows.push([], ["Implementation Steps", ""])
      plan.blueprint.implementationSteps.forEach((s, i) => planRows.push([`Step ${i + 1}`, s]))
    }
    if (plan.blueprint.before?.length) {
      planRows.push([], ["Before (Aggregates)", "Service", "Entities", "New?"])
      plan.blueprint.before.forEach(a => planRows.push([a.aggregateName, a.serviceName, a.entities.join("; "), a.isNew ? "yes" : "no"]))
    }
    if (plan.blueprint.after?.length) {
      planRows.push([], ["After (Aggregates)", "Service", "Entities", "New?"])
      plan.blueprint.after.forEach(a => planRows.push([a.aggregateName, a.serviceName, a.entities.join("; "), a.isNew ? "yes" : "no"]))
    }
    if (plan.blueprint.entityMoves?.length) {
      planRows.push([], ["Entity Moves", "From", "To", "Rationale"])
      plan.blueprint.entityMoves.forEach(m => planRows.push([m.entityName, m.fromAggregate, m.toAggregate, m.rationale ?? "—"]))
    }
  }
  if (plan?.document) {
    planRows.push([], ["--- Plan Document ---", ""])
    plan.document.split("\n").forEach(line => planRows.push([line]))
  }
  if (plan?.enrichedDocument) {
    planRows.push([], ["--- Enriched Document ---", ""])
    plan.enrichedDocument.split("\n").forEach(line => planRows.push([line]))
  }
  addSheet("Plan", planRows)

  // ── Execute ─────────────────────────────────────────────────────────────────
  const exec = detail.execution
  const execRows: Row[] = [
    ["Field", "Value"],
    ["Execute ID", exec?.executeRefactorId ?? "—"],
    ["Version Number", exec?.versionNumber ?? "—"],
    ["Branch", exec?.branchName ?? "—"],
    ["Operation", exec?.operation ?? "—"],
    ["Target Service", exec?.targetService ?? "—"],
    ["Secondary Target", exec?.secondaryTarget ?? "—"],
    ["Status", exec?.status ?? "—"],
    ["Git Commit SHA", exec?.gitCommitSha ?? "—"],
    ["Image Tag", exec?.imageTag ?? "—"],
    ["Started At", exec?.startedAt ?? "—"],
    ["Completed At", exec?.completedAt ?? "—"],
  ]
  if (exec?.executeLog) {
    execRows.push([], ["--- Execution Log ---", ""])
    exec.executeLog.split("\n").forEach(line => execRows.push([line]))
  }
  addSheet("Execute", execRows)

  // ── Arch Graph ──────────────────────────────────────────────────────────────
  const archRows: Row[] = [["Service", "Entity Count", "Internal Rels", "M1 Contrib", "Entities", "External Relations", "Invariants"]]
  if (archGraph) {
    archRows.push(["[M1 global]", archGraph.m1], ["[M2 global]", archGraph.m2], ["[Service Count]", archGraph.serviceCount], [])
    for (const svc of archGraph.services) {
      archRows.push([
        svc.name, svc.entityCount, svc.internalRelations, svc.m1Contribution,
        svc.entities.join("; "),
        Object.entries(svc.externalRelations).map(([k, v]) => `${k}:${v}`).join("; "),
        svc.invariants.join("; "),
      ])
    }
  }
  addSheet("Arch Graph", archRows)

  // ── Discovery ───────────────────────────────────────────────────────────────
  const discRows: Row[] = [["Service", "Aggregate", "Row Type", "Name / Entity", "Ext Relation Via", "Service Operations"]]
  if (discoveryGraph) {
    for (const svc of discoveryGraph.services) {
      const ops = svc.serviceOperations.join("; ")
      if (svc.aggregates.length === 0) {
        discRows.push([svc.name, "", "service", "", "", ops])
        continue
      }
      for (const agg of svc.aggregates) {
        let firstAgg = true
        for (const entity of agg.entities) {
          discRows.push([svc.name, firstAgg ? agg.name : "", "entity", entity, "", firstAgg ? ops : ""])
          firstAgg = false
        }
        for (const inv of agg.invariants) {
          discRows.push([svc.name, firstAgg ? agg.name : "", `invariant:${inv.type}`, inv.name, "", firstAgg ? ops : ""])
          firstAgg = false
        }
        for (const rel of agg.externalRelations) {
          discRows.push([svc.name, firstAgg ? agg.name : "", "ext-relation", rel.to, rel.via, firstAgg ? ops : ""])
          firstAgg = false
        }
        if (agg.entities.length === 0 && agg.invariants.length === 0 && agg.externalRelations.length === 0) {
          discRows.push([svc.name, agg.name, "aggregate", "", "", ops])
        }
      }
    }
  }
  addSheet("Discovery", discRows)

  // ── Config ──────────────────────────────────────────────────────────────────
  const cfgRows: Row[] = [["Section", "Key", "Value"]]
  if (settings) {
    cfgRows.push(["execute", "", ""])
    for (const [k, v] of Object.entries(settings.execute)) cfgRows.push(["", k, String(v)])
    cfgRows.push([])
    cfgRows.push(["monitor", "", ""])
    for (const [k, v] of Object.entries(settings.monitor)) {
      cfgRows.push(["", k, Array.isArray(v) ? (v as string[]).join("; ") : String(v)])
    }
    cfgRows.push([])
    cfgRows.push(["analyze", "", ""])
    cfgRows.push(["", "candidateLimit", String(settings.analyze.candidateLimit)])
  }
  addSheet("Config", cfgRows)

  return wb
}
