"use client"

import { useCallback, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import {
  Plus, RefreshCw, CheckCircle2, Loader2,
  Activity, Search, ClipboardList, Play, ChevronRight, Database
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { ExportIterationButton } from "@/components/shared/ExportIterationButton"
import { useSnapshot } from "@/contexts/SnapshotContext"
import {
  api,
  type LoopIterationSummary,
  type LoopIterationDetail,
  type LoopIterationStatus,
  type BranchStatus,
} from "@/lib/api"

// ─── Status helpers ────────────────────────────────────────────────────────────

const STATUS_ORDER: LoopIterationStatus[] = [
  "CREATED", "MONITORING", "ANALYZED", "PLANNED", "EXECUTING", "DEPLOYED", "FAILED",
]

function statusColor(status: LoopIterationStatus | string): string {
  const s = status as LoopIterationStatus
  if (s === "DEPLOYED")  return "bg-green-500/15 text-green-600 border-green-500/30"
  if (s === "FAILED")    return "bg-red-500/15 text-red-600 border-red-500/30"
  if (s === "EXECUTING" || s === "MONITORING") return "bg-blue-500/15 text-blue-600 border-blue-500/30"
  if (s === "ANALYZED")  return "bg-purple-500/15 text-purple-600 border-purple-500/30"
  if (s === "PLANNED")   return "bg-yellow-500/15 text-yellow-700 border-yellow-500/30"
  return "bg-muted text-muted-foreground border-border"
}

function fmtDate(iso?: string) {
  if (!iso) return "—"
  return new Date(iso).toLocaleString(undefined, { dateStyle: "short", timeStyle: "short" })
}

/** Branch outcome, tracked separately from the phase status. */
function branchStatusColor(s: BranchStatus): string {
  if (s === "BUILD_SUCCESS")      return "bg-green-500/15 text-green-600 border-green-500/30"
  if (s === "BUILD_FAILED")       return "bg-red-500/15 text-red-600 border-red-500/30"
  if (s === "EXECUTING_REFACTOR") return "bg-blue-500/15 text-blue-600 border-blue-500/30"
  return "bg-muted text-muted-foreground border-border"
}

// ─── Phase step indicator ─────────────────────────────────────────────────────

interface Phase {
  key: "monitor" | "analyze" | "plan" | "execute"
  label: string
  Icon: React.ElementType
  linkedId: string | undefined
  doneStatuses: LoopIterationStatus[]
  activeStatuses: LoopIterationStatus[]
}

function PhaseChip({ phase, status }: { phase: Phase; status: LoopIterationStatus }) {
  const isDone   = phase.doneStatuses.includes(status)
  const isActive = phase.activeStatuses.includes(status)
  const Icon = phase.Icon

  return (
    <div className={`flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium ${
      isDone   ? "border-green-500/40 bg-green-500/10 text-green-600 dark:text-green-400"
      : isActive ? "border-blue-500/40 bg-blue-500/10 text-blue-600 dark:text-blue-400"
      : "border-border bg-muted/40 text-muted-foreground"
    }`}>
      {isDone   ? <CheckCircle2 className="h-3 w-3" />
       : isActive ? <Loader2 className="h-3 w-3 animate-spin" />
       : <div className="h-3 w-3 rounded-full border border-current opacity-40" />}
      <Icon className="h-3 w-3" />
      <span>{phase.label}</span>
    </div>
  )
}

function buildPhases(row: LoopIterationSummary): Phase[] {
  return [
    {
      key: "monitor",
      label: "Monitor",
      Icon: Activity,
      linkedId: row.snapshotId,
      doneStatuses:   ["MONITORING", "ANALYZED", "PLANNED", "EXECUTING", "DEPLOYED"],
      activeStatuses: [],
    },
    {
      key: "analyze",
      label: "Analyze",
      Icon: Search,
      linkedId: row.analysisId,
      doneStatuses:   ["ANALYZED", "PLANNED", "EXECUTING", "DEPLOYED"],
      activeStatuses: [],
    },
    {
      key: "plan",
      label: "Plan",
      Icon: ClipboardList,
      linkedId: row.planId,
      doneStatuses:   ["PLANNED", "EXECUTING", "DEPLOYED"],
      activeStatuses: [],
    },
    {
      key: "execute",
      label: "Execute",
      Icon: Play,
      linkedId: row.executeRefactorId,
      doneStatuses:   ["DEPLOYED"],
      activeStatuses: ["EXECUTING"],
    },
  ]
}

// ─── Iteration card ───────────────────────────────────────────────────────────

function IterationCard({
  summary,
  onNavigate,
  onRefresh,
}: {
  summary: LoopIterationSummary
  onNavigate: (phase: "monitor" | "analyze" | "plan" | "execute", iterationId: string, snapshotId?: string) => void
  onRefresh: () => void
}) {
  const [expanded, setExpanded]     = useState(false)
  const [detail, setDetail]         = useState<LoopIterationDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  async function loadDetail() {
    if (detail) { setExpanded(e => !e); return }
    setExpanded(true)
    setDetailLoading(true)
    try {
      const d = await api.knowledge.iterations.get(summary.iterationId)
      setDetail(d)
    } catch {
      // ignore
    } finally {
      setDetailLoading(false)
    }
  }

  const phases = buildPhases(summary)
  const status = summary.status as LoopIterationStatus

  return (
    <Card className="overflow-hidden">
      <CardHeader className="py-3 px-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3 min-w-0">
            {/* Version badge */}
            <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary text-xs font-bold">
              v{summary.iterationNumber}
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-sm font-semibold text-foreground">{summary.name}</span>
                <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase ${statusColor(status)}`}>
                  {status}
                </span>
                {summary.branchStatus && (
                  <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase ${branchStatusColor(summary.branchStatus)}`}>
                    {summary.branchStatus.replace(/_/g, " ")}
                  </span>
                )}
              </div>
              {summary.branchName && (
                <p className="mt-0.5 truncate font-mono text-[11px] text-muted-foreground">
                  {summary.branchName}
                  {summary.parentBranchName && <span className="opacity-60"> ← {summary.parentBranchName}</span>}
                </p>
              )}
              <p className="mt-0.5 text-xs text-muted-foreground">Started {fmtDate(summary.startedAt)}</p>
            </div>
          </div>

          <div className="flex items-center gap-1.5 flex-shrink-0">
            <Button
              size="sm"
              variant="ghost"
              className="h-7 px-2 text-xs gap-1"
              onClick={() => onNavigate("monitor", summary.iterationId, summary.snapshotId)}
            >
              <Activity className="h-3 w-3" />
              Monitor
            </Button>
            <Button
              size="sm"
              variant="ghost"
              className="h-7 px-2 text-xs gap-1"
              onClick={() => onNavigate("analyze", summary.iterationId, summary.snapshotId)}
            >
              <Search className="h-3 w-3" />
              Analyze
            </Button>
            <ExportIterationButton
              iterationId={summary.iterationId}
              iterationNumber={summary.iterationNumber}
              iterationName={summary.name}
              codeVersionId={summary.codeVersionId}
            />
            <button
              className="ml-1 text-muted-foreground hover:text-foreground transition-transform duration-150"
              style={{ transform: expanded ? "rotate(90deg)" : "rotate(0deg)" }}
              onClick={loadDetail}
              aria-label="Toggle details"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* Phase chips */}
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          {phases.map((phase, i) => (
            <div key={phase.key} className="flex items-center gap-1.5">
              <PhaseChip phase={phase} status={status} />
              {i < phases.length - 1 && (
                <ChevronRight className="h-3 w-3 text-muted-foreground/40" />
              )}
            </div>
          ))}
        </div>
      </CardHeader>

      {expanded && (
        <CardContent className="border-t border-border px-4 pb-4 pt-3">
          {detailLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> Loading detail…
            </div>
          ) : detail ? (
            <div className="space-y-4">
              {/* Phase detail grid */}
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <PhaseDetailCard
                  label="Monitor"
                  icon={<Activity className="h-3.5 w-3.5" />}
                  linked={!!detail.snapshotId}
                  linkedId={detail.snapshotId}
                  meta={detail.snapshot ? `${fmtDate(detail.snapshot.collectedAt)}` : undefined}
                  action={() => onNavigate("monitor", summary.iterationId, summary.snapshotId)}
                  actionLabel="View metrics"
                />
                <PhaseDetailCard
                  label="Analyze"
                  icon={<Search className="h-3.5 w-3.5" />}
                  linked={!!detail.analysisId}
                  linkedId={detail.analysisId}
                  meta={detail.analysis ? `${detail.analysis.candidateOperations?.length ?? 0} candidates` : undefined}
                  action={() => onNavigate("analyze", summary.iterationId, summary.snapshotId)}
                  actionLabel="View analysis"
                />
                <PhaseDetailCard
                  label="Plan"
                  icon={<ClipboardList className="h-3.5 w-3.5" />}
                  linked={!!detail.planId}
                  linkedId={detail.planId}
                  meta={detail.plan ? detail.plan.operation : undefined}
                  action={() => onNavigate("plan", summary.iterationId, summary.snapshotId)}
                  actionLabel="View plan"
                />
                <PhaseDetailCard
                  label="Execute"
                  icon={<Play className="h-3.5 w-3.5" />}
                  linked={!!detail.executeRefactorId}
                  linkedId={detail.executeRefactorId}
                  meta={detail.execution ? detail.execution.status : undefined}
                  action={() => onNavigate("execute", summary.iterationId, summary.snapshotId)}
                  actionLabel="View execution"
                />
              </div>

              {/* Execute log preview */}
              {detail.execution?.executeLog && (
                <div>
                  <p className="mb-1 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
                    Execution log (last 1000 chars)
                  </p>
                  <pre className="max-h-36 overflow-y-auto rounded bg-black/80 p-2 text-[10px] leading-relaxed text-green-400 font-mono whitespace-pre-wrap">
                    {detail.execution.executeLog.slice(-1000)}
                  </pre>
                </div>
              )}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">No detail available.</p>
          )}
        </CardContent>
      )}
    </Card>
  )
}

