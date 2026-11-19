"use client"

import { useState } from "react"
import { ChevronDown, ChevronRight, Copy, Check, ArrowRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { EmptyState } from "@/components/shared/EmptyState"
import type { CandidateOperation, MetricImpact } from "@/lib/api"
import { cn } from "@/lib/utils"

/** Prefix of the warning SimpleRankingStrategy attaches when nothing improves the primary QA. */
const FALLBACK_WARNING_PREFIX = "No enabled operation improves "

const isFallbackWarning = (w: string) => w.startsWith(FALLBACK_WARNING_PREFIX)

const SCORE_BADGE: Record<string, string> = {
  "2":  "bg-green-500/15 text-green-700 dark:text-green-400 border-green-500/30",
  "1":  "bg-blue-500/15 text-blue-700 dark:text-blue-400 border-blue-500/30",
  "0":  "bg-muted text-muted-foreground border-border",
  "-1": "bg-orange-500/15 text-orange-700 dark:text-orange-400 border-orange-500/30",
  "-2": "bg-red-500/15 text-red-700 dark:text-red-400 border-red-500/30",
}

// ─── Metric helpers ───────────────────────────────────────────────────────────

type MetricRow = {
  label: string
  before: number | string | null | undefined
  after:  number | string | null | undefined
}

function metricRows(m: MetricImpact): MetricRow[] {
  return [
    { label: "p95 latency (ms)", before: m.p95LatencyMsBefore,    after: m.p95LatencyMsAfter },
    { label: "M1 cohesion",      before: m.m1Before,              after: m.m1After },
    { label: "M2 coupling",      before: m.m2Before,              after: m.m2After },
    { label: "Compensation",     before: m.compensationRateBefore, after: m.compensationRateAfter },
    { label: "External rels",    before: m.externalRelsBefore,    after: m.externalRelsAfter },
    { label: "Entity count",     before: m.entityCountBefore,     after: m.entityCountAfter },
  ].filter(r => r.before != null || r.after != null)
}

function fmtVal(v: number | string | null | undefined): string {
  if (v == null) return "—"
  if (typeof v === "string") return v
  return v < 1 ? v.toFixed(3) : v.toFixed(1)
}

// ─── Main export ──────────────────────────────────────────────────────────────

interface Props {
  candidates: CandidateOperation[]
  priorities: string[]
  onSelectForPlan?: (c: CandidateOperation) => void
}

export function SimpleRankCandidateList({ candidates, priorities, onSelectForPlan }: Props) {

  const unblocked = (candidates ?? []).filter(c => !c.blockedBy)
  const blocked   = (candidates ?? []).filter(c =>  c.blockedBy)
  // Ranked as a fallback: every enabled operation scores <= 0 on the primary QA.
  const fallbackWarning = unblocked.flatMap(c => c.warnings ?? []).find(isFallbackWarning)

  if (!unblocked.length && !blocked.length)
    return <EmptyState title="No candidate operations" description="Run an analysis to generate refactoring candidates." />

  function handleSelect(c: CandidateOperation) {
    onSelectForPlan?.(c)
  }

  return (
    <div className="space-y-2">
      {/* Ranking legend */}
      <div className="flex items-center justify-between px-1 mb-3">
        <p className="text-xs text-muted-foreground">
          Ordered by: service severity
          {priorities[0] && <> → <strong className="text-foreground">{priorities[0]}</strong> score</>}
          {priorities[1] && <> → {priorities[1]}</>}
          {priorities[2] && <> → {priorities[2]}</>}
        </p>
        <span className="text-[10px] text-muted-foreground">{unblocked.length} candidate{unblocked.length !== 1 ? "s" : ""}</span>
      </div>

      {fallbackWarning && (
        <div className="rounded-md border border-yellow-500/40 bg-yellow-500/10 px-3 py-2 text-xs text-yellow-700 dark:text-yellow-400">
          ⚠ {fallbackWarning} Consider re-ordering the quality priorities if this trade-off is not acceptable.
        </div>
      )}

      {/* Ranked list */}
      <div className="space-y-2">
        {unblocked.map((c, idx) => (
          <RankRow
            key={`${c.operation}-${c.targetService}-${idx}`}
            candidate={c}
            rank={idx + 1}
            primaryQaLabel={priorities[0] ?? "P1"}
            onSelectForPlan={() => handleSelect(c)}
          />
        ))}
      </div>

      {/* Blocked section */}
      {blocked.length > 0 && (
        <div className="pt-4">
          <p className="mb-2 px-1 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
            Blocked by constraints ({blocked.length})
          </p>
          <div className="space-y-1.5">
            {blocked.map((c, idx) => (
              <div
                key={`${c.operation}-${c.targetService}-${idx}`}
                className="flex items-start gap-3 rounded-lg border border-border bg-muted/20 px-4 py-3 opacity-60"
              >
                <span className="font-mono text-sm text-foreground/60 shrink-0">{c.operation}</span>
                <span className="text-xs text-muted-foreground">
                  → {c.targetService}{c.secondaryTarget ? ` + ${c.secondaryTarget}` : ""}
                </span>
                <span className="ml-auto text-xs text-destructive/80 shrink-0">⛔ {c.blockedBy}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Skill command builder ────────────────────────────────────────────────────

function buildSkillCmd(c: CandidateOperation): string {
  // Slug differs from the enum name for the aggregate operations: MERGE_AGGREGATES → merge-aggregate.
  const slug = c.operation === "MERGE_AGGREGATES" ? "merge-aggregate" : c.operation.toLowerCase().replace(/_/g, "-")
  const dest = c.secondaryTarget ? ` ${c.secondaryTarget}` : ""
  const entity = c.affectedEntities?.[0]

  switch (c.operation) {
    case "MERGE_AGGREGATES":
      return `/${slug} ${c.targetService}${dest}`
    case "SPLIT_AGGREGATE": {
      const coreEntities = c.affectedEntities && c.affectedEntities.length > 0
        ? ` --entities ${c.affectedEntities.slice(0, 3).join(",")}` : ""
      return `/${slug} ${c.targetService}${coreEntities}`
    }
    case "CREATE_REPLICA":
      return `/${slug} ${c.secondaryTarget ?? "?"} ${entity ?? "?"} --to ${c.targetService}`
    case "REMOVE_REPLICA":
      return `/${slug} ${c.targetService} ${entity ?? "?"} --source ${c.secondaryTarget ?? "?"}`
  }
}

// ─── Single row ───────────────────────────────────────────────────────────────

function RankRow({
  candidate: c,
  rank,
  primaryQaLabel,
  onSelectForPlan,
}: {
  candidate: CandidateOperation
  rank: number
  primaryQaLabel: string
  onSelectForPlan: () => void
}) {
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [blueprintOpen, setBlueprintOpen] = useState(false)
  const [copied, setCopied] = useState(false)

  const scoreKey = String(c.primaryQaScore ?? 0)
  const scoreBadge = SCORE_BADGE[scoreKey] ?? SCORE_BADGE["0"]
  const scoreLabel = c.primaryQaScore != null
    ? (c.primaryQaScore > 0 ? `+${c.primaryQaScore}` : String(c.primaryQaScore))
    : "0"

  const skillCmd = c.commandSignature ?? buildSkillCmd(c)

  function copyCmd() {
    navigator.clipboard.writeText(skillCmd)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <div className={cn(
      "rounded-lg border bg-card overflow-hidden",
      rank === 1 ? "border-primary/50" : "border-border",
    )}>
      {/* Main row */}
      <div className="flex items-start gap-3 px-4 py-3">
        {/* Rank bubble */}
        <div className={cn(
          "flex h-8 w-8 shrink-0 items-center justify-center rounded-full font-bold mt-0.5",
          rank === 1 ? "bg-primary text-primary-foreground text-sm" :
          rank === 2 ? "bg-secondary text-secondary-foreground text-sm" :
          rank === 3 ? "bg-muted text-foreground/70 text-sm" :
                       "bg-muted/50 text-muted-foreground/70 text-xs",
        )}>
          {rank}
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0 space-y-1">
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="font-mono text-xs text-muted-foreground">{c.operation}</span>
          </div>

          {/* Summary — primary headline */}
          <p className="text-sm font-medium text-foreground leading-snug">
            {c.summary ?? (c.targetService + (c.secondaryTarget ? ` → ${c.secondaryTarget}` : ""))}
          </p>

          {/* rankReason — always visible, core transparency */}
          {c.rankReason && (
            <p className="text-xs text-foreground/70 leading-snug pt-0.5 font-mono">
              {c.rankReason}
            </p>
          )}

          <p className="text-xs text-muted-foreground leading-snug">{c.whyApplicable}</p>

          {c.affectedEntities && c.affectedEntities.length > 0 && (
            <div className="flex flex-wrap gap-1 pt-0.5">
              {c.affectedEntities.map(e => (
                <span key={e} className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">{e}</span>
              ))}
            </div>
          )}

          {c.warnings && c.warnings.some(w => !isFallbackWarning(w)) && (
            <div className="space-y-0.5 pt-0.5">
              {c.warnings.filter(w => !isFallbackWarning(w)).map((w, i) => (
                <p key={i} className="text-[10px] text-yellow-600 dark:text-yellow-400">⚠ {w}</p>
              ))}
            </div>
          )}
        </div>

        {/* Right column: score + plan */}
        <div className="flex flex-col items-end gap-2 shrink-0">
          <span className={cn("rounded border px-2 py-0.5 text-sm font-bold tabular-nums", scoreBadge)}>
            {scoreLabel} <span className="text-[10px] font-normal opacity-70">{primaryQaLabel}</span>
          </span>
          <Button size="sm" className="h-7 gap-1 text-xs" onClick={onSelectForPlan}>
            Plan <ArrowRight className="h-3 w-3" />
          </Button>
        </div>
      </div>

      {/* Footer: details / blueprint / skill command */}
      <div className="border-t border-border/50 flex text-[11px]">
        <button
          className="flex items-center gap-1.5 px-4 py-2 text-muted-foreground hover:text-foreground hover:bg-muted/20 transition-colors"
          onClick={() => setDetailsOpen(!detailsOpen)}
        >
          {detailsOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          Details
        </button>
        {c.blueprint && (
          <button
            className="flex items-center gap-1.5 px-4 py-2 text-muted-foreground hover:text-foreground hover:bg-muted/20 border-l border-border/50 transition-colors"
            onClick={() => setBlueprintOpen(!blueprintOpen)}
          >
            {blueprintOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            Blueprint
          </button>
        )}
        <button
          className="ml-auto flex items-center gap-1.5 px-4 py-2 text-muted-foreground hover:text-foreground hover:bg-muted/20 border-l border-border/50 transition-colors font-mono"
          onClick={copyCmd}
          title="Copy skill command"
        >
          {copied ? <Check className="h-3 w-3 text-green-500" /> : <Copy className="h-3 w-3" />}
          <span className="truncate max-w-[200px]">{skillCmd}</span>
        </button>
      </div>

      {/* Details panel */}
      {detailsOpen && (
        <div className="px-4 pb-3 pt-2 space-y-3 border-t border-border/50 bg-muted/10">
          {c.preconditions && c.preconditions.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">Preconditions</p>
              <ul className="space-y-0.5">
                {c.preconditions.map((p, i) => <li key={i} className="text-xs text-foreground/80">• {p}</li>)}
              </ul>
            </div>
          )}
          {c.risks && c.risks.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">Risks</p>
              <ul className="space-y-0.5">
                {c.risks.map((r, i) => <li key={i} className="text-xs text-orange-600 dark:text-orange-400">⚠ {r}</li>)}
              </ul>
            </div>
          )}
          {c.dependencies && c.dependencies.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1">Dependencies</p>
              <ul className="space-y-0.5">
                {c.dependencies.map((d, i) => <li key={i} className="text-xs text-foreground/80">• {d}</li>)}
              </ul>
            </div>
          )}
        </div>
      )}

      {/* Blueprint panel */}
      {blueprintOpen && c.blueprint && (
        <div className="px-4 pb-4 pt-2 space-y-4 border-t border-border/50 bg-muted/10">
          {c.blueprint.summary && (
            <p className="text-xs font-semibold text-foreground leading-snug">{c.blueprint.summary}</p>
          )}

          {((c.blueprint.before?.length ?? 0) > 0 || (c.blueprint.after?.length ?? 0) > 0) && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1.5">Structure</p>
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1.5">
                  <p className="text-[10px] text-muted-foreground font-medium">Before</p>
                  {c.blueprint.before?.map((snap, i) => (
                    <div key={i} className="rounded border border-border/50 bg-background p-2">
                      <p className="text-[10px] font-semibold font-mono">{snap.aggregateName}</p>
                      <div className="mt-0.5 flex flex-wrap gap-0.5">
                        {snap.entities?.map(e => (
                          <span key={e} className="rounded bg-muted px-1 font-mono text-[9px] text-muted-foreground">{e}</span>
                        ))}
                      </div>
                      {snap.invariants?.length > 0 && (
                        <p className="mt-0.5 text-[9px] text-muted-foreground italic">{snap.invariants.join(", ")}</p>
                      )}
                    </div>
                  ))}
                </div>
                <div className="space-y-1.5">
                  <p className="text-[10px] text-muted-foreground font-medium">After</p>
                  {c.blueprint.after?.map((snap, i) => (
                    <div key={i} className={cn(
                      "rounded border p-2",
                      snap.isNew ? "border-green-500/30 bg-green-500/5" : "border-border/50 bg-background",
                    )}>
                      <p className="text-[10px] font-semibold font-mono">
                        {snap.aggregateName}{snap.isNew && <span className="ml-1 text-green-600">(new)</span>}
                      </p>
                      <div className="mt-0.5 flex flex-wrap gap-0.5">
                        {snap.entities?.map(e => (
                          <span key={e} className="rounded bg-muted px-1 font-mono text-[9px] text-muted-foreground">{e}</span>
                        ))}
                      </div>
                      {snap.invariants?.length > 0 && (
                        <p className="mt-0.5 text-[9px] text-muted-foreground italic">{snap.invariants.join(", ")}</p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {c.blueprint.entityMoves && c.blueprint.entityMoves.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1.5">Entity Moves</p>
              <ul className="space-y-1">
                {c.blueprint.entityMoves.map((m, i) => (
                  <li key={i} className="text-xs flex items-start gap-1 flex-wrap">
                    <span className="font-mono text-foreground/80 shrink-0">{m.entityName}</span>
                    <span className="text-muted-foreground shrink-0">{m.fromAggregate}</span>
                    <ArrowRight className="h-3 w-3 text-muted-foreground shrink-0 mt-0.5" />
                    <span className="font-mono text-foreground/80 shrink-0">{m.toAggregate}</span>
                    {m.rationale && (
                      <span className="text-muted-foreground text-[10px] italic">— {m.rationale}</span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {c.blueprint.invariantChanges && c.blueprint.invariantChanges.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1.5">Invariant Changes</p>
              <ul className="space-y-1">
                {c.blueprint.invariantChanges.map((ic, i) => (
                  <li key={i} className="text-xs flex items-center gap-1.5 flex-wrap">
                    <span className="font-mono text-foreground/80 shrink-0">{ic.invariantName}</span>
                    <span className={cn(
                      "rounded px-1 py-0.5 text-[9px] font-medium shrink-0",
                      ic.changeType === "BECOMES_INTRA" ? "bg-green-500/10 text-green-600 dark:text-green-400" :
                      ic.changeType === "MOVES"         ? "bg-blue-500/10 text-blue-600 dark:text-blue-400" :
                      ic.changeType === "STAYS"         ? "bg-muted text-muted-foreground" :
                                                         "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400"
                    )}>{ic.changeType}</span>
                    <span className="text-muted-foreground text-[10px]">{ic.fromLocation} → {ic.toLocation}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {c.blueprint.metricImpact && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1.5">Metric Impact</p>
              <div className="space-y-1">
                {metricRows(c.blueprint.metricImpact).map(({ label, before, after }) => {
                  const delta = typeof before === "number" && typeof after === "number" && before !== 0
                    ? ((after - before) / before) * 100 : null
                  return (
                    <div key={label} className="flex items-center gap-2 text-xs">
                      <span className="w-28 shrink-0 text-muted-foreground text-[10px]">{label}</span>
                      <span className="font-mono tabular-nums text-foreground/80">{fmtVal(before)}</span>
                      <ArrowRight className="h-3 w-3 text-muted-foreground shrink-0" />
                      <span className="font-mono tabular-nums text-foreground/80">{fmtVal(after)}</span>
                      {delta != null && (
                        <span className={cn(
                          "ml-auto text-[10px] font-medium",
                          delta < 0 ? "text-green-600 dark:text-green-400" : "text-red-500 dark:text-red-400",
                        )}>
                          {delta > 0 ? "+" : ""}{delta.toFixed(1)}%
                        </span>
                      )}
                    </div>
                  )
                })}
                {c.blueprint.metricImpact.estimationNote && (
                  <p className="text-[10px] text-muted-foreground italic pt-1">
                    {c.blueprint.metricImpact.estimationNote}
                  </p>
                )}
              </div>
            </div>
          )}

          {c.blueprint.implementationSteps && c.blueprint.implementationSteps.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-muted-foreground mb-1.5">Implementation Steps</p>
              <ol className="space-y-1">
                {c.blueprint.implementationSteps.map((step, i) => (
                  <li key={i} className="text-xs flex gap-2">
                    <span className="w-4 text-muted-foreground shrink-0">{i + 1}.</span>
                    <span className="text-foreground/80">{step}</span>
                  </li>
                ))}
              </ol>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
