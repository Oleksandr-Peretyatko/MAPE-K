"use client"

import { useCallback, useEffect, useState, type ReactNode } from "react"
import ReactMarkdown from "react-markdown"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Button } from "@/components/ui/button"
import { AlertCircle, ArrowRight, Download, Loader2, Sparkles } from "lucide-react"
import { Check, Pencil, X } from "lucide-react"
import { api, type CandidateOperation, type PlanResponse, type PlanVersion, type PlanVersionSummary } from "@/lib/api"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { PhaseExportButton } from "@/components/shared/PhaseExportButton"

const VERSION_KIND_LABEL: Record<string, string> = {
  ORIGINAL: "Original",
  ENRICHED: "Enhanced",
  USER_EDIT: "Edited",
}

function ApprovalBadge({ state }: { state: string }) {
  const cls =
    state === "APPROVED" ? "bg-green-500/10 text-green-700 dark:text-green-400" :
    state === "REJECTED" ? "bg-red-500/10 text-red-700 dark:text-red-400" :
                           "bg-amber-500/10 text-amber-700 dark:text-amber-400"
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${cls}`}>{state}</span>
}

function StatusBadge({ status }: { status: string }) {
  const color =
    status === "scheduled"   ? "bg-blue-500/10 text-blue-600 dark:text-blue-400" :
    status === "done"        ? "bg-green-500/10 text-green-700 dark:text-green-400" :
    status === "rolled_back" ? "bg-orange-500/10 text-orange-700 dark:text-orange-400" :
                               "bg-red-500/10 text-red-700 dark:text-red-400"
  return <span className={`rounded px-2 py-1 text-xs font-semibold ${color}`}>{status}</span>
}

function OverviewCard({ plan }: { plan: PlanResponse }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center justify-between gap-3">
          <span className="text-base">{plan.operation}</span>
          <div className="flex items-center gap-2">
            {plan.activeVersionNumber != null && (
              <span className="rounded bg-muted px-2 py-0.5 text-xs font-medium">
                Active: v{plan.activeVersionNumber}
                {plan.approvalState ? ` · ${plan.approvalState}` : ""}
              </span>
            )}
            <StatusBadge status={plan.status} />
          </div>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm">
          <div>
            <span className="text-muted-foreground">Target service</span>
            <p className="font-mono font-medium">{plan.targetService}</p>
          </div>
          <div>
            <span className="text-muted-foreground">Plan ID</span>
            <p className="font-mono text-xs">{plan.planId}</p>
          </div>
          <div>
            <span className="text-muted-foreground">Created</span>
            <p>{new Date(plan.createdAt).toLocaleString()}</p>
          </div>
          {plan.snapshotId && (
            <div>
              <span className="text-muted-foreground">Snapshot</span>
              <p className="font-mono text-xs">{plan.snapshotId}</p>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function MetaList({ label, items }: { label: string; items?: string[] }) {
  if (!items || items.length === 0) return null
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-4 text-sm">
        {items.map((it, i) => (
          <li key={i}>{it}</li>
        ))}
      </ul>
    </div>
  )
}

/**
 * Displays the Analyze-phase metadata for the selected candidate so the user can review the
 * rationale, evidence, predicted impact, risks, and constraints while polishing the plan.
 */
function RecommendationMetaCard({ rec }: { rec: CandidateOperation }) {
  const secondary = rec.secondaryQaEffects
    ? Object.entries(rec.secondaryQaEffects as Record<string, unknown>)
    : []
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <Sparkles className="h-4 w-4 text-primary" />
          Selected Candidate
          {rec.blockedBy && (
            <span className="rounded bg-red-500/10 px-2 py-0.5 text-xs font-semibold text-red-600 dark:text-red-400">
              blocked
            </span>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {rec.whyApplicable && <p className="text-sm">{rec.whyApplicable}</p>}

        <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm sm:grid-cols-3">
          {rec.primaryQaTarget && (
            <div>
              <span className="text-muted-foreground">Primary QA</span>
              <p className="font-medium">{rec.primaryQaTarget}</p>
            </div>
          )}
          {rec.primaryQaScore !== undefined && (
            <div>
              <span className="text-muted-foreground">Primary QA score</span>
              <p className="font-medium">{rec.primaryQaScore > 0 ? `+${rec.primaryQaScore}` : rec.primaryQaScore}</p>
            </div>
          )}
        </div>

        {secondary.length > 0 && (
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Secondary QA effects</p>
            <ul className="mt-1 space-y-0.5 text-sm">
              {secondary.map(([qa, effect]) => (
                <li key={qa}>
                  <span className="font-medium">{qa}:</span> {String(effect)}
                </li>
              ))}
            </ul>
          </div>
        )}

        {rec.rankReason && (
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Rank rationale</p>
            <p className="mt-1 text-sm">{rec.rankReason}</p>
          </div>
        )}

        <MetaList label="Preconditions" items={rec.preconditions} />
        <MetaList label="Risks" items={rec.risks} />
        <MetaList label="Dependencies" items={rec.dependencies} />
        <MetaList label="Affected entities" items={rec.affectedEntities} />
        {rec.blockedBy && <MetaList label="Blocked by" items={[rec.blockedBy]} />}
        <MetaList label="Warnings" items={rec.warnings} />
      </CardContent>
    </Card>
  )
}

function EmptyState({ message }: { message: ReactNode }) {
  return (
    <Card>
      <CardContent className="py-12 text-center text-muted-foreground">
        <p className="text-sm">{message}</p>
      </CardContent>
    </Card>
  )
}

function buildCombinedMarkdown(plan: PlanResponse): string {
  const header = [
    `# Refactoring Plan: ${plan.operation} — ${plan.targetService}`,
    ``,
    `> Generated: ${new Date(plan.createdAt).toLocaleString()}`,
    `> Plan ID: \`${plan.planId}\``,
    ``,
  ]
  const sections: string[] = []
  if (plan.enrichedDocument) {
    sections.push(`---\n\n## Polished Plan\n\n${plan.enrichedDocument}\n`)
  }
  if (plan.document) {
    sections.push(`---\n\n## Original Plan\n\n${plan.document}\n`)
  }
  return [...header, ...sections].join('\n')
}

