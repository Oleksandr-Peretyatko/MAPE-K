"use client"

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Badge } from "@/components/ui/badge"
import { MetricSignalBreakdownTable } from "./MetricSignalBreakdownTable"
import type { QualityAttributeScore, QaStatus, MetricSignal } from "@/lib/api"

interface Props {
  score: QualityAttributeScore | null
  onClose: () => void
}

function statusVariant(status: QaStatus) {
  switch (status) {
    case "excellent": return "green" as const
    case "good":      return "success" as const
    case "warning":   return "warning" as const
    case "poor":      return "destructive" as const
  }
}

function fmtRaw(v: number | null | undefined, direction: string): string {
  if (v === null || v === undefined) return "—"
  if (direction === "lower_is_better" && v > 999) return `${(v / 1000).toFixed(1)}k`
  if (v < 0.01) return v.toExponential(2)
  if (v < 1) return v.toFixed(3)
  if (v < 1000) return v.toFixed(1)
  return `${(v / 1000).toFixed(1)}k`
}

function PerEntitySignalTable({
  perEntitySignals,
  globalSignals,
  entityType,
}: {
  perEntitySignals: Record<string, MetricSignal[]>
  globalSignals: MetricSignal[]
  entityType: string
}) {
  const signalKeys = globalSignals.map((s) => s.key)
  const signalMeta = Object.fromEntries(globalSignals.map((s) => [s.key, s]))
  const entities = Object.keys(perEntitySignals)

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="pb-2 pr-3 font-medium capitalize">{entityType}</th>
            {signalKeys.map((k) => (
              <th key={k} className="pb-2 pr-3 font-medium text-right whitespace-nowrap">
                {signalMeta[k]?.label ?? k}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {entities.map((entity) => {
            const rowSignals = Object.fromEntries(
              (perEntitySignals[entity] ?? []).map((s) => [s.key, s])
            )
            return (
              <tr key={entity} className="border-b last:border-0 hover:bg-muted/20">
                <td className="py-1.5 pr-3 font-mono font-medium max-w-[120px] truncate" title={entity}>
                  {entity}
                </td>
                {signalKeys.map((k) => {
                  const sig = rowSignals[k]
                  const norm = sig?.normalizedValue
                  const raw = sig?.rawValue
                  const dir = sig?.direction ?? signalMeta[k]?.direction ?? "lower_is_better"
                  const missing = raw === null || raw === undefined
                  return (
                    <td
                      key={k}
                      className={`py-1.5 pr-3 text-right tabular-nums ${
                        missing || sig?.estimated ? "text-amber-600 dark:text-amber-400" : ""
                      }`}
                      title={norm !== null && norm !== undefined ? `norm: ${(norm * 100).toFixed(0)}%` : undefined}
                    >
                      {fmtRaw(raw, dir)}
                      {norm !== null && norm !== undefined && !missing && (
                        <span className="ml-1 text-muted-foreground">
                          ({(norm * 100).toFixed(0)}%)
                        </span>
                      )}
                    </td>
                  )
                })}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function QualityScoreDetailsModal({ score, onClose }: Props) {
  return (
    <Dialog open={score !== null} onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-w-5xl w-[95vw] max-h-[85vh] overflow-y-auto">
        {score && (
          <>
            <DialogHeader>
              <div className="flex items-center gap-3">
                <DialogTitle className="text-lg font-semibold capitalize">
                  {score.attribute.replace(/_/g, " ").toLowerCase()}
                </DialogTitle>
                <Badge variant={statusVariant(score.status)}>
                  {score.status}
                </Badge>
                <span className="text-2xl font-bold tabular-nums ml-auto">{score.percentage}%</span>
              </div>
            </DialogHeader>

            {/* Formula block */}
            <div className="bg-muted/40 rounded-lg p-4 space-y-2">
              <p className="text-sm text-muted-foreground">{score.formula.shortDescription}</p>
              <code className="block text-xs font-mono bg-background/60 border rounded p-2 break-all">
                {score.formula.formula}
              </code>
              <p className="text-xs text-muted-foreground italic">{score.formula.interpretation}</p>
            </div>

            {/* Top driver */}
            {score.topDriver && (
              <p className="text-sm text-muted-foreground border-l-2 border-primary/40 pl-3">
                {score.topDriver}
              </p>
            )}

            {/* Estimated data warning */}
            {score.hasEstimatedData && (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                ⚠ Some signals were estimated due to missing metric data.
              </p>
            )}

            {/* Signal breakdown */}
            <div>
              <h4 className="text-sm font-medium mb-2">Signal Breakdown</h4>
              <MetricSignalBreakdownTable signals={score.signals ?? []} />
            </div>

            {/* Per-entity breakdown */}
            {score.perEntitySignals && Object.keys(score.perEntitySignals).length > 0 && (
              <div>
                <h4 className="text-sm font-medium mb-2">
                  Per-{score.perEntityType === "scenario" ? "Scenario" : "Service"} Breakdown
                </h4>
                <PerEntitySignalTable
                  perEntitySignals={score.perEntitySignals}
                  globalSignals={score.signals ?? []}
                  entityType={score.perEntityType ?? "service"}
                />
              </div>
            )}
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
