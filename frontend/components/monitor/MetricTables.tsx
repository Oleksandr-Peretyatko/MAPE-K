"use client"

import React from "react"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Badge } from "@/components/ui/badge"
import { EmptyState } from "@/components/shared/EmptyState"
import { fmtMs, fmtPct, fmtMetric, fmtNum } from "@/lib/utils"
import { SELF_P95_MS } from "@/lib/api"
import type { AggregateMetrics, AggregateStepLatency, SagaMetrics, LocustScenario, ServiceStaticMetrics, InterServiceDependency } from "@/lib/api"
import { cn } from "@/lib/utils"
import { HelpCircle } from "lucide-react"

function Th({ children, tip }: { children: React.ReactNode; tip?: string }) {
  return (
    <th className="px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
      <div className="flex items-center gap-1">
        {children}
        {tip && (
          <Tooltip>
            <TooltipTrigger asChild>
              <HelpCircle className="h-3 w-3 cursor-help opacity-50" />
            </TooltipTrigger>
            <TooltipContent className="max-w-xs">{tip}</TooltipContent>
          </Tooltip>
        )}
      </div>
    </th>
  )
}

function Td({ children, className }: { children?: React.ReactNode; className?: string }) {
  return <td className={cn("px-3 py-2.5 text-xs", className)}>{children}</td>
}

// ─── Jaeger Runtime Metrics ───────────────────────────────────────────────────
/** Amber past the "good" bound, red past "bad" — the same bounds the score normalises against. */
function perfClass(m: AggregateMetrics): string {
  if (!m.measuredSpanCount) return "text-muted-foreground"
  if (m.p95LatencyMs >= SELF_P95_MS.bad) return "text-destructive font-medium"
  if (m.p95LatencyMs >= SELF_P95_MS.good) return "text-amber-600 dark:text-amber-500"
  return ""
}