function handleDownload(plan: PlanResponse) {
  const blob = new Blob([buildCombinedMarkdown(plan)], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `plan-${plan.operation.toLowerCase().replace(/_/g, '-')}-${plan.targetService}.md`
  a.click()
  URL.revokeObjectURL(url)
}

interface PlanPhaseProps {
  pendingCandidate?: CandidateOperation | null
  onCandidateConsumed?: () => void
  onGoToExecute?: () => void
}

export function PlanPhase({ pendingCandidate, onCandidateConsumed, onGoToExecute }: PlanPhaseProps) {
  const { snapshotId, iterationId } = useSnapshot()
  const [plan, setPlan] = useState<PlanResponse | null>(null)
  const [selectedCandidate, setSelectedCandidate] = useState<CandidateOperation | null>(null)
  const [subTab, setSubTab] = useState<"plan" | "prompt">("plan")
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Auto-load existing plan when iteration changes
  useEffect(() => {
    if (!iterationId) { setPlan(null); return }
    let cancelled = false
    api.knowledge.iterations.get(iterationId)
      .then(detail => {
        if (cancelled || !detail.planId) return null
        return api.plan.get(detail.planId)
      })
      .then(p => {
        if (!cancelled) setPlan(p ?? null)
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [iterationId])

  const handleGenerate = useCallback(async (candidate: CandidateOperation) => {
    setGenerating(true)
    setError(null)
    setSelectedCandidate(candidate)
    try {
      const result = await api.plan.generate({
        operation: candidate.operation,
        targetService: candidate.targetService,
        secondaryTarget: candidate.secondaryTarget,
        snapshotId: snapshotId ?? undefined,
        qualityPriorities: [],
        preconditionsVerified: false,
        risksAcknowledged: false,
        // Fall back to the bare skill slug when the backend did not derive a signature.
        // MERGE_AGGREGATES is the one operation whose slug is not its lowercased name.
        skillInvocation: candidate.commandSignature
          ? candidate.commandSignature.replace(/^\//, "")
          : candidate.operation === "MERGE_AGGREGATES"
            ? "merge-aggregate"
            : candidate.operation.toLowerCase().replace(/_/g, "-"),
        skillPrompt: candidate.commandSignature
          ? candidate.commandSignature.replace(/^\//, "")
          : undefined,
        blueprint: candidate.blueprint,
        iterationId: iterationId ?? undefined,
      })
      setPlan(result)
      if (iterationId) {
        api.knowledge.iterations.linkPlan(iterationId, result.planId).catch(() => {})
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to generate plan")
    } finally {
      setGenerating(false)
    }
  }, [snapshotId, iterationId])

  // Auto-generate when a candidate is passed from the Analyze tab
  useEffect(() => {
    if (!pendingCandidate) return
    handleGenerate(pendingCandidate)
    onCandidateConsumed?.()
  }, [pendingCandidate]) // eslint-disable-line react-hooks/exhaustive-deps

  // ─── Plan versions ─────────────────────────────────────────────────────────
  const [versions, setVersions] = useState<PlanVersionSummary[]>([])
  const [versionRefresh, setVersionRefresh] = useState(0)
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null)
  const [selectedVersion, setSelectedVersion] = useState<PlanVersion | null>(null)
  const [editing, setEditing] = useState(false)
  const [editText, setEditText] = useState("")
  const [editSaving, setEditSaving] = useState(false)
  const [approving, setApproving] = useState(false)

  // Load the version list for the current plan (and after edit/approve).
  useEffect(() => {
    const planId = plan?.planId
    if (!planId) { setVersions([]); setSelectedVersionId(null); setSelectedVersion(null); return }
    let cancelled = false
    api.plan.versions(planId)
      .then(vs => {
        if (cancelled) return
        setVersions(vs)
        setSelectedVersionId(prev =>
          prev && vs.some(v => v.planVersionId === prev)
            ? prev
            : (plan?.activePlanVersionId ?? vs[vs.length - 1]?.planVersionId ?? null))
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [plan?.planId, plan?.activePlanVersionId, versionRefresh])

  // Load the full document for the selected version.
  useEffect(() => {
    if (!selectedVersionId) { setSelectedVersion(null); return }
    let cancelled = false
    api.plan.getVersion(selectedVersionId)
      .then(v => { if (!cancelled) { setSelectedVersion(v); setEditing(false) } })
      .catch(() => {})
    return () => { cancelled = true }
  }, [selectedVersionId])

  const reloadPlan = useCallback(async () => {
    if (!plan?.planId) return
    try {
      const fresh = await api.plan.get(plan.planId)
      setPlan(fresh)
    } catch { /* best-effort */ }
    setVersionRefresh(x => x + 1)
  }, [plan?.planId])

  const handleApproval = useCallback(async (state: "APPROVED" | "REJECTED") => {
    if (!selectedVersionId) return
    setApproving(true)
    try {
      const updated = state === "APPROVED"
        ? await api.plan.approveVersion(selectedVersionId)
        : await api.plan.rejectVersion(selectedVersionId)
      setSelectedVersion(updated)
      await reloadPlan()
    } catch { /* surfaced via reload */ } finally {
      setApproving(false)
    }
  }, [selectedVersionId, reloadPlan])

  const handleSaveEdit = useCallback(async () => {
    if (!plan?.planId || !editText.trim()) return
    setEditSaving(true)
    try {
      const created = await api.plan.saveEdit(plan.planId, { document: editText })
      setEditing(false)
      setSelectedVersionId(created.planVersionId)
      await reloadPlan()
    } catch { /* best-effort */ } finally {
      setEditSaving(false)
    }
  }, [plan?.planId, editText, reloadPlan])

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-semibold text-foreground">Plan</h1>
          <p className="text-xs text-muted-foreground">MAPE-K Phase 3 — Define and orchestrate strategies for system adaptation</p>
        </div>
        <PhaseExportButton
          url={plan?.planId ? api.plan.exportUrl(plan.planId) : null}
          filename={plan?.planId ? `plan-${plan.planId}.json` : "plan.json"}
        />
      </div>

      <Tabs value={subTab} onValueChange={(v) => setSubTab(v as typeof subTab)} className="flex flex-col">
        <TabsList className="w-fit">
          <TabsTrigger value="plan">Plan Document</TabsTrigger>
          <TabsTrigger value="prompt">Complete Prompt</TabsTrigger>
        </TabsList>

        {/* Unified Plan Document */}
        <TabsContent value="plan" className="mt-4 space-y-4">
          {error && (
            <div className="flex items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4">
              <AlertCircle className="w-5 h-5 text-destructive flex-shrink-0 mt-0.5" />
              <p className="text-sm text-destructive">{error}</p>
            </div>
          )}
          {generating && (
            <div className="flex items-center gap-3 text-muted-foreground">
              <Loader2 className="w-5 h-5 animate-spin" />
              <span className="text-sm">Generating plan document…</span>
            </div>
          )}
          {!plan && !generating && !error && (
            <EmptyState
              message={
                <>
                  No plan loaded. Select a refactoring candidate from the{" "}
                  <strong>Analyze</strong> tab to generate a plan document.
                </>
              }
            />
          )}
          {plan && (
            <div className="space-y-4">
              <OverviewCard plan={plan} />
              {selectedCandidate && <RecommendationMetaCard rec={selectedCandidate} />}

              {/* Action bar */}
              <div className="flex items-center justify-end gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="gap-1.5"
                  onClick={() => handleDownload(plan)}
                >
                  <Download className="h-3.5 w-3.5" />
                  Download .md
                </Button>
                <Button
                  size="sm"
                  className="gap-1.5"
                  onClick={onGoToExecute}
                  disabled={!onGoToExecute}
                >
                  Go to Execute
                  <ArrowRight className="h-3.5 w-3.5" />
                </Button>
              </div>

              {/* Version history + viewer */}
              {versions.length > 0 && (
                <Card>
                  <CardHeader className="pb-3">
                    <CardTitle className="text-sm">Plan Versions</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {/* Switcher */}
                    <div className="flex flex-wrap gap-2">
                      {versions.map(v => {
                        const isSelected = v.planVersionId === selectedVersionId
                        const isActive = v.planVersionId === plan.activePlanVersionId
                        return (
                          <button
                            key={v.planVersionId}
                            onClick={() => setSelectedVersionId(v.planVersionId)}
                            className={`flex items-center gap-1.5 rounded border px-2.5 py-1.5 text-xs transition-colors ${
                              isSelected
                                ? "border-primary bg-primary/10"
                                : "border-border hover:bg-muted/50"
                            }`}
                          >
                            <span className="font-semibold">v{v.versionNumber}</span>
                            <span className="text-muted-foreground">
                              {VERSION_KIND_LABEL[v.kind] ?? v.kind}
                            </span>
                            <ApprovalBadge state={v.approvalState} />
                            {isActive && (
                              <span className="rounded bg-primary/15 px-1 py-0.5 text-[10px] font-semibold text-primary">
                                ACTIVE
                              </span>
                            )}
                          </button>
                        )
                      })}
                    </div>

                    {/* Selected version viewer */}
                    {selectedVersion && (
                      <div className="space-y-3 rounded-lg border p-4">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex items-center gap-2 text-sm">
                            <span className="font-semibold">v{selectedVersion.versionNumber}</span>
                            <span className="text-muted-foreground">
                              {VERSION_KIND_LABEL[selectedVersion.kind] ?? selectedVersion.kind}
                            </span>
                            <ApprovalBadge state={selectedVersion.approvalState} />
                            {selectedVersion.model && (
                              <span className="text-xs text-muted-foreground">· {selectedVersion.model}</span>
                            )}
                            <span className="text-xs text-muted-foreground">
                              · {new Date(selectedVersion.createdAt).toLocaleString()}
                            </span>
                          </div>
                          {!editing && (
                            <div className="flex items-center gap-1.5">
                              <Button
                                variant="outline"
                                size="sm"
                                className="gap-1.5"
                                onClick={() => { setEditText(selectedVersion.document); setEditing(true) }}
                              >
                                <Pencil className="h-3.5 w-3.5" />
                                Edit
                              </Button>
                              <Button
                                variant="outline"
                                size="sm"
                                className="gap-1.5 text-green-700 dark:text-green-400"
                                onClick={() => handleApproval("APPROVED")}
                                disabled={approving || selectedVersion.approvalState === "APPROVED"}
                              >
                                <Check className="h-3.5 w-3.5" />
                                Approve
                              </Button>
                              <Button
                                variant="outline"
                                size="sm"
                                className="gap-1.5 text-red-700 dark:text-red-400"
                                onClick={() => handleApproval("REJECTED")}
                                disabled={approving || selectedVersion.approvalState === "REJECTED"}
                              >
                                <X className="h-3.5 w-3.5" />
                                Reject
                              </Button>
                            </div>
                          )}
                        </div>

                        {editing ? (
                          <div className="space-y-2">
                            <textarea
                              value={editText}
                              onChange={(e) => setEditText(e.target.value)}
                              className="h-96 w-full resize-y rounded border bg-background p-3 font-mono text-xs"
                            />
                            <div className="flex items-center justify-end gap-2">
                              <Button variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={editSaving}>
                                Cancel
                              </Button>
                              <Button size="sm" className="gap-1.5" onClick={handleSaveEdit} disabled={editSaving || !editText.trim()}>
                                {editSaving && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                                Save as new version
                              </Button>
                            </div>
                          </div>
                        ) : (
                          <div className="prose prose-sm dark:prose-invert max-w-none">
                            <ReactMarkdown>{selectedVersion.document}</ReactMarkdown>
                          </div>
                        )}
                      </div>
                    )}
                  </CardContent>
                </Card>
              )}

              {/* Fallback for legacy plans with no version history */}
              {versions.length === 0 && (plan.enrichedDocument || plan.document) && (
                <Card>
                  <CardContent className="pt-6">
                    <div className="prose prose-sm dark:prose-invert max-w-none">
                      <ReactMarkdown>{plan.enrichedDocument || plan.document || ""}</ReactMarkdown>
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>
          )}
        </TabsContent>

        {/* Complete Prompt */}
        <TabsContent value="prompt" className="mt-4 space-y-6">
          {!plan ? (
            <EmptyState message="Generate a plan first to see the complete prompt." />
          ) : (
            <>
              <div className="space-y-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">1 · Command</p>
                <pre className="rounded bg-black/80 p-3 text-sm text-green-400 overflow-x-auto whitespace-pre-wrap">
                  /{plan.skillInvocation}
                </pre>
              </div>
              <div className="space-y-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">2 · Skill Instructions</p>
                {plan.skillContent ? (
                  <div className="prose prose-sm dark:prose-invert max-w-none rounded border bg-muted/30 p-6 select-none pointer-events-none">
                    <ReactMarkdown>{plan.skillContent}</ReactMarkdown>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground italic">No SKILL.md content available for this invocation.</p>
                )}
              </div>
              <div className="space-y-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">3 · Polish Plan</p>
                {plan.enrichedDocument ? (
                  <div className="prose prose-sm dark:prose-invert max-w-none rounded border bg-muted/30 p-6">
                    <ReactMarkdown>{plan.enrichedDocument}</ReactMarkdown>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground italic">Run Enhance with AI to generate the polished plan.</p>
                )}
              </div>
            </>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
