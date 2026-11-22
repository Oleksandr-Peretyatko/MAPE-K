"use client"

import { useCallback, useEffect, useState, Suspense } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { Activity, ClipboardList, Play, Search } from "lucide-react"
import { MonitorPhase }  from "@/components/phases/MonitorPhase"
import { AnalyzePhase }  from "@/components/phases/AnalyzePhase"
import { PlanPhase }     from "@/components/phases/PlanPhase"
import { ExecutePhase }  from "@/components/phases/ExecutePhase"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { api, type CandidateOperation, type LoopIterationStatus } from "@/lib/api"

// ─── Types ────────────────────────────────────────────────────────────────────

type PhaseTab = "monitor" | "analyze" | "plan" | "execute"

const PHASES: { key: PhaseTab; label: string; Icon: React.ElementType }[] = [
  { key: "monitor",  label: "Monitor",  Icon: Activity },
  { key: "analyze",  label: "Analyze",  Icon: Search },
  { key: "plan",     label: "Plan",     Icon: ClipboardList },
  { key: "execute",  label: "Execute",  Icon: Play },
]

// Which phases are "done" given the iteration status
function doneCount(status?: string): number {
  switch (status) {
    case "MONITORING": return 0
    case "ANALYZED":   return 1
    case "PLANNED":    return 2
    case "EXECUTING":  return 3
    case "DEPLOYED":   return 4
    default:           return 0
  }
}

// Which tab to auto-select based on the iteration's current status
function statusToTab(status?: string): PhaseTab {
  switch (status) {
    case "ANALYZED":  return "analyze"
    case "PLANNED":   return "plan"
    case "EXECUTING":
    case "DEPLOYED":  return "execute"
    case "FAILED":    return "execute"
    default:          return "monitor"
  }
}

// Which phase is currently running (for pulse animation) — index 0-3
function runningPhaseIdx(status?: string): number {
  if (status === "MONITORING") return 0
  if (status === "EXECUTING")  return 3
  return -1
}

// ─── Stepper ─────────────────────────────────────────────────────────────────

function LoopStepper({
  activeTab,
  iterStatus,
  onSelect,
}: {
  activeTab: PhaseTab
  iterStatus: string
  onSelect: (tab: PhaseTab) => void
}) {
  const done    = doneCount(iterStatus)
  const running = runningPhaseIdx(iterStatus)

  return (
    <div className="flex items-center gap-0 w-full">
      {PHASES.map((phase, i) => {
        const isDone    = i < done
        const isRunning = i === running
        const isActive  = phase.key === activeTab

        const dotCls = isDone
          ? "bg-green-500 border-green-500"
          : isRunning
          ? "bg-blue-500 border-blue-500 animate-pulse"
          : isActive
          ? "bg-primary border-primary"
          : "bg-muted border-border"

        const labelCls = isActive
          ? "text-foreground font-semibold"
          : isDone
          ? "text-green-600 dark:text-green-400"
          : "text-muted-foreground"

        return (
          <div key={phase.key} className="flex items-center flex-1 last:flex-none">
            {/* Dot + label */}
            <button
              onClick={() => onSelect(phase.key)}
              className="flex flex-col items-center gap-1.5 group px-2 py-1"
            >
              <div className={`h-4 w-4 rounded-full border-2 transition-all duration-150 group-hover:scale-110 ${dotCls}`} />
              <span className={`text-[10px] uppercase tracking-wide transition-colors ${labelCls}`}>
                {phase.label}
              </span>
            </button>
            {/* Connector line */}
            {i < PHASES.length - 1 && (
              <div className={`flex-1 h-0.5 mt-[-12px] transition-colors ${i < done ? "bg-green-500" : "bg-border"}`} />
            )}
          </div>
        )
      })}
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

function LoopPageInner() {
  const router       = useRouter()
  const searchParams = useSearchParams()
  const { iterationId, navRefreshSeq } = useSnapshot()

  const [activeTab,        setActiveTab]        = useState<PhaseTab>("monitor")
  const [iterStatus,       setIterStatus]       = useState<string>("")
  const [pendingCandidate, setPendingCandidate] = useState<CandidateOperation | null>(null)

  // Sync tab with URL ?tab= param — re-runs whenever searchParams changes (e.g. sidebar link)
  useEffect(() => {
    const tab = searchParams.get("tab") as PhaseTab | null
    if (tab && PHASES.some(p => p.key === tab)) {
      setActiveTab(tab)
    }
  }, [searchParams])

  // When iteration changes: fetch its status, auto-advance to the right tab
  useEffect(() => {
    if (!iterationId) { setIterStatus(""); return }
    let cancelled = false
    api.knowledge.iterations.get(iterationId)
      .then(detail => {
        if (cancelled) return
        const s = detail.status as LoopIterationStatus
        setIterStatus(s)
        // Only auto-advance if no URL tab was specified (first navigation)
        if (!searchParams.get("tab")) {
          setActiveTab(statusToTab(s))
        }
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [iterationId]) // eslint-disable-line react-hooks/exhaustive-deps

  // A phase that advances the iteration (Monitor running its analysis, Execute finishing) signals
  // through navRefreshSeq. Re-read the status so the dots keep up — without this the stepper still
  // says MONITORING while the backend has moved on to ANALYZED. Deliberately no statusToTab here:
  // the user is already looking at a tab, and re-selecting one would yank them out of it.
  useEffect(() => {
    if (navRefreshSeq === 0 || !iterationId) return
    let cancelled = false
    api.knowledge.iterations.get(iterationId)
      .then(detail => { if (!cancelled) setIterStatus(detail.status as LoopIterationStatus) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [navRefreshSeq]) // eslint-disable-line react-hooks/exhaustive-deps

  const switchTab = useCallback((tab: PhaseTab) => {
    setActiveTab(tab)
    router.replace(`/loop?tab=${tab}`)
  }, [router])

  const handleSelectForPlan = useCallback((candidate: CandidateOperation) => {
    setPendingCandidate(candidate)
    switchTab("plan")
  }, [switchTab])

  return (
    <div className="space-y-6">
      {/* Stepper */}
      <div className="rounded-xl border border-border bg-card px-6 py-4">
        <LoopStepper
          activeTab={activeTab}
          iterStatus={iterStatus}
          onSelect={switchTab}
        />
      </div>

      {/* Phase content */}
      {activeTab === "monitor" && (
        <MonitorPhase onSwitchTab={switchTab} />
      )}
      {activeTab === "analyze" && (
        <AnalyzePhase onSelectForPlan={handleSelectForPlan} />
      )}
      {activeTab === "plan" && (
        <PlanPhase
          pendingCandidate={pendingCandidate}
          onCandidateConsumed={() => setPendingCandidate(null)}
          onGoToExecute={() => switchTab("execute")}
        />
      )}
      {activeTab === "execute" && (
        // A successful run adopts its follow-up iteration; move to Monitor to measure it.
        // The page's own auto-advance effect cannot do this: it is gated on `?tab=` being
        // absent, and by now the URL already carries ?tab=execute.
        <ExecutePhase onGoToMonitor={() => switchTab("monitor")} />
      )}
    </div>
  )
}

export default function LoopPage() {
  return (
    <Suspense>
      <LoopPageInner />
    </Suspense>
  )
}
