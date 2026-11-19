"use client"

import { useState, useEffect, useCallback } from "react"
import { Save, RotateCcw } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { api, REFACTOR_OPERATIONS, type AnalysisConfig, type QualityAttributeDef } from "@/lib/api"

const HOTSPOT_TYPES = [
  "HIGH_LATENCY", "TIGHT_COUPLING", "LOW_COHESION", "HIGH_ENTITY_COUNT",
  "HIGH_COMPENSATION", "READ_HEAVY", "CONSISTENCY_VIOLATION", "HIGH_ERROR_RATE",
]

const OPERATION_TYPES = REFACTOR_OPERATIONS

const FALLBACK_PRIORITIES = ["PERFORMANCE", "CONSISTENCY", "MAINTAINABILITY"]

export function AnalysisConfigEditor() {
  const [config, setConfig] = useState<AnalysisConfig | null>(null)
  const [qaOptions, setQaOptions] = useState<QualityAttributeDef[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [resetting, setResetting] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    try { setConfig(await api.config.analysisConfig.get()) } catch { /* ignore */ }
    finally { setLoading(false) }
    api.config.qualityAttributes.list()
      .then(list => setQaOptions(list.filter(q => q.active)))
      .catch(() => {})
  }, [])

  useEffect(() => { load() }, [load])

  function update(updater: (draft: AnalysisConfig) => void) {
    setConfig((prev) => {
      if (!prev) return prev
      const next = JSON.parse(JSON.stringify(prev)) as AnalysisConfig
      updater(next)
      return next
    })
    setDirty(true)
  }

  async function save() {
    if (!config) return
    setSaving(true)
    setError("")
    try { setConfig(await api.config.analysisConfig.update(config)); setDirty(false) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : "Failed to save analysis config") }
    finally { setSaving(false) }
  }

  async function reset() {
    setResetting(true)
    setError("")
    try { setConfig(await api.config.analysisConfig.reset()); setDirty(false) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : "Failed to reset analysis config") }
    finally { setResetting(false) }
  }

  if (loading) return <p className="text-xs text-muted-foreground">Loading analysis config…</p>
  if (!config) return <p className="text-xs text-destructive">Failed to load analysis config.</p>

  const priorities = config.defaultQualityPriorities ?? FALLBACK_PRIORITIES

  return (
    <div className="space-y-4">
      {/* Action bar */}
      <div className="flex items-center gap-2 justify-end">
        {dirty && <span className="text-[10px] text-amber-500">Unsaved changes</span>}
        <Button variant="outline" size="sm" className="gap-1.5 h-8 text-xs" onClick={reset} disabled={resetting || saving}>
          <RotateCcw className="h-3.5 w-3.5" />{resetting ? "Resetting…" : "Reset to Defaults"}
        </Button>
        <Button size="sm" className="gap-1.5 h-8 text-xs" onClick={save} disabled={!dirty || saving || resetting}>
          <Save className="h-3.5 w-3.5" />{saving ? "Saving…" : "Save"}
        </Button>
      </div>

      {error && (
        <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
          {error}
        </div>
      )}

      {/* Default quality priorities — the Analyze phase's fallback order */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Default Quality Priorities</CardTitle>
          <p className="text-[10px] text-muted-foreground mt-1">
            Ordered priorities (P1 is most important) used by Analyze whenever a run does not
            specify its own — including the automatic analysis after a Monitor snapshot.
          </p>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap items-center gap-3">
            {[0, 1, 2].map((slot) => (
              <div key={slot} className="flex items-center gap-2">
                <span className="text-xs text-muted-foreground">P{slot + 1}</span>
                <Select
                  value={priorities[slot] ?? ""}
                  onValueChange={(value) =>
                    update((d) => {
                      const next = [...(d.defaultQualityPriorities ?? FALLBACK_PRIORITIES)]
                      next[slot] = value
                      d.defaultQualityPriorities = next
                    })
                  }
                >
                  <SelectTrigger className="w-44"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {qaOptions.map((o) => (
                      <SelectItem
                        key={o.id}
                        value={o.name}
                        disabled={priorities.includes(o.name) && priorities[slot] !== o.name}
                      >
                        {o.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Hotspot Rules — which operations each hotspot type generates as candidates */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Stage 3 — Hotspot Rules</CardTitle>
          <p className="text-[10px] text-muted-foreground mt-1">
            Controls which operations are generated as candidates for each hotspot type.
            Uncheck to suppress an operation; empty row = no candidates for that hotspot.
          </p>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="text-[10px] w-full">
              <thead>
                <tr className="border-b border-border text-muted-foreground">
                  <th className="py-2 pr-4 text-left font-medium">Hotspot Type</th>
                  {OPERATION_TYPES.map((op) => (
                    <th key={op} className="py-2 px-2 text-center font-mono writing-vertical" title={op}>
                      <span className="block truncate max-w-[56px]">{op.replace(/_/g, " ")}</span>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {HOTSPOT_TYPES.map((ht) => {
                  const allowed = config.hotspotRules[ht] ?? OPERATION_TYPES
                  return (
                    <tr key={ht} className="border-b border-border/40 hover:bg-muted/20">
                      <td className="py-2 pr-4 font-mono text-foreground/80">{ht}</td>
                      {OPERATION_TYPES.map((op) => (
                        <td key={op} className="py-2 px-2 text-center">
                          <input
                            type="checkbox"
                            className="h-3.5 w-3.5 accent-primary"
                            checked={allowed.includes(op)}
                            onChange={(e) =>
                              update((d) => {
                                const cur = d.hotspotRules[ht] ?? [...OPERATION_TYPES]
                                if (e.target.checked) {
                                  if (!cur.includes(op)) cur.push(op)
                                } else {
                                  const idx = cur.indexOf(op)
                                  if (idx >= 0) cur.splice(idx, 1)
                                }
                                d.hotspotRules[ht] = cur
                              })
                            }
                          />
                        </td>
                      ))}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
