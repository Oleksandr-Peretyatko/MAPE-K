"use client"

import { Badge } from "@/components/ui/badge"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
import { SortableTable, type SortableColumn } from "@/components/ui/sortable-table"
import { fmtMs, fmtPct, fmtMetric, fmtNum } from "@/lib/utils"
import { SELF_P95_MS } from "@/lib/api"
import type { AnalysisResult, ServiceStaticMetrics, AggregateMetrics, SagaMetrics, LocustScenario, FunctionalityInfo } from "@/lib/api"

// ─── Coupling tab ─────────────────────────────────────────────────────────────

type CouplingRow = ServiceStaticMetrics & { totalExternalRefs: number }

function buildCouplingRows(perService: Record<string, ServiceStaticMetrics>): CouplingRow[] {
  return Object.values(perService).map((s) => ({
    ...s,
    totalExternalRefs: Object.values(s.externalRelations ?? {}).reduce((a, b) => a + b, 0),
  }))
}

const COUPLING_COLS: SortableColumn<CouplingRow>[] = [
  {
    key: "serviceName",
    label: "Service",
    render: (r) => <span className="font-mono text-foreground">{r.serviceName}</span>,
  },
  { key: "entityCount", label: "Entities (N)", sortValue: (r) => r.entityCount },
  { key: "internalRelations", label: "Internal Rel (μ)", sortValue: (r) => r.internalRelations },
  {
    key: "totalExternalRefs",
    label: "Ext Refs (σ)",
    sortValue: (r) => r.totalExternalRefs,
    render: (r) => (
      <span className={r.totalExternalRefs > 2 ? "font-semibold text-destructive" : ""}>
        {r.totalExternalRefs}
      </span>
    ),
  },
  {
    key: "m1Contribution",
    label: "M1 Contrib",
    sortValue: (r) => r.m1Contribution,
    render: (r) => (
      <span className={r.m1Contribution < 0.05 && r.entityCount >= 2 ? "text-destructive font-medium" : ""}>
        {fmtMetric(r.m1Contribution)}
      </span>
    ),
  },
  {
    key: "m2Contribution",
    label: "M2 Contrib",
    sortValue: (r) => r.m2Contribution ?? 0,
    render: (r) => <span>{fmtMetric(r.m2Contribution ?? 0)}</span>,
  },
  {
    key: "couplingPairs",
    label: "Coupled With",
    sortable: false,
    render: (r) =>
      Object.entries(r.externalRelations ?? {}).filter(([, n]) => n > 0).length > 0 ? (
        <div className="flex flex-wrap gap-1">
          {Object.entries(r.externalRelations ?? {})
            .filter(([, n]) => n > 0)
            .sort(([, a], [, b]) => b - a)
            .map(([svc, n]) => (
              <Badge
                key={svc}
                variant={n > 2 ? "destructive" : "outline"}
                className="text-[10px] py-0 font-mono"
              >
                {svc}: {n}
              </Badge>
            ))}
        </div>
      ) : (
        <span className="text-muted-foreground text-[10px]">—</span>
      ),
  },
]

// ─── Performance tab ──────────────────────────────────────────────────────────

type PerformanceRow = AggregateMetrics

