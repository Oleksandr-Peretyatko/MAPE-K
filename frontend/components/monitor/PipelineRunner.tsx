"use client"

import { useState, useEffect, useRef, useCallback } from "react"
import { Play, Square, ChevronDown, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { PipelineStageBar, type PipelineStage, type StageStatus } from "@/components/monitor/PipelineStageBar"
import {
  JaegerMetricsTable,
  SagaMetricsTable,
  LocustResultsTable,
  StaticAnalysisTable,
} from "@/components/monitor/MetricTables"
import { api, type MonitorSnapshot } from "@/lib/api"

const POLL_INTERVAL_MS = 2000

const TERMINAL_STATUSES = new Set(["DONE", "STOPPED", "FAILED"])

function deriveStages(snapshot: MonitorSnapshot | null): PipelineStage[] {
  const status = snapshot?.pipelineStatus

  const staticStatus: StageStatus = (() => {
    if (!status) return "PENDING"
    if (status === "RUNNING_STATIC") return "RUNNING"
    if (status === "FAILED" && !snapshot?.staticVersions?.length) return "FAILED"
    return "DONE"
  })()

  const locustStatus: StageStatus = (() => {
    if (!status || status === "RUNNING_STATIC") return "PENDING"
    if (status === "RUNNING_LOCUST") return "RUNNING"
    if (status === "RUNNING_JAEGER" || status === "DONE" || status === "STOPPED") return "DONE"
    if (status === "FAILED") return "FAILED"
    return "PENDING"
  })()

  const jaegerStatus: StageStatus = (() => {
    if (!status || status === "RUNNING_STATIC" || status === "RUNNING_LOCUST") return "PENDING"
    if (status === "RUNNING_JAEGER") return "RUNNING"
    if (status === "DONE" || status === "STOPPED") return "DONE"
    if (status === "FAILED") return "FAILED"
    return "PENDING"
  })()

  return [
    { id: "static", label: "Static Analysis", status: staticStatus },
    { id: "locust", label: "Load Tests", status: locustStatus },
    { id: "jaeger", label: "Jaeger Collection", status: jaegerStatus },
  ]
}

interface PipelineRunnerProps {
  iterationId?: string | null
  /**
   * Snapshot to re-open on mount. This component is unmounted whenever the loop switches phase
   * tabs, so without it a completed run is replaced by the Start card — as if nothing had ever
   * been collected, one click away from re-running the whole load test.
   */
  restoreSnapshotId?: string | null
  onSnapshotSaved?: (snapshotId: string) => void
}

export function PipelineRunner({ iterationId, restoreSnapshotId, onSnapshotSaved }: PipelineRunnerProps) {
  const [host, setHost] = useState("")
  const hostTouched = useRef(false)
  const [windowMinutes, setWindowMinutes] = useState(15)
  const [configOpen, setConfigOpen] = useState(false)

  const [snapshotId, setSnapshotId] = useState<string | null>(null)
  const [snapshot, setSnapshot] = useState<MonitorSnapshot | null>(null)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState("")
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const savedRef = useRef(false)
  const restoredRef = useRef<string | null>(null)

  const isRunning = snapshot?.pipelineStatus != null && !TERMINAL_STATUSES.has(snapshot.pipelineStatus)
  const isDone = snapshot?.pipelineStatus === "DONE" || snapshot?.pipelineStatus === "STOPPED"
  const isFailed = snapshot?.pipelineStatus === "FAILED"
  const canStop = snapshot?.pipelineStatus === "RUNNING_STATIC" || snapshot?.pipelineStatus === "RUNNING_LOCUST"

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }, [])

  const startPolling = useCallback((id: string) => {
    stopPolling()
    pollRef.current = setInterval(async () => {
      try {
        const s = await api.monitor.pipeline.status(id)
        setSnapshot(s)
        if (TERMINAL_STATUSES.has(s.pipelineStatus ?? "")) {
          stopPolling()
        }
      } catch {
        // silent — keep polling
      }
    }, POLL_INTERVAL_MS)
  }, [stopPolling])

  // Re-open the run this phase was last showing. `/monitor/pipeline/{id}/status` is a plain DB
  // read, so it resolves for any persisted snapshot, not just one that is still in flight.
  useEffect(() => {
    if (!restoreSnapshotId) return
    if (restoreSnapshotId === snapshotId) return           // already on screen
    if (starting || (snapshotId && isRunning)) return      // never interrupt a live run
    if (restoredRef.current === restoreSnapshotId) return  // tried once; "New collection" stays cleared
    restoredRef.current = restoreSnapshotId

    let cancelled = false
    api.monitor.pipeline.status(restoreSnapshotId)
      .then((s) => {
        // Snapshots assembled through the older /monitor/snapshot/assemble route carry no pipeline
        // status; rendering them here would draw an all-PENDING stage bar for a run that finished.
        if (cancelled || !s?.pipelineStatus) return
        // Suppress the DONE effect below for a run that has already finished. A fresh mount starts
        // with savedRef false, so without this, re-opening a finished run would launch a second
        // analysis and — since the caller navigates to Analyze on success — bounce the user straight
        // back out of Monitor. A run still in flight keeps the flag clear so it advances normally.
        savedRef.current = TERMINAL_STATUSES.has(s.pipelineStatus)
        if (!savedRef.current) startPolling(restoreSnapshotId)
        setSnapshot(s)
        setSnapshotId(restoreSnapshotId)
      })
      .catch(() => { /* 404 or unreachable — fall back to the Start card */ })
    return () => { cancelled = true }
  }, [restoreSnapshotId, snapshotId, isRunning, starting, startPolling])

  // Trigger onSnapshotSaved once when pipeline reaches DONE or STOPPED
  useEffect(() => {
    if ((isDone) && snapshotId && !savedRef.current) {
      savedRef.current = true
      onSnapshotSaved?.(snapshotId)
    }
  }, [isDone, snapshotId, onSnapshotSaved])

  useEffect(() => () => stopPolling(), [stopPolling])

  // Seed the target host from the backend (rt.monitor.locust.default-host, i.e. the
  // LOCUST_TARGET_HOST env var) so the deployment under test is configured in one place.
  useEffect(() => {
    api.config.settings()
      .then((s) => {
        if (!hostTouched.current && s.monitor.locustTargetHost) {
          setHost(s.monitor.locustTargetHost)
        }
      })
      .catch(() => { /* leave the field empty; the user can type a host */ })
  }, [])

  const handleStart = async () => {
    setStarting(true)
    setError("")
    savedRef.current = false
    try {
      const s = await api.monitor.pipeline.start({
        host,
        windowMinutes,
        iterationId: iterationId ?? undefined,
      })
      setSnapshot(s)
      setSnapshotId(s.snapshotId)
      startPolling(s.snapshotId)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to start pipeline")
    } finally {
      setStarting(false)
    }
  }

  const handleStop = async () => {
    if (!snapshotId) return
    try {
      await api.monitor.pipeline.stop(snapshotId)
    } catch { /* silent */ }
  }

  const stages = deriveStages(snapshot)

  const hasStatic = (snapshot?.staticVersions?.length ?? 0) > 0
  const hasLocust = (snapshot?.locustScenarios?.length ?? 0) > 0
  const hasJaeger = Object.keys(snapshot?.aggregateMetrics ?? {}).length > 0

  const latestStaticVersion = snapshot?.staticVersions?.at(-1)

  return (
    <div className="space-y-4">
      {/* Start / config row */}
      {!snapshot && (
        <Card>
          <CardContent className="pt-4 space-y-3">
            <Collapsible open={configOpen} onOpenChange={setConfigOpen}>
              <div className="flex items-center gap-3">
                <CollapsibleTrigger asChild>
                  <button className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors">
                    {configOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                    Configure
                  </button>
                </CollapsibleTrigger>
                <div className="ml-auto">
                  <Button onClick={handleStart} disabled={starting} className="gap-1.5">
                    <Play className="h-3.5 w-3.5" />
                    {starting ? "Starting…" : "Start Collection Pipeline"}
                  </Button>
                </div>
              </div>
              <CollapsibleContent className="mt-3 grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">App host</label>
                  <input
                    type="text"
                    value={host}
                    onChange={(e) => { hostTouched.current = true; setHost(e.target.value) }}
                    className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">Jaeger window (min)</label>
                  <input
                    type="number"
                    value={windowMinutes}
                    min={1}
                    onChange={(e) => setWindowMinutes(Number(e.target.value))}
                    className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
              </CollapsibleContent>
            </Collapsible>
          </CardContent>
        </Card>
      )}

      {error && (
        <p className="rounded-md border border-destructive/30 bg-destructive/5 px-4 py-2 text-xs text-destructive">
          {error}
        </p>
      )}

      {/* Pipeline progress */}
      {snapshot && (
        <Card>
          <CardContent className="pt-4 space-y-3">
            <div className="flex items-center gap-4 flex-wrap">
              <PipelineStageBar stages={stages} />
              <div className="ml-auto flex items-center gap-2">
                {canStop && (
                  <Button variant="outline" size="sm" onClick={handleStop} className="gap-1.5 text-xs">
                    <Square className="h-3 w-3" />
                    Stop
                  </Button>
                )}
                {/* Also offered once the run is DONE: a restored run would otherwise be a dead end,
                    with no way to collect again. `restoredRef` keeps the restore effect from
                    immediately re-opening the snapshot this clears. */}
                {!isRunning && (
                  <Button variant="outline" size="sm" onClick={() => {
                    setSnapshot(null)
                    setSnapshotId(null)
                    savedRef.current = false
                  }} className="text-xs">
                    {isDone ? "New collection" : "Run again"}
                  </Button>
                )}
              </div>
            </div>

            {isFailed && snapshot.pipelineError && (
              <p className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
                Pipeline failed: {snapshot.pipelineError}
              </p>
            )}

            {/* Incremental results */}
            {hasStatic && (
              <div>
                <p className="text-xs font-semibold text-foreground mb-2">Static Analysis (M1 / M2)</p>
                <StaticAnalysisTable
                  services={latestStaticVersion?.metrics?.services ?? null}
                  m1={latestStaticVersion?.metrics?.m1}
                  m2={latestStaticVersion?.metrics?.m2}
                  m1Functional={latestStaticVersion?.metrics?.m1Functional}
                  m2Functional={latestStaticVersion?.metrics?.m2Functional}
                />
              </div>
            )}

            {hasLocust && (
              <div>
                <p className="text-xs font-semibold text-foreground mb-2">Load Tests</p>
                <LocustResultsTable data={snapshot.locustScenarios ?? null} />
              </div>
            )}

            {hasJaeger && (
              <div className="space-y-3">
                <div>
                  <p className="text-xs font-semibold text-foreground mb-2">Aggregate Metrics</p>
                  <JaegerMetricsTable data={snapshot.aggregateMetrics ?? null} />
                </div>
                <div>
                  <p className="text-xs font-semibold text-foreground mb-2">SAGA Workflows</p>
                  <SagaMetricsTable data={snapshot.sagaMetrics ?? null} />
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
