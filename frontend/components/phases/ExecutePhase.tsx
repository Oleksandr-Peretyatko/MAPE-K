"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import {
  AlertCircle, Check, CheckCircle2, Clock, Ghost, Loader2,
  Play, RefreshCw, RotateCcw, Terminal, X,
} from "lucide-react"
import { api, type ClaudeCommandTestResult, type ExecuteResponse, type ExecuteStatus, type PlanResponse } from "@/lib/api"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { PhaseExportButton } from "@/components/shared/PhaseExportButton"

const PIPELINE_STEPS: { key: ExecuteStatus; label: string }[] = [
  { key: "PENDING",   label: "Pending"   },
  { key: "MUTATING",  label: "Mutating"  },
  { key: "BUILDING",  label: "Building"  },
  { key: "DEPLOYING", label: "Deploying" },
  { key: "DEPLOYED",  label: "Deployed"  },
]

const TERMINAL: ExecuteStatus[] = ["DEPLOYED", "FAILED", "ROLLED_BACK", "NOT_APPLICABLE"]
// AWAITING_VERDICT counts as active: the branch is checked out and the iteration is open, so
// polling must continue and Start must stay disabled until the verdict settles the run.
const ACTIVE:   ExecuteStatus[] = ["PENDING", "MUTATING", "BUILDING", "DEPLOYING", "AWAITING_VERDICT"]

function StatusBadge({ status }: { status: ExecuteStatus }) {
  const color: Record<string, string> = {
    PENDING:     "bg-blue-500/10 text-blue-600 dark:text-blue-400",
    MUTATING:    "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400",
    BUILDING:    "bg-orange-500/10 text-orange-600 dark:text-orange-400",
    DEPLOYING:   "bg-purple-500/10 text-purple-600 dark:text-purple-400",
    DEPLOYED:    "bg-green-500/10 text-green-700 dark:text-green-400",
    FAILED:      "bg-red-500/10 text-red-700 dark:text-red-400",
    ROLLED_BACK: "bg-gray-500/10 text-gray-700 dark:text-gray-400",
    // Neutral, not red: nothing went wrong, the operation just did not fit this codebase.
    NOT_APPLICABLE: "bg-slate-500/10 text-slate-700 dark:text-slate-300",
    // Ghost mode: waiting on a person, so it reads as a prompt rather than progress.
    AWAITING_VERDICT: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
  }
  return (
    <span className={`rounded px-2 py-1 text-xs font-semibold ${color[status] ?? ""}`}>
      {status}
    </span>
  )
}

function StepIndicator({ current }: { current: ExecuteStatus }) {
  // NOT_APPLICABLE stops the pipeline too, so the step row must not keep showing progress —
  // but it is rendered as a plain badge rather than a failure.
  const isFailed = current === "FAILED" || current === "ROLLED_BACK" || current === "NOT_APPLICABLE"
  // A ghost run parked for a verdict branched and then stopped on purpose. It is not in
  // PIPELINE_STEPS, so show the steps it did complete as done and the rest as never reached —
  // otherwise the whole row would grey out as if nothing had happened.
  const isParked = current === "AWAITING_VERDICT"
  const currentIdx = isParked
    ? PIPELINE_STEPS.findIndex(s => s.key === "BUILDING")
    : PIPELINE_STEPS.findIndex(s => s.key === current)
  return (
    <div className="flex items-center gap-2 flex-wrap">
      {PIPELINE_STEPS.map((step, i) => {
        const isPast    = !isFailed && i < currentIdx
        const isCurrent = !isFailed && !isParked && step.key === current
        const cls = isPast
          ? "bg-green-500 text-white"
          : isCurrent
          ? "bg-blue-500 text-white animate-pulse"
          : "bg-muted text-muted-foreground"
        return <span key={step.key} className={`rounded px-2 py-1 text-xs font-medium ${cls}`}>{step.label}</span>
      })}
      {(isFailed || isParked) && <StatusBadge status={current} />}
    </div>
  )
}

