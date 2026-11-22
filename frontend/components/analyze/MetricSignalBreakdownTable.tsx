"use client"

import type { ReactNode } from "react"
import type { MetricSignal } from "@/lib/api"

interface Props {
  signals: MetricSignal[]
}

function fmtRaw(v: number | null, direction: string): string {
  if (v === null || v === undefined) return "—"
  if (direction === "lower_is_better" && v > 999) return `${(v / 1000).toFixed(1)}k`
  if (v < 0.01) return v.toExponential(2)
  if (v < 1) return v.toFixed(3)
  if (v < 1000) return v.toFixed(1)
  return `${(v / 1000).toFixed(1)}k`
}

function statusBadge(signal: MetricSignal): ReactNode {
  if (signal.estimated) {
    return <span className="text-xs text-amber-600 dark:text-amber-400 font-medium">estimated</span>
  }
  if (signal.normalizedValue === null) {
    return <span className="text-xs text-muted-foreground">missing</span>
  }
  return <span className="text-xs text-muted-foreground">measured</span>
}

function contributionBar(contribution: number): ReactNode {
  const pct = Math.round(Math.min(contribution / 0.3, 1) * 100) // scale to max ~30% contribution
  return (
    <div className="flex items-center gap-1">
      <div className="w-12 h-1.5 bg-muted rounded-full overflow-hidden">
        <div className="h-full bg-primary rounded-full" style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs tabular-nums">{(contribution * 100).toFixed(1)}%</span>
    </div>
  )
}

export function MetricSignalBreakdownTable({ signals }: Props) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th className="pb-2 font-medium">Signal</th>
            <th className="pb-2 font-medium">Source</th>
            <th className="pb-2 font-medium text-right">Raw</th>
            <th className="pb-2 font-medium text-right">Norm.</th>
            <th className="pb-2 font-medium text-right">Weight</th>
            <th className="pb-2 font-medium">Contribution</th>
            <th className="pb-2 font-medium">Status</th>
          </tr>
        </thead>
        <tbody>
          {signals.map((s) => (
            <tr
              key={s.key}
              className={`border-b last:border-0 ${s.normalizedValue === null ? "opacity-50" : ""}`}
            >
              <td className="py-2 pr-3">
                <p className="text-xs font-medium text-foreground">{s.label}</p>
                <p className="text-xs text-muted-foreground leading-tight">{s.description}</p>
              </td>
              <td className="py-2 pr-3">
                <span className="text-xs font-mono px-1 py-0.5 bg-muted rounded">{s.source}</span>
              </td>
              <td className="py-2 pr-3 text-right font-mono text-xs tabular-nums">
                {fmtRaw(s.rawValue, s.direction)}
              </td>
              <td className="py-2 pr-3 text-right font-mono text-xs tabular-nums">
                {s.normalizedValue !== null ? (s.normalizedValue * 100).toFixed(0) + "%" : "—"}
              </td>
              <td className="py-2 pr-3 text-right text-xs tabular-nums">
                {(s.weight * 100).toFixed(0)}%
              </td>
              <td className="py-2 pr-3">{contributionBar(s.contribution)}</td>
              <td className="py-2">{statusBadge(s)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