function PhaseDetailCard({
  label, icon, linked, linkedId, meta, action, actionLabel,
}: {
  label: string
  icon: React.ReactNode
  linked: boolean
  linkedId?: string
  meta?: string
  action?: () => void
  actionLabel?: string
}) {
  return (
    <div className={`rounded-lg border p-2.5 text-xs ${linked ? "border-green-500/30 bg-green-500/5" : "border-border bg-muted/20"}`}>
      <div className="flex items-center gap-1.5 font-semibold mb-1">
        {linked ? <CheckCircle2 className="h-3.5 w-3.5 text-green-500" /> : <div className="h-3.5 w-3.5 rounded-full border border-muted-foreground/40" />}
        {icon}
        <span>{label}</span>
      </div>
      {linked ? (
        <>
          {linkedId && <p className="font-mono text-[10px] text-muted-foreground truncate">{linkedId.slice(0, 8)}…</p>}
          {meta && <p className="text-muted-foreground mt-0.5">{meta}</p>}
          {action && (
            <button className="mt-1.5 text-primary hover:underline text-[10px]" onClick={action}>
              {actionLabel} →
            </button>
          )}
        </>
      ) : (
        <p className="text-muted-foreground italic">Not yet linked</p>
      )}
    </div>
  )
}

// ─── Page ──────────────────────────────────────────────────────────────────────