interface ExecutePhaseProps {
  /** Called after a successful run hands over to its follow-up iteration. */
  onGoToMonitor?: () => void
}

export function ExecutePhase({ onGoToMonitor }: ExecutePhaseProps) {
  const {
    iterationId, triggerNavRefresh,
    setIterationId, setCodeVersionId, setSnapshotId,
  } = useSnapshot()
  const [plan,        setPlan]       = useState<PlanResponse | null>(null)
  const [status,      setStatus]     = useState<ExecuteResponse | null>(null)
  const [loading,     setLoading]    = useState(false)
  const [error,       setError]      = useState<string | null>(null)
  const [testResult,  setTestResult] = useState<ClaudeCommandTestResult | null>(null)
  const [testLoading, setTestLoading]= useState(false)
  const [ghostMode,   setGhostMode]  = useState(false)
  const [verdictNote, setVerdictNote]= useState("")
  const [verdicting,  setVerdicting] = useState(false)
  const logRef  = useRef<HTMLPreElement>(null)
  const pollRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // startPolling is memoised with an empty dep array, so anything it closes over must be read
  // through a ref or it captures the first render's value.
  const goToMonitorRef = useRef(onGoToMonitor)
  useEffect(() => { goToMonitorRef.current = onGoToMonitor }, [onGoToMonitor])

  /**
   * The backend creates a follow-up iteration bound to the version just deployed. Adopt it as the
   * active iteration and move on to Monitor, so the loop continues on the new branch.
   */
  const handOverToFollowUp = useCallback(async (nextIterationId: string) => {
    try {
      const next = await api.knowledge.iterations.get(nextIterationId)
      setIterationId(next.iterationId)
      if (next.codeVersionId) setCodeVersionId(next.codeVersionId)
      // Without this the previous iteration's snapshot leaks into the fresh Monitor tab.
      setSnapshotId(null)
      triggerNavRefresh()
      goToMonitorRef.current?.()
    } catch (err) {
      setError(`Deployed, but could not open the follow-up iteration: ${err instanceof Error ? err.message : String(err)}`)
    }
  }, [setIterationId, setCodeVersionId, setSnapshotId, triggerNavRefresh])

  const handOverRef = useRef(handOverToFollowUp)
  useEffect(() => { handOverRef.current = handOverToFollowUp }, [handOverToFollowUp])

  const startPolling = useCallback((currentIterationId?: string) => {
    const poll = async () => {
      try {
        const s = await api.execute.status()
        setStatus(s)
        if (s && !TERMINAL.includes(s.status)) {
          pollRef.current = setTimeout(poll, 3000)
          return
        }
        if (!s || !TERMINAL.includes(s.status)) return

        if (currentIterationId && s.executeRefactorId) {
          // The run belongs to the iteration that started it, whatever its outcome.
          try {
            await api.knowledge.iterations.linkExecute(currentIterationId, s.executeRefactorId)
            triggerNavRefresh()
          } catch (err) {
            setError(`Execution ${s.status} — iteration link failed: ${err instanceof Error ? err.message : String(err)}`)
          }
        }

        // Only a successful run continues the loop. A failed one stays put: the backend has
        // already recovered to the parent branch and there is nothing new to measure.
        if (s.status === "DEPLOYED" && s.nextIterationId) {
          await handOverRef.current(s.nextIterationId)
        }
      } catch { /* stop polling silently */ }
    }
    pollRef.current = setTimeout(poll, 2000)
  }, [triggerNavRefresh])

  const loadInitial = useCallback(async () => {
    setLoading(true)
    try {
      // Boot-time flag, so it cannot change while the page is open — but it decides whether a run
      // will do anything at all, which the operator must know before pressing Start.
      const settings = await api.config.settings().catch(() => null)
      if (settings) setGhostMode(Boolean(settings.execute.ghostMode))

      if (iterationId) {
        const detail = await api.knowledge.iterations.get(iterationId).catch(() => null)
        if (detail?.planId) {
          const p = await api.plan.get(detail.planId).catch(() => null)
          setPlan(p)
        } else {
          setPlan(null)
        }
        if (detail?.execution) {
          setStatus(detail.execution)
          if (!TERMINAL.includes(detail.execution.status)) startPolling(iterationId)
        } else {
          setStatus(null)
        }
      } else {
        const [planResult, statusResult] = await Promise.allSettled([
          api.plan.current(),
          api.execute.status(),
        ])
        if (planResult.status === "fulfilled") setPlan(planResult.value)
        if (statusResult.status === "fulfilled") setStatus(statusResult.value)
      }
    } finally {
      setLoading(false)
    }
  }, [iterationId, startPolling])

  useEffect(() => { loadInitial() }, [loadInitial])

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [status?.executeLog])

  useEffect(() => () => { if (pollRef.current) clearTimeout(pollRef.current) }, [])

  const isRunning = status ? ACTIVE.includes(status.status) : false

  const handleRun = useCallback(async () => {
    setError(null); setLoading(true)
    try {
      const s = await api.execute.run(iterationId ?? undefined)
      setStatus(s)
      startPolling(iterationId ?? undefined)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to start execution")
    } finally { setLoading(false) }
  }, [startPolling, iterationId])

  const handleTestRun = useCallback(async () => {
    setError(null); setLoading(true)
    try {
      const s = await api.execute.testRun()
      setStatus(s)
      startPolling(iterationId ?? undefined)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to start test run")
    } finally { setLoading(false) }
  }, [startPolling, iterationId])

  const handleRollback = useCallback(async () => {
    setError(null); setLoading(true)
    try { setStatus(await api.execute.rollback()) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : "Rollback failed") }
    finally { setLoading(false) }
  }, [])

  /**
   * Settles a ghost run. The verdict is what the backend writes into the CLAUDE.md refactor-log
   * entry (CHANGED / NO_CHANGE) before committing it, so it is taken once and not revisable.
   *
   * <p>Polling is still live, so the handover to the follow-up iteration happens through the same
   * path a real deploy takes — nothing extra to do here on success.
   */
  const handleVerdict = useCallback(async (verdict: "SUCCESS" | "FAILED") => {
    if (!status?.executeRefactorId) return
    setError(null); setVerdicting(true)
    try {
      const updated = await api.execute.verdict(
        status.executeRefactorId, verdict, verdictNote.trim() || undefined)
      setStatus(updated)
      setVerdictNote("")
      triggerNavRefresh()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to record the verdict")
    } finally { setVerdicting(false) }
  }, [status?.executeRefactorId, verdictNote, triggerNavRefresh])

  const handleTestCommand = useCallback(async () => {
    setTestResult(null); setTestLoading(true)
    try { setTestResult(await api.execute.testCommand()) }
    catch (e: unknown) {
      setTestResult({ success: false, exitCode: -1, output: "", error: e instanceof Error ? e.message : "Request failed" })
    } finally { setTestLoading(false) }
  }, [])

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-semibold text-foreground">Execute</h1>
          <p className="text-xs text-muted-foreground">MAPE-K Phase 4 — Apply the current refactoring plan to the running system</p>
        </div>
        <PhaseExportButton
          url={status?.executeRefactorId ? api.execute.exportUrl(status.executeRefactorId) : null}
          filename={status?.executeRefactorId ? `execution-${status.executeRefactorId}.json` : "execution.json"}
        />
      </div>

      {/* Current Plan */}
      <Card>
        <CardHeader className="pb-3"><CardTitle className="text-sm">Current Plan</CardTitle></CardHeader>
        <CardContent>
          {!plan ? (
            <p className="text-sm text-muted-foreground">
              No plan loaded. Generate a plan in the <strong>Plan</strong> tab first.
            </p>
          ) : (
            <div className="space-y-2 text-sm">
              <div className="flex gap-6 flex-wrap">
                <span>
                  <span className="text-muted-foreground">Operation </span>
                  <span className="font-mono font-medium">{plan.operation}</span>
                </span>
                <span>
                  <span className="text-muted-foreground">Target </span>
                  <span className="font-mono font-medium">{plan.targetService}</span>
                </span>
              </div>
              {plan.skillInvocation && (
                <pre className="rounded bg-black/80 p-2 text-xs text-green-400 overflow-x-auto whitespace-pre-wrap">
                  /{plan.skillInvocation}
                </pre>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Claude CLI */}
      <Card>
        <CardHeader className="pb-3"><CardTitle className="text-sm">Claude CLI</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <Button size="sm" variant="outline" onClick={handleTestCommand} disabled={testLoading} title="Verify claude CLI is installed and authenticated">
              {testLoading ? <Loader2 className="w-3 h-3 mr-1.5 animate-spin" /> : <CheckCircle2 className="w-3 h-3 mr-1.5" />}
              Test Claude Command
            </Button>
            <p className="text-xs text-muted-foreground">
              Mutations run through the claude CLI, authenticated with the Claude session mounted into rt-app.
            </p>
          </div>
          {testResult && (
            <div className={`rounded border p-3 text-xs ${
              testResult.success
                ? "border-green-500/30 bg-green-500/5 text-green-700 dark:text-green-400"
                : "border-red-500/30 bg-red-500/5 text-red-700 dark:text-red-400"
            }`}>
              <div className="flex items-center gap-2 mb-1 font-semibold">
                {testResult.success ? <CheckCircle2 className="w-3.5 h-3.5" /> : <AlertCircle className="w-3.5 h-3.5" />}
                {testResult.success ? "Claude CLI OK" : "Claude CLI check failed"}
                {testResult.claudePath && <span className="font-normal text-muted-foreground ml-1">— {testResult.claudePath}</span>}
              </div>
              {testResult.error && <p className="text-red-600 dark:text-red-400">{testResult.error}</p>}
              {testResult.output && (
                <pre className="mt-1 whitespace-pre-wrap font-mono text-xs opacity-80 max-h-32 overflow-auto">{testResult.output}</pre>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Actions */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm flex items-center gap-2">
            Actions
            {ghostMode && (
              <span className="rounded bg-amber-500/10 px-2 py-0.5 text-xs font-semibold text-amber-700 dark:text-amber-400 inline-flex items-center gap-1">
                <Ghost className="w-3 h-3" /> GHOST MODE
              </span>
            )}
          </CardTitle>
        </CardHeader>
        {ghostMode && (
          <CardContent className="pt-0 pb-3">
            <p className="text-xs text-muted-foreground">
              A run will create its branch and then wait for your verdict. No Claude mutation, no
              test gate, no image build, no redeploy — the running stack is untouched, and the
              version it produces carries no code change. Redeploy rt-app without{" "}
              <code className="font-mono">--ghost</code> for a real refactoring.
            </p>
          </CardContent>
        )}
        <CardContent className="flex flex-wrap gap-3">
          <Button onClick={handleRun} disabled={!plan || isRunning || loading}>
            {loading ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Play className="w-4 h-4 mr-2" />}
            Start Execution
          </Button>
          <Button variant="outline" onClick={handleTestRun} disabled={isRunning || loading}>
            {loading ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Terminal className="w-4 h-4 mr-2" />}
            Test Run
          </Button>
          <Button variant="outline" onClick={handleRollback} disabled={status?.status !== "DEPLOYED" || loading}>
            <RotateCcw className="w-4 h-4 mr-2" />
            Rollback
          </Button>
          <Button variant="ghost" onClick={loadInitial} disabled={loading}>
            <RefreshCw className="w-4 h-4 mr-2" />
            Refresh
          </Button>
        </CardContent>
      </Card>

      {/* Ghost verdict — only ever rendered for a run parked by ghost mode. */}
      {status?.status === "AWAITING_VERDICT" && (
        <Card className="border-amber-500/40">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm flex items-center gap-2">
              <Ghost className="w-4 h-4 text-amber-600 dark:text-amber-400" />
              Verdict required
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="text-sm text-muted-foreground">
              Branch <span className="font-mono text-foreground">{status.branchName}</span> is
              ready and nothing has been changed. Your verdict decides the{" "}
              <span className="font-mono">Result</span> of the{" "}
              <span className="font-mono">CLAUDE.md</span> refactor-log entry the tool then writes
              and commits — <span className="font-mono">CHANGED</span> for success,{" "}
              <span className="font-mono">NO_CHANGE</span> for failure — so it is taken once and
              cannot be revised afterwards.
            </p>
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">
                Summary (optional) — goes into the log entry
              </label>
              <input
                type="text"
                value={verdictNote}
                onChange={(e) => setVerdictNote(e.target.value)}
                placeholder={`e.g. ${status.operation} ${status.targetService} applied as planned`}
                disabled={verdicting}
                className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline" size="sm"
                className="gap-1.5 text-green-700 dark:text-green-400"
                onClick={() => handleVerdict("SUCCESS")}
                disabled={verdicting}
              >
                {verdicting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                Mark Success
              </Button>
              <Button
                variant="outline" size="sm"
                className="gap-1.5 text-red-700 dark:text-red-400"
                onClick={() => handleVerdict("FAILED")}
                disabled={verdicting}
              >
                {verdicting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <X className="h-3.5 w-3.5" />}
                Mark Failed
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Success records the version and hands over to Monitor. Failure marks the iteration
              failed and returns to the parent branch. Either way the containers are left alone.
            </p>
          </CardContent>
        </Card>
      )}

      {error && (
        <div className="flex items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4">
          <AlertCircle className="w-5 h-5 text-destructive flex-shrink-0 mt-0.5" />
          <p className="text-sm text-destructive">{error}</p>
        </div>
      )}

      {/* Execution Status */}
      {status ? (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center justify-between gap-3 text-sm">
              <span>Execution Status</span>
              <div className="flex items-center gap-2">
                {isRunning && <Loader2 className="w-4 h-4 animate-spin text-blue-500" />}
                <StatusBadge status={status.status} />
              </div>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <StepIndicator current={status.status} />
            <div className="grid grid-cols-2 gap-x-8 gap-y-1 text-xs">
              <div className="text-muted-foreground">Version <span className="text-foreground font-mono ml-1">v{status.versionNumber}</span></div>
              <div className="text-muted-foreground">Branch <span className="text-foreground font-mono ml-1">{status.branchName}</span></div>
              {status.gitCommitSha && (
                <div className="text-muted-foreground">Commit <span className="text-foreground font-mono ml-1">{status.gitCommitSha.slice(0, 8)}</span></div>
              )}
              {status.imageTag && (
                <div className="text-muted-foreground">Image <span className="text-foreground font-mono ml-1">{status.imageTag}</span></div>
              )}
              <div className="text-muted-foreground">Started <span className="text-foreground ml-1">{new Date(status.startedAt).toLocaleTimeString()}</span></div>
              {status.completedAt && (
                <div className="text-muted-foreground">Completed <span className="text-foreground ml-1">{new Date(status.completedAt).toLocaleTimeString()}</span></div>
              )}
            </div>
            {status.executeLog && (
              <pre ref={logRef} className="rounded bg-black/80 p-3 text-xs text-green-300 overflow-auto max-h-96 whitespace-pre-wrap font-mono">
                {status.executeLog}
              </pre>
            )}
          </CardContent>
        </Card>
      ) : (
        !loading && (
          <Card>
            <CardContent className="py-12 text-center text-muted-foreground">
              <Clock className="mx-auto mb-3 w-8 h-8 opacity-40" />
              <p className="text-sm">
                No execution history yet. Click <strong>Test Run</strong> for a safe dry-run, or{" "}
                <strong>Start Execution</strong> to apply the current plan.
              </p>
            </CardContent>
          </Card>
        )
      )}
    </div>
  )
}