export function JaegerMetricsTable({ data }: { data: Record<string, AggregateMetrics> | null }) {
  const rows = Object.values(data ?? {})
  if (!rows.length) return <EmptyState title="No runtime metrics" description="Collect a snapshot to see Jaeger metrics." />
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[700px]">
        <thead>
          <tr className="border-b border-border">
            <Th>Service</Th>
            <Th tip="Total spans recorded in the collection window">Spans</Th>
            <Th tip="Spans the latency was derived from — work spans only, excluding trace scaffolding">Measured</Th>
            <Th tip="Operations per second">Op/s</Th>
            <Th tip="Average read latency in milliseconds">Avg Read</Th>
            <Th tip="Average write latency in milliseconds">Avg Write</Th>
            <Th tip="95th-percentile self time — span duration minus downstream waits">p95 self</Th>
            <Th tip="Error rate — highlighted red if > 5%">Error Rate</Th>
            <Th tip="Bandwidth in bytes/sec">Bandwidth</Th>
            <Th tip="Read/Write ratio">R/W</Th>
          </tr>
        </thead>
        <tbody>
          {rows.map((m) => (
            <tr key={m.serviceName} className="border-b border-border/50 hover:bg-muted/30 transition-colors">
              <Td>
                <span className="font-mono text-foreground">{m.serviceName}</span>
                {m.latencySource && m.latencySource !== "jaeger" && (
                  <Badge variant="outline" className="ml-1.5 text-[10px] px-1 py-0 text-muted-foreground">
                    src:{m.latencySource}
                  </Badge>
                )}
              </Td>
              <Td>{fmtNum(m.totalSpanCount)}</Td>
              <Td className={m.measuredSpanCount === 0 ? "text-muted-foreground" : ""}>
                {fmtNum(m.measuredSpanCount)}
              </Td>
              <Td>{fmtNum(m.operationFrequency)}</Td>
              <Td>{fmtMs(m.avgReadLatencyMs)}</Td>
              <Td>{fmtMs(m.avgWriteLatencyMs)}</Td>
              <Td className={perfClass(m)}>{m.measuredSpanCount ? fmtMs(m.p95LatencyMs) : "—"}</Td>
              <Td className={m.errorRate > 0.05 ? "text-destructive font-medium" : ""}>{fmtPct(m.errorRate)}</Td>
              <Td>{fmtNum(Math.round(m.bandwidthBytesPerSec))} B/s</Td>
              <Td>{m.readWriteRatio?.toFixed(2) ?? "—"}</Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─── SAGA Metrics ─────────────────────────────────────────────────────────────

function StepBreakdownRow({ breakdown }: { breakdown: Record<string, AggregateStepLatency> }) {
  const entries = Object.values(breakdown)
  if (!entries.length) return null
  return (
    <tr className="border-b border-border/30 bg-muted/10">
      <td colSpan={7} className="px-3 py-1.5">
        <div className="flex flex-wrap gap-1.5 items-center">
          <span className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground mr-1">
            Aggregate steps:
          </span>
          {entries.map((e) => (
            <Tooltip key={e.aggregateName}>
              <TooltipTrigger asChild>
                <Badge
                  variant="secondary"
                  className="text-[10px] px-1.5 py-0 font-mono cursor-default"
                >
                  {e.aggregateName}: {fmtMs(e.avgStepLatencyMs)}
                </Badge>
              </TooltipTrigger>
              <TooltipContent className="text-xs">
                <div className="font-semibold mb-0.5">{e.aggregateName}</div>
                <div>Avg: {fmtMs(e.avgStepLatencyMs)}</div>
                <div>p95: {fmtMs(e.p95StepLatencyMs)}</div>
                <div>Steps: {fmtNum(e.stepCount)}</div>
              </TooltipContent>
            </Tooltip>
          ))}
        </div>
      </td>
    </tr>
  )
}

export function SagaMetricsTable({ data }: { data: Record<string, SagaMetrics> | null }) {
  const rows = Object.values(data ?? {})
  if (!rows.length) return <EmptyState title="No SAGA metrics" description="Collect a snapshot to see workflow metrics." />
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[700px]">
        <thead>
          <tr className="border-b border-border">
            <Th>Workflow</Th>
            <Th>Executions</Th>
            <Th tip="Average total latency across all saga steps">Avg Total</Th>
            <Th tip="95th-percentile total saga latency">p95 Total</Th>
            <Th tip="Average number of cross-service calls per execution">Avg XS Calls</Th>
            <Th tip="Compensation rate — highlighted orange if > 10%">Comp. Rate</Th>
            <Th>Involved Services</Th>
          </tr>
        </thead>
        <tbody>
          {rows.map((m) => (
            <React.Fragment key={m.workflowName}>
              <tr className="border-b border-border/50 hover:bg-muted/30 transition-colors">
                <Td><span className="font-mono text-foreground">{m.workflowName}</span></Td>
                <Td>{fmtNum(m.executionCount)}</Td>
                <Td>{fmtMs(m.avgTotalLatencyMs)}</Td>
                <Td>{fmtMs(m.p95TotalLatencyMs)}</Td>
                <Td>{m.avgCrossServiceCalls?.toFixed(1) ?? "—"}</Td>
                <Td className={m.compensationRate > 0.10 ? "text-warning font-medium" : ""}>{fmtPct(m.compensationRate)}</Td>
                <Td>
                  <div className="flex flex-wrap gap-1">
                    {m.involvedServices?.map((s) => (
                      <Badge key={s} variant="outline" className="text-[10px] py-0">{s}</Badge>
                    ))}
                  </div>
                </Td>
              </tr>
              {m.stepBreakdown && <StepBreakdownRow breakdown={m.stepBreakdown} />}
            </React.Fragment>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─── Locust Results ───────────────────────────────────────────────────────────
export function LocustResultsTable({ data }: { data: LocustScenario[] | null }) {
  const rows = data ?? []
  if (!rows.length) return <EmptyState title="No Locust scenario data" description="Either no load test has run since the last version switch, or the test produced no per-endpoint rows (only Aggregated). Check rt-app logs for '[locust] CSV has no per-endpoint rows' warnings." />
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[700px]">
        <thead>
          <tr className="border-b border-border">
            <Th>Scenario</Th>
            <Th>Requests</Th>
            <Th>Failures</Th>
            <Th tip="Error rate — highlighted red if > 5%">Error Rate</Th>
            <Th>Median (ms)</Th>
            <Th tip="95th-percentile response time">p95 (ms)</Th>
            <Th tip="99th-percentile response time">p99 (ms)</Th>
            <Th>Req/s</Th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr
              key={`${r.sourceFile ?? ""}-${r.scenarioName}`}
              className={`border-b border-border/50 transition-colors ${
                r.scenarioName === "Aggregated"
                  ? "bg-muted/30 font-semibold"
                  : "hover:bg-muted/30"
              }`}
            >
              <Td><span className="font-mono text-foreground">{r.scenarioName}</span></Td>
              <Td>{fmtNum(r.requestCount)}</Td>
              <Td>{fmtNum(r.failureCount)}</Td>
              <Td className={r.errorRate > 0.05 ? "text-destructive font-medium" : ""}>{fmtPct(r.errorRate)}</Td>
              <Td>{fmtMs(r.medianResponseMs)}</Td>
              <Td>{fmtMs(r.p95ResponseMs)}</Td>
              <Td>{fmtMs(r.p99ResponseMs)}</Td>
              <Td>{r.requestsPerSec?.toFixed(1) ?? "—"}</Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─── Inter-service dependency badges ──────────────────────────────────────────

function depBadgeVariant(weight: number): "secondary" | "outline" | "destructive" {
  if (weight >= 5) return "destructive"
  if (weight >= 3) return "outline"
  return "secondary"
}

function InterDepBadge({ target, dep }: { target: string; dep: InterServiceDependency }) {
  const tokens: string[] = []
  if (dep.structuralRefs)          tokens.push(`σ=${dep.structuralRefs}`)
  if (dep.eventTypes?.length)      tokens.push(`e=${dep.eventTypes.length}`)
  if (dep.functionalityNames?.length) tokens.push(`f=${dep.functionalityNames.length}`)
  tokens.push(`w=${dep.totalWeight}`)

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          variant={depBadgeVariant(dep.totalWeight)}
          className="cursor-help text-[10px] font-mono px-1.5 py-0.5 h-auto"
        >
          {target} [{tokens.join(" ")}]
        </Badge>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs text-xs" side="left">
        <p className="font-semibold mb-1">{target}</p>
        {dep.structuralRefs > 0 && (
          <p><span className="text-muted-foreground">σ structural:</span> {dep.structuralRefs}</p>
        )}
        {dep.eventTypes && dep.eventTypes.length > 0 && (
          <div>
            <p className="text-muted-foreground">e events:</p>
            <ul className="list-disc list-inside">
              {dep.eventTypes.map(e => <li key={e}>{e}</li>)}
            </ul>
          </div>
        )}
        {dep.functionalityNames && dep.functionalityNames.length > 0 && (
          <div>
            <p className="text-muted-foreground">f functionalities:</p>
            <ul className="list-disc list-inside">
              {dep.functionalityNames.map(f => <li key={f}>{f}</li>)}
            </ul>
          </div>
        )}
      </TooltipContent>
    </Tooltip>
  )
}

function InterDepCell({ svc }: { svc: ServiceStaticMetrics }) {
  const merged = svc.interServiceDependencies
  if (merged && Object.keys(merged).length > 0) {
    const sorted = Object.entries(merged).sort((a, b) => b[1].totalWeight - a[1].totalWeight)
    return (
      <div className="flex flex-wrap gap-1">
        {sorted.map(([target, dep]) => (
          <InterDepBadge key={target} target={target} dep={dep} />
        ))}
      </div>
    )
  }
  // Backward-compat: fall back to raw externalRelations counts
  const ext = svc.externalRelations ?? {}
  const entries = Object.entries(ext).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1])
  if (!entries.length) return <span className="text-muted-foreground text-xs">—</span>
  return (
    <div className="flex flex-wrap gap-1">
      {entries.map(([target, count]) => (
        <Badge key={target} variant="secondary" className="text-[10px] font-mono px-1.5 py-0.5 h-auto">
          {target} [σ={count}]
        </Badge>
      ))}
    </div>
  )
}

// ─── Static Analysis Table ─────────────────────────────────────────────────────
export function StaticAnalysisTable({
  services,
  m1,
  m2,
  m1Functional,
  m2Functional,
  serviceCount,
}: {
  services: Record<string, ServiceStaticMetrics> | null
  m1?: number
  m2?: number
  m1Functional?: number
  m2Functional?: number
  serviceCount?: number
}) {
  const rows = Object.values(services ?? {})
  if (!rows.length) return <EmptyState title="No static metrics" description="Select a version to see service metrics." />

  const totalWeight = rows.reduce((sum, s) => {
    const deps = s.interServiceDependencies ?? {}
    return sum + Object.values(deps).reduce((a, d) => a + d.totalWeight, 0)
  }, 0)
  const hasFunctional = rows.some(s => s.m1FunctionalContribution != null)

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[1050px]">
        <thead>
          <tr className="border-b border-border">
            <Th>Service</Th>
            <Th tip="Number of domain entities owned by this service (Nᵢ)">N</Th>
            <Th tip="Internal JPA relations between entities within this service (μᵢ: @OneToOne / @OneToMany)">μ</Th>
            <Th tip="M1 contribution (structural): μᵢ / Nᵢ² (JPA relations). Highlighted red if < 0.05 (low cohesion)">M1</Th>
            <Th tip="M2 contribution (structural): Σⱼ (σᵢⱼ + σⱼᵢ) / (2·Nᵢ·Nⱼ) — JPA entity refs.">M2</Th>
            {hasFunctional && (
              <>
                <Th tip="Total step count across all Functionality classes owned by this service (μ'ᵢ — functional intra-service signal).">steps</Th>
                <Th tip="M1 contribution (functionality graph): μ'ᵢ / Nᵢ² where μ'ᵢ = functionality step count. Measures operational density.">M1f</Th>
                <Th tip="M2 contribution (functionality graph): Σⱼ σ'ᵢⱼ / (2·Nᵢ·Nⱼ) where σ'ᵢⱼ = distinct Functionality classes from i calling j.">M2f</Th>
              </>
            )}
            <Th tip="Inter-service dependencies: σ=structural refs  e=event subscriptions  f=functionality calls  w=total weight. Badge color: grey ≤2  amber 3-4  red ≥5">
              Inter-service dependencies
            </Th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.serviceName} className="border-b border-border/50 hover:bg-muted/30 transition-colors">
              <Td><span className="font-mono text-foreground">{s.serviceName}</span></Td>
              <Td>{s.entityCount}</Td>
              <Td>{s.internalRelations}</Td>
              <Td className={s.m1Contribution < 0.05 ? "text-destructive font-medium" : ""}>{fmtMetric(s.m1Contribution)}</Td>
              <Td>{fmtMetric(s.m2Contribution ?? 0)}</Td>
              {hasFunctional && (
                <>
                  <Td>{s.functionalityStepCount ?? 0}</Td>
                  <Td>{fmtMetric(s.m1FunctionalContribution ?? 0)}</Td>
                  <Td>{fmtMetric(s.m2FunctionalContribution ?? 0)}</Td>
                </>
              )}
              <td className="px-3 py-2 align-top">
                <InterDepCell svc={s} />
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-border bg-muted/20">
            <Td className="font-semibold text-foreground">System</Td>
            <Td className="text-muted-foreground text-xs">{serviceCount ?? rows.length} svc</Td>
            <Td />
            <Td>
              <span className="font-mono text-xs">M1={fmtMetric(m1)}</span>
            </Td>
            <Td>
              <span className="font-mono text-xs">M2={fmtMetric(m2)}</span>
            </Td>
            {hasFunctional && (
              <>
                <Td />
                <Td>
                  <span className="font-mono text-xs text-muted-foreground">M1f={fmtMetric(m1Functional)}</span>
                </Td>
                <Td>
                  <span className="font-mono text-xs text-muted-foreground">M2f={fmtMetric(m2Functional)}</span>
                </Td>
              </>
            )}
            <td className="px-3 py-2">
              <span className="text-xs text-muted-foreground">total dep weight: {totalWeight}</span>
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}
