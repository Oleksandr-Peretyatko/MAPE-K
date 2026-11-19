"use client"

import { useState, useEffect, useCallback } from "react"
import { Trash2, Plus, Settings, GitBranch, Network, SlidersHorizontal, Star, BarChart3 } from "lucide-react"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { api, REFACTOR_OPERATIONS, type AppSettings, type RefactorConstraint, type ArchGraph, type ConstraintContext, type OperationName } from "@/lib/api"
import { ArchitectureGraph } from "@/components/config/ArchitectureGraph"
import { AnalysisConfigEditor } from "@/components/config/AnalysisConfigEditor"
import { QualityAttributeEditor } from "@/components/config/QualityAttributeEditor"
import { OperationScoreMatrix } from "@/components/config/OperationScoreMatrix"

export default function ConfigPage() {
  const [settings, setSettings] = useState<AppSettings | null>(null)
  const [constraints, setConstraints] = useState<RefactorConstraint[]>([])
  const [graph, setGraph] = useState<ArchGraph | null>(null)
  const [loadingSettings, setLoadingSettings] = useState(false)
  const [loadingConstraints, setLoadingConstraints] = useState(false)
  const [loadingGraph, setLoadingGraph] = useState(false)
  const [constraintContext, setConstraintContext] = useState<ConstraintContext | null>(null)

  // New constraint form state
  // "ANY" is the sentinel for an unscoped constraint — it is not an operation name.
  const [newOp, setNewOp] = useState<OperationName | "ANY">("ANY")
  const [newTarget, setNewTarget] = useState("")
  const [newSecondary, setNewSecondary] = useState("")
  const [newEntity, setNewEntity] = useState("")
  const [newReason, setNewReason] = useState("")
  const [newBlocking, setNewBlocking] = useState(true)
  const [saving, setSaving] = useState(false)

  // Services from live static analysis; fall back to YAML seed list only when context unavailable
  const contextServices = constraintContext?.services ?? []
  const knownServices = contextServices.length > 0
    ? contextServices.map(s => s.serviceName)
    : (settings?.monitor?.knownServices ?? [])

  // Entities for the currently selected target service
  const entityOptions: string[] = (() => {
    if (!newTarget || newTarget === "ANY") {
      return Array.from(new Set(contextServices.flatMap(s => s.entityNames))).sort()
    }
    return contextServices.find(s => s.serviceName === newTarget)?.entityNames ?? []
  })()

  const loadSettings = useCallback(async () => {
    setLoadingSettings(true)
    try { setSettings(await api.config.settings()) } catch { /* ignore */ }
    finally { setLoadingSettings(false) }
  }, [])

  const loadConstraints = useCallback(async () => {
    setLoadingConstraints(true)
    try { setConstraints(await api.config.constraints.list()) } catch { /* ignore */ }
    finally { setLoadingConstraints(false) }
  }, [])

  const loadConstraintContext = useCallback(async () => {
    try { setConstraintContext(await api.config.constraints.context()) } catch { /* ignore */ }
  }, [])

  const loadGraph = useCallback(async () => {
    setLoadingGraph(true)
    try { setGraph(await api.config.graph()) } catch { /* ignore */ }
    finally { setLoadingGraph(false) }
  }, [])

  useEffect(() => {
    loadSettings()
    loadConstraints()
    loadConstraintContext()
  }, [loadSettings, loadConstraints, loadConstraintContext])

  async function addConstraint() {
    if (!newReason.trim()) return
    setSaving(true)
    try {
      await api.config.constraints.create({
        operation: newOp === "ANY" ? undefined : newOp,
        targetService: newTarget.trim() || undefined,
        secondaryTarget: newSecondary.trim() || undefined,
        targetEntity: newEntity.trim() || undefined,
        reason: newReason.trim(),
        blocking: newBlocking,
      })
      setNewOp("ANY"); setNewTarget(""); setNewSecondary(""); setNewEntity(""); setNewReason("")
      await loadConstraints()
    } catch { /* ignore */ }
    finally { setSaving(false) }
  }

  async function deleteConstraint(id: string) {
    try {
      await api.config.constraints.delete(id)
      setConstraints((prev) => prev.filter((c) => c.id !== id))
    } catch { /* ignore */ }
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-lg font-semibold text-foreground">Configuration</h1>
        <p className="text-xs text-muted-foreground">Runtime settings, refactoring rules, and architecture graph</p>
      </div>

      <Tabs defaultValue="settings">
        <TabsList className="mb-4 flex-wrap h-auto gap-1">
          <TabsTrigger value="settings" className="gap-1.5"><Settings className="h-3.5 w-3.5" />General Settings</TabsTrigger>
          <TabsTrigger value="quality-attributes" className="gap-1.5"><Star className="h-3.5 w-3.5" />Quality Attributes</TabsTrigger>
          <TabsTrigger value="operation-scores" className="gap-1.5"><BarChart3 className="h-3.5 w-3.5" />Operation Scores</TabsTrigger>
          <TabsTrigger value="rules" className="gap-1.5"><GitBranch className="h-3.5 w-3.5" />Refactoring Rules</TabsTrigger>
          <TabsTrigger value="graph" className="gap-1.5" onClick={() => { if (!graph) loadGraph() }}><Network className="h-3.5 w-3.5" />Architecture Graph</TabsTrigger>
          <TabsTrigger value="analysis-config" className="gap-1.5"><SlidersHorizontal className="h-3.5 w-3.5" />Analysis Config</TabsTrigger>
        </TabsList>

        {/* ─── Tab A: General Settings ──────────────────────────────────────── */}
        <TabsContent value="settings" className="space-y-4">
          {loadingSettings && <p className="text-xs text-muted-foreground">Loading settings…</p>}
          {settings && (
            <>
              <SettingsSection title="Execute Phase" entries={[
                { label: "Base Branch",      value: settings.execute.baseBranch },
                { label: "Build Profiles",   value: settings.execute.buildProfiles },
                { label: "Deploy Target",    value: `${settings.execute.deployTarget} (${settings.execute.deployMode})` },
                { label: "Deploy Services",  value: `${(settings.execute.deployServices ?? []).join(", ") || "—"} [${settings.execute.deployTopologySource}]` },
                { label: "Project Root",     value: settings.execute.projectRoot },
                { label: "Compose File",     value: settings.execute.composeFile },
                { label: "Git User",         value: `${settings.execute.gitUserName} <${settings.execute.gitUserEmail}>` },
              ]} />

              <SettingsSection title="Monitor Phase" entries={[
                { label: "Jaeger Base URL",       value: settings.monitor.jaegerBaseUrl },
                { label: "Collection Window",     value: `${settings.monitor.collectionWindowMinutes} min` },
                { label: "Locust Target Host",    value: settings.monitor.locustTargetHost },
                { label: "Known Services",        value: settings.monitor.knownServices.join(", ") },
              ]} />
            </>
          )}
        </TabsContent>

        {/* ─── Tab A2: Quality Attributes ──────────────────────────────────── */}
        <TabsContent value="quality-attributes" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Quality Attributes</CardTitle>
              <p className="text-xs text-muted-foreground">Global catalog, shared across projects. Used as priority selectors in the Analyze phase.</p>
            </CardHeader>
            <CardContent>
              <QualityAttributeEditor />
            </CardContent>
          </Card>
        </TabsContent>

        {/* ─── Tab A3: Operation Scores ─────────────────────────────────────── */}
        <TabsContent value="operation-scores" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Operation → QA Score Matrix</CardTitle>
              <p className="text-xs text-muted-foreground">Per-project. Scores drive the SIMPLE_RANK algorithm. Operations with score ≤ 0 for the primary QA are excluded.</p>
            </CardHeader>
            <CardContent>
              <OperationScoreMatrix />
            </CardContent>
          </Card>
        </TabsContent>

        {/* ─── Tab B: Refactoring Rules ─────────────────────────────────────── */}
        <TabsContent value="rules" className="space-y-4">
          {/* Add rule form */}
          <Card>
            <CardHeader><CardTitle className="text-sm">Add Constraint Rule</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
                <div className="space-y-1">
                  <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Operation</label>
                  <Select value={newOp} onValueChange={(v) => setNewOp(v as OperationName | "ANY")}>
                    <SelectTrigger className="h-8 text-xs"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ANY">Any operation</SelectItem>
                      {REFACTOR_OPERATIONS.map((op) => <SelectItem key={op} value={op}>{op}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Target Service</label>
                  <Select value={newTarget || "ANY"} onValueChange={(v) => { setNewTarget(v === "ANY" ? "" : v); setNewEntity("") }}>
                    <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Any service" /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ANY">Any service</SelectItem>
                      {knownServices.map((s) => <SelectItem key={s} value={s}>{s}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Secondary Target</label>
                  <Select value={newSecondary || "ANY"} onValueChange={(v) => setNewSecondary(v === "ANY" ? "" : v)}>
                    <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Any" /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ANY">Any</SelectItem>
                      {knownServices.map((s) => <SelectItem key={s} value={s}>{s}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Entity</label>
                  <Select value={newEntity || "ANY"} onValueChange={(v) => setNewEntity(v === "ANY" ? "" : v)}>
                    <SelectTrigger className="h-8 text-xs"><SelectValue placeholder="Any entity" /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ANY">Any entity</SelectItem>
                      {entityOptions.map((e) => <SelectItem key={e} value={e}>{e}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Type</label>
                  <Select value={newBlocking ? "blocking" : "warning"} onValueChange={(v) => setNewBlocking(v === "blocking")}>
                    <SelectTrigger className="h-8 text-xs"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="blocking">Blocking (filters out)</SelectItem>
                      <SelectItem value="warning">Warning (badge only)</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <div className="flex gap-2">
                <input
                  className="flex-1 h-8 rounded-md border border-input bg-transparent px-3 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
                  placeholder="Reason (e.g. 'tournament + user merge not allowed by architecture decision ADR-7')"
                  value={newReason}
                  onChange={(e) => setNewReason(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") addConstraint() }}
                />
                <Button size="sm" onClick={addConstraint} disabled={saving || !newReason.trim()} className="gap-1.5 shrink-0">
                  <Plus className="h-3.5 w-3.5" />
                  {saving ? "Saving…" : "Add Rule"}
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* Rules list */}
          <Card>
            <CardHeader><CardTitle className="text-sm">Active Rules ({constraints.length})</CardTitle></CardHeader>
            <CardContent>
              {loadingConstraints && <p className="text-xs text-muted-foreground">Loading…</p>}
              {!loadingConstraints && constraints.length === 0 && (
                <p className="text-xs text-muted-foreground">No constraints defined. All operations are permitted.</p>
              )}
              {constraints.length > 0 && (
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-border text-muted-foreground">
                      <th className="py-2 pr-3 text-left">Operation</th>
                      <th className="py-2 pr-3 text-left">Target</th>
                      <th className="py-2 pr-3 text-left">Secondary</th>
                      <th className="py-2 pr-3 text-left">Entity</th>
                      <th className="py-2 pr-3 text-left">Reason</th>
                      <th className="py-2 pr-3 text-center">Type</th>
                      <th className="py-2 text-right"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {constraints.map((c) => (
                      <tr key={c.id} className="border-b border-border/40">
                        <td className="py-2 pr-3 font-mono text-foreground/80">{c.operation ?? <span className="text-muted-foreground/50">Any</span>}</td>
                        <td className="py-2 pr-3 text-muted-foreground">{c.targetService ?? <span className="opacity-40">Any</span>}</td>
                        <td className="py-2 pr-3 text-muted-foreground">{c.secondaryTarget ?? <span className="opacity-40">Any</span>}</td>
                        <td className="py-2 pr-3 text-muted-foreground font-mono">{c.targetEntity ?? <span className="opacity-40">Any</span>}</td>
                        <td className="py-2 pr-3 text-foreground/80 max-w-xs truncate">{c.reason}</td>
                        <td className="py-2 pr-3 text-center">
                          {c.blocking
                            ? <Badge variant="destructive" className="text-[10px]">Blocking</Badge>
                            : <Badge variant="warning" className="text-[10px]">Warning</Badge>}
                        </td>
                        <td className="py-2 text-right">
                          <button
                            onClick={() => c.id && deleteConstraint(c.id)}
                            className="text-muted-foreground hover:text-destructive transition-colors"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ─── Tab C: Architecture Graph ────────────────────────────────────── */}
        <TabsContent value="graph">
          {loadingGraph && <p className="text-xs text-muted-foreground p-4">Loading architecture graph…</p>}
          {graph && <ArchitectureGraph graph={graph} />}
          {!graph && !loadingGraph && (
            <div className="rounded-lg border border-border p-8 text-center">
              <p className="text-sm text-muted-foreground">Click the Architecture Graph tab to load.</p>
            </div>
          )}
        </TabsContent>
        {/* ─── Tab D: Analysis Config ───────────────────────────────────────── */}
        <TabsContent value="analysis-config">
          <AnalysisConfigEditor />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function SettingsSection({ title, entries }: { title: string; entries: { label: string; value: string }[] }) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-sm">{title}</CardTitle></CardHeader>
      <CardContent>
        <dl className="grid gap-2 sm:grid-cols-2">
          {entries.map(({ label, value }) => (
            <div key={label} className="flex items-start gap-2 rounded-md border border-border px-3 py-2">
              <dt className="shrink-0 w-32 text-[10px] text-muted-foreground">{label}</dt>
              <dd className="font-mono text-xs text-foreground break-all">{value}</dd>
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  )
}
