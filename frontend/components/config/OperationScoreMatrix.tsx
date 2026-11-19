"use client"

import { useState, useEffect, useCallback } from "react"
import { Save, RotateCcw } from "lucide-react"
import { Button } from "@/components/ui/button"
import { api, OPERATION_LABELS, REFACTOR_OPERATIONS, type QualityAttributeDef } from "@/lib/api"

const SCORE_OPTIONS = [-2, -1, 0, 1, 2] as const
const SCORE_LABELS: Record<number, string> = {
  "-2": "-2 (harmful)", "-1": "-1", "0": "0 (neutral)", "1": "+1", "2": "+2 (strong)"
}

const SCORE_COLOR: Record<number, string> = {
  "-2": "text-red-600 dark:text-red-400",
  "-1": "text-orange-500 dark:text-orange-400",
  "0":  "text-muted-foreground",
  "1":  "text-blue-600 dark:text-blue-400",
  "2":  "text-green-600 dark:text-green-400",
}

export function OperationScoreMatrix() {
  const [qas, setQas] = useState<QualityAttributeDef[]>([])
  const [scores, setScores] = useState<Record<string, Record<string, number>>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    setError("")
    try {
      const [qaList, scoreMap] = await Promise.all([
        api.config.qualityAttributes.list(),
        api.config.operationScores.get(),
      ])
      setQas(qaList)
      setScores(scoreMap)
    } catch {
      setError("Failed to load score matrix")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  function handleChange(operation: string, qaId: string, value: number) {
    setScores(prev => ({
      ...prev,
      [operation]: { ...(prev[operation] ?? {}), [qaId]: value },
    }))
    setSaved(false)
  }

  async function handleSave() {
    setSaving(true)
    setError("")
    setSaved(false)
    try {
      const updated = await api.config.operationScores.update(scores)
      setScores(updated)
      setSaved(true)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to save")
    } finally {
      setSaving(false)
    }
  }

  async function handleReset() {
    setSaving(true)
    setError("")
    try {
      const updated = await api.config.operationScores.reset()
      setScores(updated)
      setSaved(false)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to reset")
    } finally {
      setSaving(false)
    }
  }

  const operations = REFACTOR_OPERATIONS

  if (loading) return <p className="text-xs text-muted-foreground">Loading…</p>

  return (
    <div className="space-y-4">
      <p className="text-xs text-muted-foreground">
        Assign how each refactoring operation affects each quality attribute.
        Values: <span className="text-red-600">-2</span> (harmful) → <span className="text-green-600">+2</span> (strongly improves).
        Operations with score ≤ 0 for the selected primary QA are excluded from SIMPLE_RANK results.
      </p>

      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full text-xs">
          <thead className="bg-muted/40">
            <tr>
              <th className="text-left px-3 py-2 font-medium text-muted-foreground whitespace-nowrap">Operation</th>
              {qas.map(qa => (
                <th key={qa.id} className="text-center px-2 py-2 font-medium text-muted-foreground whitespace-nowrap min-w-[90px]">
                  {qa.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {operations.map(op => (
              <tr key={op} className="border-t border-border/50 hover:bg-muted/20">
                <td className="px-3 py-2 font-mono whitespace-nowrap">{OPERATION_LABELS[op]}</td>
                {qas.map(qa => {
                  const val = scores[op]?.[qa.id] ?? 0
                  return (
                    <td key={qa.id} className="px-2 py-1 text-center">
                      <select
                        value={val}
                        onChange={e => handleChange(op, qa.id, Number(e.target.value))}
                        className={`h-7 w-24 rounded border border-input bg-background px-1 text-xs text-center focus:outline-none focus:ring-1 focus:ring-ring ${SCORE_COLOR[val]}`}
                      >
                        {SCORE_OPTIONS.map(s => (
                          <option key={s} value={s} className="text-foreground">{SCORE_LABELS[s]}</option>
                        ))}
                      </select>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {error && (
        <p className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">{error}</p>
      )}
      {saved && (
        <p className="rounded-md border border-green-500/30 bg-green-500/5 px-3 py-2 text-xs text-green-600 dark:text-green-400">
          Scores saved — next analysis run will use these values.
        </p>
      )}

      <div className="flex justify-between">
        <Button variant="ghost" size="sm" onClick={handleReset} disabled={saving} className="gap-1.5 text-xs">
          <RotateCcw className="h-3.5 w-3.5" />
          Reset to defaults
        </Button>
        <Button size="sm" onClick={handleSave} disabled={saving} className="gap-1.5">
          <Save className="h-3.5 w-3.5" />
          {saving ? "Saving…" : "Save scores"}
        </Button>
      </div>
    </div>
  )
}