export default function IterationsPage() {
  const { setSnapshotId, setIterationId } = useSnapshot()
  const router = useRouter()

  const [iterations, setIterations] = useState<LoopIterationSummary[]>([])
  const [loading,    setLoading]    = useState(false)
  const [creating,   setCreating]   = useState(false)
  const [seeding,    setSeeding]    = useState(false)
  const [error,      setError]      = useState<string | null>(null)
  const [seedResult, setSeedResult] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await api.knowledge.iterations.list()
      setIterations(list)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load iterations")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function handleCreate() {
    setCreating(true)
    try {
      const iter = await api.knowledge.iterations.create()
      setIterationId(iter.iterationId)
      setIterations(prev => [...prev, iter])
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to create iteration")
    } finally {
      setCreating(false)
    }
  }

  async function handleSeed() {
    setSeeding(true)
    setSeedResult(null)
    try {
      const result = await api.knowledge.iterations.seed()
      setSeedResult(result.message)
      load()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Seed failed")
    } finally {
      setSeeding(false)
    }
  }

  function handleNavigate(
    phase: "monitor" | "analyze" | "plan" | "execute",
    iterationId: string,
    snapshotId?: string,
  ) {
    setIterationId(iterationId)
    if (snapshotId) setSnapshotId(snapshotId)
    const routes = { monitor: "/monitor", analyze: "/analyze", plan: "/plan", execute: "/execute" }
    router.push(routes[phase])
  }

  const sorted = [...iterations].sort((a, b) => b.iterationNumber - a.iterationNumber)

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-foreground">Iterations</h1>
          <p className="text-xs text-muted-foreground">
            Each iteration is one full MAPE-K cycle — Monitor → Analyze → Plan → Execute
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={handleSeed}
            disabled={seeding}
            className="gap-1.5"
          >
            {seeding ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Database className="h-3.5 w-3.5" />}
            Seed
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={load}
            disabled={loading}
            className="gap-1.5"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
            Refresh
          </Button>
          <Button
            size="sm"
            onClick={handleCreate}
            disabled={creating}
            className="gap-1.5"
          >
            {creating ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Plus className="h-3.5 w-3.5" />}
            New Iteration
          </Button>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* Seed result */}
      {seedResult && (
        <div className="rounded-lg border border-green-500/30 bg-green-500/5 px-4 py-2.5 text-sm text-green-600 dark:text-green-400">
          {seedResult}
        </div>
      )}

      {/* Iteration list */}
      {loading && iterations.length === 0 ? (
        <div className="flex items-center gap-3 text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          <span className="text-sm">Loading iterations…</span>
        </div>
      ) : sorted.length === 0 ? (
        <div className="py-16 text-center space-y-3">
          <p className="text-sm text-muted-foreground">No iterations yet.</p>
          <p className="text-xs text-muted-foreground">
            Click <strong>New Iteration</strong> to start a MAPE-K cycle, or <strong>Seed</strong> to create
            iterations from existing snapshots and plans.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {sorted.map(iter => (
            <IterationCard
              key={iter.iterationId}
              summary={iter}
              onNavigate={handleNavigate}
              onRefresh={load}
            />
          ))}
        </div>
      )}
    </div>
  )
}
