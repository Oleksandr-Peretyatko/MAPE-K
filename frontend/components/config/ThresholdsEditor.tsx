"use client"

import { useState, useEffect, useCallback } from "react"
import { Settings2, RotateCcw, Save } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { api, type HotspotThresholds } from "@/lib/api"

const DEFAULTS: HotspotThresholds = {
  highLatencyP95Ms:    1000,
  highSagaLatencyMs:   2000,
  highCrossSvcCalls:   3,
  highCompensationRate: 0.10,
  highErrorRate:       0.05,
  readHeavyRatio:      5,
  highCouplingM2:      0.30,
  lowCohesionM1:       0.05,
  highEntityCount:     5,
  highExternalRels:    2,
}

interface FieldDef {
  key: keyof HotspotThresholds
  label: string
  step: number
  min: number
  max?: number
  description: string
}

const RUNTIME_FIELDS: FieldDef[] = [
  { key: "highLatencyP95Ms",    label: "Service p95 latency (ms)",   step: 50,   min: 0, description: "HIGH_LATENCY — flag service if p95 > this" },
  { key: "highSagaLatencyMs",   label: "SAGA p95 latency (ms)",      step: 100,  min: 0, description: "HIGH_LATENCY — flag SAGA workflow if p95 > this" },
  { key: "highCrossSvcCalls",   label: "Cross-service calls / SAGA", step: 1,    min: 0, description: "Avg cross-svc calls per SAGA execution" },
  { key: "highCompensationRate",label: "Compensation rate (0–1)",     step: 0.01, min: 0, max: 1, description: "HIGH_COMPENSATION — flag SAGA if rate > this" },
  { key: "highErrorRate",       label: "Error rate (0–1)",            step: 0.01, min: 0, max: 1, description: "HIGH_ERROR_RATE — flag service if error rate > this" },
  { key: "readHeavyRatio",      label: "Read/write ratio",           step: 0.5,  min: 0, description: "READ_HEAVY — flag service if read÷write > this" },
]

const STRUCTURAL_FIELDS: FieldDef[] = [
  { key: "highCouplingM2",  label: "System M2 threshold",          step: 0.01, min: 0, max: 1, description: "TIGHT_COUPLING — system coupling above which pairs are over-coupled" },
  { key: "lowCohesionM1",   label: "Per-service M1 min",           step: 0.01, min: 0, max: 1, description: "LOW_COHESION — service M1 contribution below this is flagged" },
  { key: "highEntityCount", label: "Entity count threshold",        step: 1,    min: 0, description: "HIGH_ENTITY_COUNT — flag service if entity count > this" },
  { key: "highExternalRels",label: "External relations threshold",  step: 1,    min: 0, description: "TIGHT_COUPLING — flag pair if total external relations > this" },
]

interface FieldRowProps {
  def: FieldDef
  value: number
  onChange: (key: keyof HotspotThresholds, val: number) => void
}

function FieldRow({ def, value, onChange }: FieldRowProps) {
  return (
    <div className="grid grid-cols-[1fr_auto] gap-2 items-start py-2 border-b border-border/50 last:border-0">
      <div>
        <p className="text-xs font-medium text-foreground">{def.label}</p>
        <p className="text-[10px] text-muted-foreground mt-0.5">{def.description}</p>
      </div>
      <input
        type="number"
        step={def.step}
        min={def.min}
        max={def.max}
        value={value}
        onChange={(e) => onChange(def.key, Number(e.target.value))}
        className="h-8 w-28 rounded-md border border-input bg-background px-2 text-xs text-right focus:outline-none focus:ring-1 focus:ring-ring"
      />
    </div>
  )
}

interface ThresholdsEditorProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function ThresholdsEditor({ open, onOpenChange }: ThresholdsEditorProps) {
  const [values, setValues] = useState<HotspotThresholds>(DEFAULTS)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState("")
  const [saved, setSaved] = useState(false)

  const load = useCallback(async () => {
    try {
      const t = await api.config.heuristics.get()
      setValues(t)
    } catch {
      // silent — defaults remain
    }
  }, [])

  useEffect(() => {
    if (open) {
      load()
      setError("")
      setSaved(false)
    }
  }, [open, load])

  function handleChange(key: keyof HotspotThresholds, val: number) {
    setValues((prev) => ({ ...prev, [key]: val }))
    setSaved(false)
  }

  async function handleSave() {
    setSaving(true)
    setError("")
    setSaved(false)
    try {
      const updated = await api.config.heuristics.update(values)
      setValues(updated)
      setSaved(true)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to save thresholds")
    } finally {
      setSaving(false)
    }
  }

  function handleReset() {
    setValues(DEFAULTS)
    setSaved(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base">
            <Settings2 className="h-4 w-4" />
            Hotspot Detection Thresholds
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-5 mt-2">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground mb-1">
              Runtime
            </p>
            {RUNTIME_FIELDS.map((f) => (
              <FieldRow key={f.key} def={f} value={values[f.key]} onChange={handleChange} />
            ))}
          </div>

          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground mb-1">
              Structural
            </p>
            {STRUCTURAL_FIELDS.map((f) => (
              <FieldRow key={f.key} def={f} value={values[f.key]} onChange={handleChange} />
            ))}
          </div>

          {error && (
            <p className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
              {error}
            </p>
          )}
          {saved && (
            <p className="rounded-md border border-green-500/30 bg-green-500/5 px-3 py-2 text-xs text-green-600 dark:text-green-400">
              Thresholds saved — next analysis run will use these values.
            </p>
          )}

          <div className="flex justify-between pt-1">
            <Button variant="ghost" size="sm" onClick={handleReset} className="gap-1.5 text-xs">
              <RotateCcw className="h-3.5 w-3.5" />
              Reset to defaults
            </Button>
            <Button size="sm" onClick={handleSave} disabled={saving} className="gap-1.5">
              <Save className="h-3.5 w-3.5" />
              {saving ? "Saving…" : "Save thresholds"}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
