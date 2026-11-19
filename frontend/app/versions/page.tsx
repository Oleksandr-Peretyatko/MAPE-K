"use client"

import { useCallback, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import {
  RefreshCw, Loader2, Plus, ChevronRight,
  Activity, Search, ClipboardList, Play,
  GitBranch, GitCommit, Package, AlertCircle, ListOrdered
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { ExportIterationButton } from "@/components/shared/ExportIterationButton"
import { VersionEvolutionPanel } from "@/components/versions/VersionEvolutionPanel"
import { StructureButton } from "@/components/structure/StructureButton"
import { DeployCommandButton } from "@/components/versions/DeployCommandButton"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { cn } from "@/lib/utils"
import {
  api,
  type CodeVersionSummary,
  type CodeVersionDetail,
  type CodeVersionStatus,
  type LoopIterationSummary,
  type LoopIterationStatus,
} from "@/lib/api"

// ─── Status helpers ────────────────────────────────────────────────────────────

function versionStatusColor(status: CodeVersionStatus): string {
  if (status === "BASELINE")   return "bg-blue-500/15 text-blue-600 border-blue-500/30"
  if (status === "ACTIVE")     return "bg-green-500/15 text-green-600 border-green-500/30"
  if (status === "SUPERSEDED") return "bg-muted text-muted-foreground border-border"
  if (status === "FAILED")     return "bg-red-500/15 text-red-600 border-red-500/30"
  return "bg-muted text-muted-foreground border-border"
}

function iterStatusColor(status: LoopIterationStatus | string): string {
  if (status === "DEPLOYED")  return "bg-green-500/15 text-green-600 border-green-500/30"
  if (status === "FAILED")    return "bg-red-500/15 text-red-600 border-red-500/30"
  if (status === "EXECUTING" || status === "MONITORING") return "bg-blue-500/15 text-blue-600 border-blue-500/30"
  if (status === "ANALYZED")  return "bg-purple-500/15 text-purple-600 border-purple-500/30"
  if (status === "PLANNED")   return "bg-yellow-500/15 text-yellow-700 border-yellow-500/30"
  return "bg-muted text-muted-foreground border-border"
}

function fmtDate(iso?: string) {
  if (!iso) return "—"
  return new Date(iso).toLocaleString(undefined, { dateStyle: "short", timeStyle: "short" })
}

function shortSha(sha?: string) {
  return sha ? sha.slice(0, 7) : null
}

// ─── Iteration mini-row ────────────────────────────────────────────────────────

function IterationRow({
  iter,
  onNavigate,
}: {
  iter: LoopIterationSummary
  onNavigate: (phase: "monitor" | "analyze" | "plan" | "execute", iterationId: string, snapshotId?: string, codeVersionId?: string) => void
}) {
  const phases: { key: "monitor" | "analyze" | "plan" | "execute"; Icon: React.ElementType; done: boolean }[] = [
    { key: "monitor",  Icon: Activity,      done: ["MONITORING","ANALYZED","PLANNED","EXECUTING","DEPLOYED"].includes(iter.status) },
    { key: "analyze",  Icon: Search,        done: ["ANALYZED","PLANNED","EXECUTING","DEPLOYED"].includes(iter.status) },
    { key: "plan",     Icon: ClipboardList, done: ["PLANNED","EXECUTING","DEPLOYED"].includes(iter.status) },
    { key: "execute",  Icon: Play,          done: ["DEPLOYED"].includes(iter.status) },
  ]

  return (
    <div className="flex items-center gap-3 rounded-md border border-border bg-muted/20 px-3 py-2 text-xs hover:bg-muted/40 transition-colors">
      <div className="flex h-6 w-6 items-center justify-center rounded bg-primary/10 text-primary text-[10px] font-bold flex-shrink-0">
        #{iter.iterationNumber}
      </div>
      <span className="truncate font-medium text-foreground flex-1">{iter.name}</span>
      <span className={`inline-flex items-center rounded-full border px-1.5 py-0.5 text-[9px] font-bold uppercase ${iterStatusColor(iter.status as LoopIterationStatus)}`}>
        {iter.status}
      </span>
      <div className="flex items-center gap-1">
        {phases.map(({ key, Icon, done }) => (
          <button
            key={key}
            className={`flex items-center justify-center rounded p-0.5 transition-colors ${done ? "text-green-500 hover:bg-green-500/10" : "text-muted-foreground/40 cursor-default"}`}
            onClick={done ? () => onNavigate(key, iter.iterationId, iter.snapshotId, iter.codeVersionId) : undefined}
            title={key}
          >
            <Icon className="h-3 w-3" />
          </button>
        ))}
      </div>
      <ExportIterationButton
        iterationId={iter.iterationId}
        iterationNumber={iter.iterationNumber}
        iterationName={iter.name}
        codeVersionId={iter.codeVersionId}
      />
      <span className="text-muted-foreground flex-shrink-0">{fmtDate(iter.startedAt)}</span>
    </div>
  )
}

// ─── Version card ──────────────────────────────────────────────────────────────

function VersionCard({
  summary,
  onCreateIteration,
  onNavigate,
}: {
  summary: CodeVersionSummary
  onCreateIteration: (versionId: string) => void
  onNavigate: (phase: "monitor" | "analyze" | "plan" | "execute", iterationId: string, snapshotId?: string, codeVersionId?: string) => void
}) {
  const [expanded,       setExpanded]       = useState(false)
  const [detail,         setDetail]         = useState<CodeVersionDetail | null>(null)
  const [detailLoading,  setDetailLoading]  = useState(false)
  const [discovering,    setDiscovering]    = useState(false)
  const [discoverMsg,    setDiscoverMsg]    = useState<string | null>(null)
  const [creatingIter,   setCreatingIter]   = useState(false)

  const isActive   = summary.status === "ACTIVE"
  const isBaseline = summary.status === "BASELINE"

  async function loadDetail() {
    if (detail) { setExpanded(e => !e); return }
    setExpanded(true)
    setDetailLoading(true)
    try {
      const d = await api.knowledge.versions.get(summary.versionId)
      setDetail(d)
    } catch {
      // ignore
    } finally {
      setDetailLoading(false)
    }
  }

  async function handleDiscover() {
    setDiscovering(true)
    setDiscoverMsg(null)
    try {
      await api.knowledge.versions.runDiscovery(summary.versionId)
      setDiscoverMsg("Discovery complete")
      if (detail) {
        const d = await api.knowledge.versions.get(summary.versionId)
        setDetail(d)
      }
    } catch (e: unknown) {
      setDiscoverMsg(e instanceof Error ? e.message : "Discovery failed")
    } finally {
      setDiscovering(false)
    }
  }

  async function handleCreateIteration() {
    setCreatingIter(true)
    try {
      await onCreateIteration(summary.versionId)
    } finally {
      setCreatingIter(false)
    }
  }

  const sha = shortSha(summary.gitCommitSha)

  return (
    <Card className={`overflow-hidden ${isActive ? "border-green-500/40" : isBaseline ? "border-blue-500/30" : ""}`}>
      <CardHeader className="py-3 px-4">
        <div className="flex items-start gap-3">
          {/* Version badge */}
          <div className={`flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg text-xs font-bold ${
            isActive   ? "bg-green-500/20 text-green-600"
            : isBaseline ? "bg-blue-500/20 text-blue-600"
            : "bg-muted/60 text-muted-foreground"
          }`}>
            v{summary.versionNumber}
          </div>

          <div className="flex-1 min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-semibold text-foreground">{summary.versionLabel}</span>
              <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase ${versionStatusColor(summary.status)}`}>
                {summary.status}
              </span>
              {summary.iterationCount > 0 && (
                <span className="text-xs text-muted-foreground">{summary.iterationCount} iteration{summary.iterationCount !== 1 ? "s" : ""}</span>
              )}
            </div>
            <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
              {summary.branchName && (
                <span className="flex items-center gap-1">
                  <GitBranch className="h-3 w-3" />
                  <span className="font-mono">{summary.branchName}</span>
                </span>
              )}
              {sha && (
                <span className="flex items-center gap-1">
                  <GitCommit className="h-3 w-3" />
                  <span className="font-mono">{sha}</span>
                </span>
              )}
              {summary.imageTag && (
                <span className="flex items-center gap-1">
                  <Package className="h-3 w-3" />
                  <span className="font-mono">{summary.imageTag}</span>
                </span>
              )}
              {summary.qualityPriorities && summary.qualityPriorities.length > 0 && (
                <span className="flex items-center gap-1" title="Quality priorities this version was analyzed with">
                  <ListOrdered className="h-3 w-3" />
                  <span>{summary.qualityPriorities.join(" → ")}</span>
                </span>
              )}
              {!summary.branchName && !sha && !summary.imageTag && (
                <span className="italic">Original codebase</span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-1.5 flex-shrink-0">
            <StructureButton
              versionId={summary.versionId}
              versionLabel={summary.versionLabel}
              className="h-7 px-2 text-xs gap-1"
              label="Structure"
            />
            <DeployCommandButton
              versionLabel={summary.versionLabel}
              deployCommand={summary.deployCommand}
            />
            <Button
              size="sm"
              variant="outline"
              className="h-7 px-2 text-xs gap-1"
              onClick={handleDiscover}
              disabled={discovering}
              title={summary.hasDiscoveryGraph ? "Re-run discovery" : "Run discovery"}
            >
              {discovering ? <Loader2 className="h-3 w-3 animate-spin" /> : <Search className="h-3 w-3" />}
              {summary.hasDiscoveryGraph ? "Re-run" : "Discover"}
            </Button>
            <Button
              size="sm"
              className="h-7 px-2 text-xs gap-1"
              onClick={handleCreateIteration}
              disabled={creatingIter}
            >
              {creatingIter ? <Loader2 className="h-3 w-3 animate-spin" /> : <Plus className="h-3 w-3" />}
              New Iteration
            </Button>
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

        {discoverMsg && (
          <p className="mt-1.5 text-xs text-muted-foreground">{discoverMsg}</p>
        )}
      </CardHeader>

      {expanded && (
        <CardContent className="border-t border-border px-4 pb-4 pt-3">
          {detailLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> Loading…
            </div>
          ) : detail ? (
            <div className="space-y-2">
              {detail.iterations && detail.iterations.length > 0 ? (
                detail.iterations.map(iter => (
                  <IterationRow
                    key={iter.iterationId}
                    iter={iter}
                    onNavigate={onNavigate}
                  />
                ))
              ) : (
                <p className="text-xs text-muted-foreground italic py-2">
                  No iterations for this version yet. Click <strong>New Iteration</strong> to start the MAPE-K loop.
                </p>
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

// ─── Page ──────────────────────────────────────────────────────────────────────

export default function VersionsPage() {
  const { setSnapshotId, setIterationId, setCodeVersionId } = useSnapshot()
  const router = useRouter()

  const [versions,  setVersions]  = useState<CodeVersionSummary[]>([])
  const [loading,   setLoading]   = useState(false)
  const [error,     setError]     = useState<string | null>(null)
  const [view,      setView]      = useState<"list" | "evolution">("evolution")
  const [refreshKey, setRefreshKey] = useState(0)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await api.knowledge.versions.list()
      setVersions(list)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load versions")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function handleCreateIteration(codeVersionId: string) {
    try {
      const iter = await api.knowledge.iterations.create(undefined, codeVersionId)
      setIterationId(iter.iterationId)
      setCodeVersionId(codeVersionId)
      setSnapshotId(null)
      router.push("/monitor")
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to create iteration")
    }
  }

  function handleNavigate(
    phase: "monitor" | "analyze" | "plan" | "execute",
    iterationId: string,
    snapshotId?: string,
    codeVersionId?: string,
  ) {
    setIterationId(iterationId)
    if (snapshotId) setSnapshotId(snapshotId)
    if (codeVersionId) setCodeVersionId(codeVersionId)
    const routes = { monitor: "/monitor", analyze: "/analyze", plan: "/plan", execute: "/execute" }
    router.push(routes[phase])
  }

  const sorted = [...versions].sort((a, b) => b.versionNumber - a.versionNumber)

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-foreground">Versions</h1>
          <p className="text-xs text-muted-foreground">
            Each version is a state of the codebase on a git branch — run MAPE-K iterations against it
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center rounded-md border border-border p-0.5">
            {(["evolution", "list"] as const).map((mode) => (
              <button
                key={mode}
                onClick={() => setView(mode)}
                className={cn(
                  "rounded px-2.5 py-1 text-xs capitalize transition-colors",
                  view === mode
                    ? "bg-primary/15 font-semibold text-foreground"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {mode}
              </button>
            ))}
          </div>
          <Button
            size="sm"
            variant="outline"
            onClick={() => { load(); setRefreshKey((k) => k + 1) }}
            disabled={loading}
            className="gap-1.5"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
            Refresh
          </Button>
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
          <AlertCircle className="h-4 w-4 flex-shrink-0" />
          {error}
        </div>
      )}

      {view === "evolution" ? (
        <VersionEvolutionPanel onCreateIteration={handleCreateIteration} refreshKey={refreshKey} />
      ) : loading && versions.length === 0 ? (
        <div className="flex items-center gap-3 text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          <span className="text-sm">Loading versions…</span>
        </div>
      ) : sorted.length === 0 ? (
        <div className="py-16 text-center space-y-3">
          <p className="text-sm text-muted-foreground">No code versions yet.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {sorted.map(v => (
            <VersionCard
              key={v.versionId}
              summary={v}
              onCreateIteration={handleCreateIteration}
              onNavigate={handleNavigate}
            />
          ))}
        </div>
      )}
    </div>
  )
}