function fmtBw(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)} MB/s`
  if (v >= 1_000) return `${(v / 1_000).toFixed(1)} KB/s`
  return `${Math.round(v)} B/s`
}

/**
 * A service worth flagging: measured latency past the "bad" bound, or a high error rate.
 * `measuredSpanCount === 0` means latency was never measured, so it must not count as healthy
 * *or* as slow — the score omits the signal entirely in that case.
 */
function perfNeedsAttention(r: PerformanceRow): boolean {
  return (r.measuredSpanCount > 0 && r.p95LatencyMs >= SELF_P95_MS.bad) || r.errorRate > 0.05
}

const PERFORMANCE_COLS: SortableColumn<PerformanceRow>[] = [
  {
    key: "serviceName",
    label: "Service",
    render: (r) => <span className="font-mono text-foreground">{r.serviceName}</span>,
  },
  {
    key: "p95LatencyMs",
    label: "p95 self (ms)",
    sortValue: (r) => r.p95LatencyMs,
    render: (r) =>
      r.measuredSpanCount > 0 ? (
        <span
          className={
            r.p95LatencyMs >= SELF_P95_MS.bad
              ? "text-destructive font-semibold"
              : r.p95LatencyMs >= SELF_P95_MS.good
                ? "text-amber-600 dark:text-amber-500"
                : ""
          }
        >
          {fmtMs(r.p95LatencyMs)}
        </span>
      ) : (
        <span className="text-muted-foreground" title="No work spans — latency not measured">—</span>
      ),
  },
  { key: "avgReadLatencyMs", label: "Avg Read", sortValue: (r) => r.avgReadLatencyMs, render: (r) => <span>{fmtMs(r.avgReadLatencyMs)}</span> },
  { key: "avgWriteLatencyMs", label: "Avg Write", sortValue: (r) => r.avgWriteLatencyMs, render: (r) => <span>{fmtMs(r.avgWriteLatencyMs)}</span> },
  {
    key: "errorRate",
    label: "Error Rate",
    sortValue: (r) => r.errorRate,
    render: (r) => (
      <span className={r.errorRate > 0.05 ? "text-destructive font-semibold" : ""}>
        {fmtPct(r.errorRate)}
      </span>
    ),
  },
  { key: "operationFrequency", label: "Ops/min", sortValue: (r) => r.operationFrequency, render: (r) => <span>{fmtNum(r.operationFrequency)}</span> },
  { key: "bandwidthBytesPerSec", label: "Bandwidth", sortValue: (r) => r.bandwidthBytesPerSec, render: (r) => <span>{fmtBw(r.bandwidthBytesPerSec)}</span> },
  { key: "readWriteRatio", label: "R/W", sortValue: (r) => r.readWriteRatio, render: (r) => <span>{r.readWriteRatio?.toFixed(2) ?? "—"}</span> },
  {
    key: "latencySource",
    label: "Source",
    sortable: false,
    render: (r) =>
      r.latencySource && r.latencySource !== "jaeger" ? (
        <Badge variant="outline" className="text-[10px] py-0">{r.latencySource}</Badge>
      ) : (
        <span className="text-[10px] text-muted-foreground">jaeger</span>
      ),
  },
]

// ─── Consistency tab ──────────────────────────────────────────────────────────

type ConsistencyRow = SagaMetrics

const CONSISTENCY_COLS: SortableColumn<ConsistencyRow>[] = [
  {
    key: "workflowName",
    label: "Workflow",
    render: (r) => <span className="font-mono text-foreground text-[11px]">{r.workflowName}</span>,
  },
  { key: "executionCount", label: "Executions", sortValue: (r) => r.executionCount, render: (r) => <span>{fmtNum(r.executionCount)}</span> },
  { key: "avgTotalLatencyMs", label: "Avg (ms)", sortValue: (r) => r.avgTotalLatencyMs, render: (r) => <span>{fmtMs(r.avgTotalLatencyMs)}</span> },
  { key: "p95TotalLatencyMs", label: "p95 (ms)", sortValue: (r) => r.p95TotalLatencyMs, render: (r) => <span className={r.p95TotalLatencyMs > 2000 ? "text-destructive font-semibold" : ""}>{fmtMs(r.p95TotalLatencyMs)}</span> },
  { key: "avgCrossServiceCalls", label: "XS Calls", sortValue: (r) => r.avgCrossServiceCalls, render: (r) => <span>{r.avgCrossServiceCalls?.toFixed(1) ?? "—"}</span> },
  {
    key: "compensationRate",
    label: "Comp. Rate",
    sortValue: (r) => r.compensationRate,
    render: (r) => (
      <span className={r.compensationRate > 0.1 ? "text-warning font-semibold" : ""}>
        {fmtPct(r.compensationRate)}
      </span>
    ),
  },
  {
    key: "involvedServices",
    label: "Services",
    sortable: false,
    render: (r) => (
      <div className="flex flex-wrap gap-1">
        {(r.involvedServices ?? []).map((s) => (
          <Badge key={s} variant="outline" className="text-[10px] py-0">{s}</Badge>
        ))}
      </div>
    ),
  },
]

// ─── Locust tab ───────────────────────────────────────────────────────────────

type LocustRow = LocustScenario

const LOCUST_COLS: SortableColumn<LocustRow>[] = [
  {
    key: "scenarioName",
    label: "Scenario / Endpoint",
    render: (r) => <span className="font-mono text-foreground text-[11px]">{r.scenarioName}</span>,
  },
  { key: "requestCount", label: "Requests", sortValue: (r) => r.requestCount, render: (r) => <span>{fmtNum(r.requestCount)}</span> },
  {
    key: "errorRate",
    label: "Error Rate",
    sortValue: (r) => r.errorRate,
    render: (r) => (
      <span className={r.errorRate > 0.05 ? "text-destructive font-semibold" : ""}>
        {fmtPct(r.errorRate)}
      </span>
    ),
  },
  { key: "avgResponseMs", label: "Avg (ms)", sortValue: (r) => r.avgResponseMs, render: (r) => <span>{fmtMs(r.avgResponseMs)}</span> },
  {
    key: "p95ResponseMs",
    label: "p95 (ms)",
    sortValue: (r) => r.p95ResponseMs,
    render: (r) => (
      <span className={r.p95ResponseMs > 1000 ? "text-destructive font-semibold" : ""}>
        {fmtMs(r.p95ResponseMs)}
      </span>
    ),
  },
  { key: "p99ResponseMs", label: "p99 (ms)", sortValue: (r) => r.p99ResponseMs, render: (r) => <span>{fmtMs(r.p99ResponseMs)}</span> },
  { key: "requestsPerSec", label: "RPS", sortValue: (r) => r.requestsPerSec, render: (r) => <span>{r.requestsPerSec?.toFixed(1) ?? "—"}</span> },
]

// ─── Functionalities tab ──────────────────────────────────────────────────────

type FuncRow = FunctionalityInfo & {
  locustP95?: number
  locustErrorRate?: number
}

const FUNC_COLS: SortableColumn<FuncRow>[] = [
  {
    key: "name",
    label: "Functionality",
    render: (r) => <span className="font-mono text-[11px] text-foreground">{r.name}</span>,
  },
  {
    key: "ownerService",
    label: "Owner",
    render: (r) => <Badge variant="outline" className="text-[10px] py-0">{r.ownerService}</Badge>,
  },
  {
    key: "protocol",
    label: "Protocol",
    render: (r) => (
      <Badge variant={r.protocol === "sagas" ? "secondary" : "outline"} className="text-[10px] py-0 uppercase">
        {r.protocol}
      </Badge>
    ),
  },
  { key: "stepCount", label: "Steps", sortValue: (r) => r.stepCount, render: (r) => <span>{r.stepCount}</span> },
  {
    key: "httpEndpoint",
    label: "Endpoint",
    sortable: false,
    render: (r) =>
      r.httpEndpoint ? (
        <span className="font-mono text-[10px]">
          <span className="text-muted-foreground">{r.httpMethod} </span>{r.httpEndpoint}
        </span>
      ) : (
        <span className="text-muted-foreground text-[10px]">event-driven</span>
      ),
  },
  {
    key: "locustTaskName",
    label: "Locust Task",
    sortable: false,
    render: (r) =>
      r.locustTaskName ? (
        <span className="text-[10px]">{r.locustTaskName}</span>
      ) : (
        <span className="text-muted-foreground text-[10px]">—</span>
      ),
  },
  {
    key: "locustP95",
    label: "p95 (ms)",
    sortValue: (r) => r.locustP95 ?? -1,
    render: (r) =>
      r.locustP95 != null ? (
        <span className={r.locustP95 > 1000 ? "text-destructive font-semibold" : ""}>
          {fmtMs(r.locustP95)}
        </span>
      ) : (
        <span className="text-muted-foreground text-[10px]">—</span>
      ),
  },
  {
    key: "locustErrorRate",
    label: "Err Rate",
    sortValue: (r) => r.locustErrorRate ?? -1,
    render: (r) =>
      r.locustErrorRate != null ? (
        <span className={r.locustErrorRate > 0.05 ? "text-destructive font-semibold" : ""}>
          {fmtPct(r.locustErrorRate)}
        </span>
      ) : (
        <span className="text-muted-foreground text-[10px]">—</span>
      ),
  },
  {
    key: "touchedServices",
    label: "Touches",
    sortable: false,
    render: (r) => (
      <div className="flex flex-wrap gap-1">
        {(r.touchedServices ?? []).map((s) => (
          <Badge key={s} variant="outline" className="text-[10px] py-0">{s}</Badge>
        ))}
      </div>
    ),
  },
]

// ─── Summary badge helper ─────────────────────────────────────────────────────

function ThresholdBadge({ count, label, variant }: { count: number; label: string; variant: "destructive" | "warning" | "secondary" }) {
  if (count === 0) return null
  return (
    <Badge variant={variant} className="text-[10px] gap-1">
      {count} {label}
    </Badge>
  )
}

// ─── Main component ───────────────────────────────────────────────────────────

export function QAMetricsPanel({ result }: { result: AnalysisResult }) {
  const { structuralEvidence: se, runtimeEvidence: re } = result

  const couplingRows = buildCouplingRows(se?.perService ?? {})
  const perfRows = Object.values(re?.perServiceMetrics ?? {})
  const consistencyRows = Object.values(re?.sagaMetrics ?? {})
  const locustRows = re?.locustScenarios ?? []

  // functionalityLocustMetrics: base name → LocustScenario; show only Locust-linked entries
  const funcLocust = re?.functionalityLocustMetrics ?? {}
  const funcRows: FuncRow[] = Object.entries(funcLocust).map(([base, lm]) => ({
    name: base,
    ownerService: "",
    protocol: "sagas" as const,
    touchedServices: [],
    stepCount: 0,
    locustTaskName: lm.scenarioName,
    locustP95: lm.p95ResponseMs,
    locustErrorRate: lm.errorRate,
  }))

  const aboveThresholdCoupling = couplingRows.filter((r) => r.totalExternalRefs > 2).length
  const aboveThresholdPerf = perfRows.filter(perfNeedsAttention).length
  const aboveThresholdComp = consistencyRows.filter((r) => r.compensationRate > 0.1).length
  const aboveThresholdLocust = locustRows.filter((r) => r.p95ResponseMs > 1000 || r.errorRate > 0.05).length
  const aboveThresholdFunc = funcRows.filter((r) => (r.locustP95 ?? 0) > 1000 || (r.locustErrorRate ?? 0) > 0.05).length

  return (
    <Tabs defaultValue="coupling">
      <TabsList className="flex-wrap h-auto gap-1">
        <TabsTrigger value="coupling" className="text-xs gap-1.5">
          Structural (Coupling)
          {aboveThresholdCoupling > 0 && (
            <span className="rounded-full bg-destructive/15 text-destructive px-1.5 py-0 text-[10px] font-semibold">
              {aboveThresholdCoupling}
            </span>
          )}
        </TabsTrigger>
        <TabsTrigger value="performance" className="text-xs gap-1.5">
          Performance
          {aboveThresholdPerf > 0 && (
            <span className="rounded-full bg-destructive/15 text-destructive px-1.5 py-0 text-[10px] font-semibold">
              {aboveThresholdPerf}
            </span>
          )}
        </TabsTrigger>
        <TabsTrigger value="consistency" className="text-xs gap-1.5">
          Consistency (SAGAs)
          {aboveThresholdComp > 0 && (
            <span className="rounded-full bg-warning/20 text-warning px-1.5 py-0 text-[10px] font-semibold">
              {aboveThresholdComp}
            </span>
          )}
        </TabsTrigger>
        <TabsTrigger value="locust" className="text-xs gap-1.5">
          Load Tests
          {aboveThresholdLocust > 0 && (
            <span className="rounded-full bg-destructive/15 text-destructive px-1.5 py-0 text-[10px] font-semibold">
              {aboveThresholdLocust}
            </span>
          )}
        </TabsTrigger>
        {funcRows.length > 0 && (
          <TabsTrigger value="functionalities" className="text-xs gap-1.5">
            Functionalities
            {aboveThresholdFunc > 0 && (
              <span className="rounded-full bg-destructive/15 text-destructive px-1.5 py-0 text-[10px] font-semibold">
                {aboveThresholdFunc}
              </span>
            )}
          </TabsTrigger>
        )}
      </TabsList>

      {/* Structural / Coupling */}
      <TabsContent value="coupling">
        <SortableTable<CouplingRow>
          columns={COUPLING_COLS}
          data={couplingRows}
          defaultSortKey="totalExternalRefs"
          defaultSortDir="desc"
          searchPlaceholder="Search service…"
          searchKeys={["serviceName"]}
          rowClassName={(r) => r.totalExternalRefs > 2 ? "bg-destructive/5" : ""}
          emptyMessage="No structural metrics — run a snapshot first."
          summary={
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>M1 = <strong className="text-foreground">{fmtMetric(se?.m1)}</strong></span>
              <span>M2 = <strong className="text-foreground">{fmtMetric(se?.m2)}</strong></span>
              <span>{couplingRows.length} services</span>
              <ThresholdBadge count={aboveThresholdCoupling} label="high coupling" variant="destructive" />
            </div>
          }
        />
      </TabsContent>

      {/* Performance */}
      <TabsContent value="performance">
        <SortableTable<PerformanceRow>
          columns={PERFORMANCE_COLS}
          data={perfRows}
          defaultSortKey="p95LatencyMs"
          defaultSortDir="desc"
          searchPlaceholder="Search service…"
          searchKeys={["serviceName"]}
          rowClassName={(r) => (perfNeedsAttention(r) ? "bg-destructive/5" : "")}
          emptyMessage="No runtime metrics — collect Jaeger data in the Monitor phase."
          summary={
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>{perfRows.length} services</span>
              <ThresholdBadge count={aboveThresholdPerf} label="above threshold" variant="destructive" />
            </div>
          }
        />
      </TabsContent>

      {/* Consistency — SAGA workflows */}
      <TabsContent value="consistency">
        <SortableTable<ConsistencyRow>
          columns={CONSISTENCY_COLS}
          data={consistencyRows}
          defaultSortKey="compensationRate"
          defaultSortDir="desc"
          searchPlaceholder="Search workflow…"
          searchKeys={["workflowName"]}
          rowClassName={(r) => r.compensationRate > 0.1 ? "bg-orange-500/5" : ""}
          emptyMessage="No SAGA metrics — collect Jaeger data with saga-suffix operations."
          summary={
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>{consistencyRows.length} workflows</span>
              <span>
                Avg comp. rate:{" "}
                <strong className="text-foreground">
                  {fmtPct(result.consistencyEvidence?.avgCompensationRate)}
                </strong>
              </span>
              <ThresholdBadge count={aboveThresholdComp} label="high compensation" variant="warning" />
            </div>
          }
        />
      </TabsContent>

      {/* Locust load tests */}
      <TabsContent value="locust">
        <SortableTable<LocustRow>
          columns={LOCUST_COLS}
          data={locustRows}
          defaultSortKey="p95ResponseMs"
          defaultSortDir="desc"
          searchPlaceholder="Search scenario…"
          searchKeys={["scenarioName", "sourceFile"]}
          rowClassName={(r) =>
            r.scenarioName === "Aggregated"
              ? "bg-muted/40 font-semibold"
              : r.p95ResponseMs > 1000 || r.errorRate > 0.05
              ? "bg-destructive/5"
              : ""
          }
          emptyMessage="No Locust data in this snapshot — run a load test in the Monitor phase."
          summary={
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>{locustRows.length} scenarios</span>
              <ThresholdBadge count={aboveThresholdLocust} label="slow / errors" variant="destructive" />
            </div>
          }
        />
      </TabsContent>

      {/* Functionalities — per-Functionality Locust metrics */}
      {funcRows.length > 0 && (
        <TabsContent value="functionalities">
          <SortableTable<FuncRow>
            columns={FUNC_COLS}
            data={funcRows}
            defaultSortKey="locustP95"
            defaultSortDir="desc"
            searchPlaceholder="Search functionality…"
            searchKeys={["name", "ownerService", "locustTaskName"]}
            rowClassName={(r) =>
              (r.locustP95 ?? 0) > 1000 || (r.locustErrorRate ?? 0) > 0.05 ? "bg-destructive/5" : ""
            }
            emptyMessage="No Functionality → Locust links yet."
            summary={
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span>{funcRows.length} linked functionalities</span>
                <ThresholdBadge count={aboveThresholdFunc} label="slow / errors" variant="destructive" />
              </div>
            }
          />
        </TabsContent>
      )}
    </Tabs>
  )
}
