"use client"

import { useState, useCallback, useEffect, useRef } from "react"
import { Play, Loader2, Info, Settings2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { SimpleRankAnalyzeView } from "@/components/phases/SimpleRankAnalyzeView"
import { SkeletonTable } from "@/components/shared/SkeletonTable"
import { ErrorState } from "@/components/shared/ErrorState"
import { PhaseExportButton } from "@/components/shared/PhaseExportButton"
import { ThresholdsEditor } from "@/components/config/ThresholdsEditor"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { api, type AnalysisResult, type CandidateOperation, type QualityAttributeDef } from "@/lib/api"

interface AnalyzePhaseProps {
  onSelectForPlan?: (candidate: CandidateOperation) => void
}

export function AnalyzePhase({ onSelectForPlan }: AnalyzePhaseProps) {
  const { snapshotId, iterationId } = useSnapshot()
  const [qaOptions, setQaOptions] = useState<QualityAttributeDef[]>([])
  const [p1, setP1] = useState("PERFORMANCE")
  const [p2, setP2] = useState("CONSISTENCY")
  const [p3, setP3] = useState("MAINTAINABILITY")
  const [result, setResult] = useState<AnalysisResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [autoLoading, setAutoLoading] = useState(false)
  const [autoLoaded, setAutoLoaded] = useState(false)
  const [error, setError] = useState("")
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const [thresholdsOpen, setThresholdsOpen] = useState(false)

  // Project default from /config/analysis-config, and the "only this iteration vs project
  // default" scope prompt shown when the selected priorities diverge from it.
  const [configDefault, setConfigDefault] = useState<string[]>(["PERFORMANCE", "CONSISTENCY", "MAINTAINABILITY"])
  const [scopePromptOpen, setScopePromptOpen] = useState(false)
  const [savingDefault, setSavingDefault] = useState(false)
  const [defaultSaved, setDefaultSaved] = useState(false)

  // Load the configured default priorities on mount
  useEffect(() => {
    api.config.analysisConfig.get()
      .then(c => { if (c.defaultQualityPriorities?.length) setConfigDefault(c.defaultQualityPriorities) })
      .catch(() => {})
  }, [])

  // Restore last-used priorities for this iteration (or globally last-used on first load)
  useEffect(() => {
    api.analyze.getPriorities(iterationId ?? undefined)
      .then((saved) => {
        if (saved.length >= 1) setP1(saved[0])
        if (saved.length >= 2) setP2(saved[1])
        if (saved.length >= 3) setP3(saved[2])
      })
      .catch(() => {/* keep defaults */})
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [iterationId])

  // Load dynamic QA options on mount
  useEffect(() => {
    api.config.qualityAttributes.list()
      .then(list => {
        setQaOptions(list)
        // Reset selectors if current values no longer exist in the list
        const names = list.map(q => q.name)
        if (names.length > 0 && !names.includes(p1)) setP1(names[0])
        if (names.length > 1 && !names.includes(p2)) setP2(names[1])
        if (names.length > 2 && !names.includes(p3)) setP3(names[2])
      })
      .catch(() => {})
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Auto-load existing analysis when iteration changes
  useEffect(() => {
    if (!iterationId) { setResult(null); setAutoLoaded(false); return }
    let cancelled = false
    api.knowledge.iterations.get(iterationId)
      .then(detail => {
        if (cancelled) return
        if (detail.analysis) { setResult(detail.analysis); setAutoLoaded(true) }
        else { setResult(null); setAutoLoaded(false) }
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [iterationId])

  const runAnalysis = useCallback(async () => {
    if (!snapshotId) return
    setLoading(true)
    setError("")
    setAutoLoaded(false)
    try {
      const data = await api.analyze.run({
        snapshotId,
        qualityPriorities: [p1, p2, p3],
        ...(iterationId ? { iterationId } : {}),
      })
      setResult(data)
      const diverges = [p1, p2, p3].some((p, i) => p !== configDefault[i])
      setScopePromptOpen(diverges)
      if (diverges) setDefaultSaved(false)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Analysis failed")
    } finally {
      setLoading(false)
    }
  }, [snapshotId, iterationId, p1, p2, p3, configDefault])

  // Auto-recalculate when priorities change — only if a result already exists
  useEffect(() => {
    if (!result || !snapshotId) return
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(async () => {
      setAutoLoading(true)
      try {
        const data = await api.analyze.run({
          snapshotId,
          qualityPriorities: [p1, p2, p3],
          ...(iterationId ? { iterationId } : {}),
        })
        setResult(data)
        // Ask the scope question once the run succeeded, only while diverging from the default.
        const diverges = [p1, p2, p3].some((p, i) => p !== configDefault[i])
        setScopePromptOpen(diverges)
        if (diverges) setDefaultSaved(false)
      } catch { /* silent */ }
      finally { setAutoLoading(false) }
    }, 400)
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current) }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [p1, p2, p3])

  // "Set as project default": re-fetch first so the full-object PUT does not clobber other fields.
  const saveAsDefault = useCallback(async () => {
    setSavingDefault(true)
    setError("")
    try {
      const cfg = await api.config.analysisConfig.get()
      await api.config.analysisConfig.update({ ...cfg, defaultQualityPriorities: [p1, p2, p3] })
      setConfigDefault([p1, p2, p3])
      setScopePromptOpen(false)
      setDefaultSaved(true)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to save project default")
    } finally {
      setSavingDefault(false)
    }
  }, [p1, p2, p3])

  const priorities = [p1, p2, p3]

  return (
    <div className="space-y-5">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-semibold text-foreground">Analyze</h1>
          <p className="text-xs text-muted-foreground">MAPE-K Phase 2 — Identify hotspots and candidate refactoring operations</p>
        </div>
        <PhaseExportButton
          url={result?.analysisId ? api.analyze.exportUrl(result.analysisId) : null}
          filename={result?.analysisId ? `analysis-${result.analysisId}.json` : "analysis.json"}
        />
      </div>

      {/* Controls */}
      <Card>
        <CardContent className="pt-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">P1</span>
              <Select value={p1} onValueChange={setP1}>
                <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
                <SelectContent>{qaOptions.map((o) => <SelectItem key={o.id} value={o.name}>{o.name}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">P2</span>
              <Select value={p2} onValueChange={setP2}>
                <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
                <SelectContent>{qaOptions.map((o) => <SelectItem key={o.id} value={o.name}>{o.name}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">P3</span>
              <Select value={p3} onValueChange={setP3}>
                <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
                <SelectContent>{qaOptions.map((o) => <SelectItem key={o.id} value={o.name}>{o.name}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="ml-auto flex items-center gap-2">
              {autoLoading && <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />}
              <Button
                variant="outline"
                size="icon"
                onClick={() => setThresholdsOpen(true)}
                title="Configure detection thresholds"
              >
                <Settings2 className="h-3.5 w-3.5" />
              </Button>
              <Button onClick={runAnalysis} disabled={!snapshotId || loading} className="gap-1.5">
                <Play className="h-3.5 w-3.5" />
                {loading ? "Analysing…" : "Run Analysis"}
              </Button>
            </div>
          </div>
          {!snapshotId && <p className="mt-2 text-xs text-muted-foreground">Select an iteration in the top bar first.</p>}
        </CardContent>
      </Card>

      {/* Scope prompt — the chosen priorities differ from the project default */}
      {scopePromptOpen && (
        <Card className="border-amber-500/30">
          <CardContent className="pt-4">
            <p className="text-sm text-foreground">Priorities changed</p>
            <p className="mt-1 text-xs text-muted-foreground">
              These priorities differ from the project default ({configDefault.join(" → ")}).
              Apply them only to this iteration, or make them the default for every analysis?
            </p>
            <div className="mt-3 flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setScopePromptOpen(false)}
                disabled={savingDefault}
              >
                Only this iteration
              </Button>
              <Button size="sm" onClick={saveAsDefault} disabled={savingDefault} className="gap-1.5">
                {savingDefault && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                Set as project default
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
      {defaultSaved && (
        <div className="rounded-md border border-green-500/30 bg-green-500/5 px-3 py-2 text-xs text-green-600 dark:text-green-400">
          Saved as project default — new iterations will analyze with {configDefault.join(" → ")}.
        </div>
      )}

      {error && <ErrorState message={error} onRetry={runAnalysis} />}

      {loading && <div className="space-y-4"><SkeletonTable rows={3} cols={5} /></div>}

      {/* Auto-loaded banner */}
      {autoLoaded && !loading && (
        <div className="flex items-center gap-2 rounded-md border border-blue-500/20 bg-blue-500/5 px-4 py-2 text-xs text-blue-600 dark:text-blue-400">
          <Info className="h-3.5 w-3.5 flex-shrink-0" />
          Loaded from saved iteration — click <strong className="mx-1">Run Analysis</strong> to refresh
        </div>
      )}

      {result && !loading && (
        <SimpleRankAnalyzeView result={result} priorities={priorities} onSelectForPlan={onSelectForPlan} />
      )}

      <ThresholdsEditor open={thresholdsOpen} onOpenChange={setThresholdsOpen} />
    </div>
  )
}
