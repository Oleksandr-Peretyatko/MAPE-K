"use client"

import { useState, useEffect, useCallback } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { PipelineRunner } from "@/components/monitor/PipelineRunner"
import {
  JaegerMetricsTable,
  SagaMetricsTable,
  LocustResultsTable,
  StaticAnalysisTable,
} from "@/components/monitor/MetricTables"
import { SkeletonTable } from "@/components/shared/SkeletonTable"
import { ErrorState } from "@/components/shared/ErrorState"
import { PhaseExportButton } from "@/components/shared/PhaseExportButton"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { api, type MonitorSnapshot } from "@/lib/api"
import { Loader2, CheckCircle2, ChevronRight } from "lucide-react"

type AnalyzePassState = "idle" | "analyzing" | "done" | "error"

interface MonitorPhaseProps {
  onSwitchTab?: (tab: "monitor" | "analyze" | "plan" | "execute") => void
}

export function MonitorPhase({ onSwitchTab }: MonitorPhaseProps) {
  const { snapshotId, setSnapshotId, iterationId, triggerNavRefresh } = useSnapshot()
  const [snapshot, setSnapshot] = useState<MonitorSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState("")

  const [analyzePass, setAnalyzePass] = useState<AnalyzePassState>("idle")
  const [analyzeError, setAnalyzeError] = useState("")

  const loadSnapshot = useCallback(async () => {
    if (!snapshotId) return
    setLoading(true)
    setError("")
    try {
      const data = await api.monitor.snapshot(snapshotId)
      setSnapshot(data)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load snapshot")
    } finally {
      setLoading(false)
    }
  }, [snapshotId])

  useEffect(() => { loadSnapshot() }, [loadSnapshot])

  // The snapshot's static analysis is measured on the version the iteration runs on; the
  // collector lists it last.
  const currentVersionData = snapshot?.staticVersions?.at(-1)
  const services = currentVersionData?.metrics?.services ?? null
  const m1 = currentVersionData?.metrics?.m1
  const m2 = currentVersionData?.metrics?.m2
  const m1Functional = currentVersionData?.metrics?.m1Functional
  const m2Functional = currentVersionData?.metrics?.m2Functional

  const handleSnapshotSaved = useCallback(async (id: string) => {
    setSnapshotId(id)
    setAnalyzePass("analyzing")
    setAnalyzeError("")
    try {
      // No qualityPriorities: the backend applies the project default from /config/analysis-config.
      await api.analyze.run({
        snapshotId: id,
        ...(iterationId ? { iterationId } : {}),
      })
      setAnalyzePass("done")
      // The analysis promoted the iteration to ANALYZED — let the stepper and the top bar catch up.
      triggerNavRefresh()
      // Monitor is finished, so move on without waiting for a click. The Monitor dot leads back
      // here, and PipelineRunner re-opens this run when it does.
      onSwitchTab?.("analyze")
    } catch (e: unknown) {
      setAnalyzeError(e instanceof Error ? e.message : "Analysis failed")
      setAnalyzePass("error")
    }
  }, [setSnapshotId, iterationId, triggerNavRefresh, onSwitchTab])

  return (
    <div className="space-y-5">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-semibold text-foreground">Monitor</h1>
          <p className="text-xs text-muted-foreground">MAPE-K Phase 1 — Collect runtime and static metrics</p>
        </div>
        <PhaseExportButton
          url={snapshotId ? api.monitor.exportUrl(snapshotId) : null}
          filename={snapshotId ? `snapshot-${snapshotId}.json` : "snapshot.json"}
        />
      </div>

      <PipelineRunner
        onSnapshotSaved={handleSnapshotSaved}
        iterationId={iterationId}
        restoreSnapshotId={snapshotId}
      />

      {analyzePass === "analyzing" && (
        <div className="flex items-center gap-3 rounded-md border border-blue-500/30 bg-blue-500/5 px-4 py-2.5 text-sm text-blue-600 dark:text-blue-400">
          <Loader2 className="h-4 w-4 animate-spin flex-shrink-0" />
          Running analysis on new snapshot…
        </div>
      )}
      {analyzePass === "done" && (
        <button
          onClick={() => onSwitchTab?.("analyze")}
          className="flex w-full items-center gap-3 rounded-md border border-green-500/30 bg-green-500/5 px-4 py-2.5 text-sm text-green-600 dark:text-green-400 hover:bg-green-500/10 transition-colors text-left"
        >
          <CheckCircle2 className="h-4 w-4 flex-shrink-0" />
          Monitor complete — Go to Analyze step
          <ChevronRight className="h-4 w-4 ml-auto" />
        </button>
      )}
      {analyzePass === "error" && (
        <div className="flex items-center gap-3 rounded-md border border-destructive/30 bg-destructive/5 px-4 py-2.5 text-sm text-destructive">
          Analysis failed: {analyzeError}
        </div>
      )}

      <Tabs defaultValue="runtime">
        <TabsList className="w-full justify-start">
          <TabsTrigger value="runtime">Aggregates</TabsTrigger>
          <TabsTrigger value="saga">SAGA</TabsTrigger>
          <TabsTrigger value="locust">Locust</TabsTrigger>
          <TabsTrigger value="static">Static Analysis</TabsTrigger>
        </TabsList>

        <TabsContent value="runtime">
          <Card>
            <CardHeader><CardTitle>Aggregate Metrics</CardTitle></CardHeader>
            <CardContent>
              {!snapshotId ? (
                <p className="py-6 text-center text-xs text-muted-foreground">Select an iteration in the top bar.</p>
              ) : loading ? (
                <SkeletonTable rows={6} cols={9} />
              ) : error ? (
                <ErrorState message={error} onRetry={loadSnapshot} />
              ) : (
                <JaegerMetricsTable data={snapshot?.aggregateMetrics ?? null} />
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="saga">
          <Card>
            <CardHeader><CardTitle>SAGA Workflow Metrics</CardTitle></CardHeader>
            <CardContent>
              {!snapshotId ? (
                <p className="py-6 text-center text-xs text-muted-foreground">Select an iteration in the top bar.</p>
              ) : loading ? (
                <SkeletonTable rows={4} cols={7} />
              ) : error ? (
                <ErrorState message={error} onRetry={loadSnapshot} />
              ) : (
                <SagaMetricsTable data={snapshot?.sagaMetrics ?? null} />
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="locust">
          <Card>
            <CardHeader><CardTitle>Locust Load Test Results</CardTitle></CardHeader>
            <CardContent>
              {!snapshotId ? (
                <p className="py-6 text-center text-xs text-muted-foreground">Select an iteration in the top bar.</p>
              ) : loading ? (
                <SkeletonTable rows={6} cols={8} />
              ) : error ? (
                <ErrorState message={error} onRetry={loadSnapshot} />
              ) : (
                <LocustResultsTable data={snapshot?.locustScenarios ?? null} />
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="static">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Static Analysis — M1/M2</CardTitle>
              </div>
            </CardHeader>
            <CardContent>
              {!snapshotId ? (
                <p className="py-6 text-center text-xs text-muted-foreground">Select an iteration in the top bar.</p>
              ) : loading ? (
                <SkeletonTable rows={8} cols={5} />
              ) : error ? (
                <ErrorState message={error} onRetry={loadSnapshot} />
              ) : (
                <StaticAnalysisTable
                  services={services}
                  m1={m1}
                  m2={m2}
                  m1Functional={m1Functional}
                  m2Functional={m2Functional}
                />
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
