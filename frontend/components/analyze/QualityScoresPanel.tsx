"use client"

import { useState, useEffect } from "react"
import { AlertTriangle } from "lucide-react"
import { api, type QualityAttributeScore, type QualityScoreResult, type QaStatus, type Hotspot } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import { QualityFormulaTooltip } from "./QualityFormulaTooltip"
import { QualityScoreDetailsModal } from "./QualityScoreDetailsModal"
import { QualityScoreDeltaBadge } from "./QualityScoreDeltaBadge"

interface Props {
  snapshotId: string
  previousScores?: QualityScoreResult
  /** Pre-loaded scores from AnalysisResult — skips the API call when provided. */
  qualityScores?: QualityAttributeScore[]
  /** Hotspots for count badges on each QA card. */
  hotspots?: Hotspot[]
}

function statusVariant(status: QaStatus) {
  switch (status) {
    case "excellent": return "green" as const
    case "good":      return "success" as const
    case "warning":   return "warning" as const
    case "poor":      return "destructive" as const
  }
}

function progressColor(status: QaStatus): string {
  switch (status) {
    case "excellent": return "[&>div]:bg-green-500"
    case "good":      return "[&>div]:bg-emerald-500"
    case "warning":   return "[&>div]:bg-amber-500"
    case "poor":      return "[&>div]:bg-destructive"
  }
}

function attrLabel(attr: string): string {
  return attr.replace(/_/g, " ").toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase())
}

function ScoreCard({
  score,
  previousScore,
  hotspotCount,
  onClick,
}: {
  score: QualityAttributeScore
  previousScore?: QualityAttributeScore
  hotspotCount: number
  onClick: () => void
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") onClick() }}
      className="w-full text-left bg-card border rounded-lg p-4 hover:bg-muted/40 transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <div className="flex items-start justify-between mb-2">
        <div className="flex items-center gap-1">
          <span className="text-sm font-medium">{attrLabel(score.attribute)}</span>
          <QualityFormulaTooltip formula={score.formula} />
        </div>
        <div className="flex items-center gap-2">
          {hotspotCount > 0 && (
            <Badge variant="outline" className="text-[10px] gap-0.5 text-amber-600 border-amber-400">
              <AlertTriangle className="h-2.5 w-2.5" />{hotspotCount}
            </Badge>
          )}
          {previousScore && (
            <QualityScoreDeltaBadge current={score.score} previous={previousScore.score} />
          )}
          <Badge variant={statusVariant(score.status)} className="shrink-0">
            {score.status}
          </Badge>
        </div>
      </div>

      <div className="flex items-end justify-between mb-2">
        <span className="text-2xl font-bold tabular-nums">{score.percentage}%</span>
        {score.hasEstimatedData && (
          <span className="text-xs text-amber-500" title="Some signals were estimated">⚠ est.</span>
        )}
      </div>

      <Progress value={score.percentage} className={`h-1.5 ${progressColor(score.status)}`} />

      {score.topDriver && (
        <p className="mt-2 text-xs text-muted-foreground line-clamp-2">{score.topDriver}</p>
      )}
    </div>
  )
}

export function QualityScoresPanel({ snapshotId, previousScores, qualityScores, hotspots }: Props) {
  const [result, setResult] = useState<QualityScoreResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedScore, setSelectedScore] = useState<QualityAttributeScore | null>(null)

  useEffect(() => {
    if (qualityScores) {
      setResult({ snapshotId, version: "current", generatedAt: "", scores: qualityScores })
      setLoading(false)
      return
    }
    if (!snapshotId) return
    setLoading(true)
    setError(null)
    api.quality.getScores(snapshotId)
      .then(setResult)
      .catch(() => setError("Failed to load quality scores."))
      .finally(() => setLoading(false))
  }, [snapshotId, qualityScores])

  if (loading) {
    return (
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
        {Array.from({ length: 7 }).map((_, i) => (
          <div key={i} className="bg-card border rounded-lg p-4 animate-pulse h-28" />
        ))}
      </div>
    )
  }

  if (error || !result) {
    return (
      <div className="text-sm text-muted-foreground p-4 text-center border rounded-lg">
        {error ?? "No quality scores available for this snapshot."}
      </div>
    )
  }

  return (
    <>
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
        {result.scores.map((score) => {
          const prev = previousScores?.scores.find((s) => s.attribute === score.attribute)
          const count = hotspots?.filter((h) => h.qualityAttribute === score.attribute).length ?? 0
          return (
            <ScoreCard
              key={score.attribute}
              score={score}
              previousScore={prev}
              hotspotCount={count}
              onClick={() => setSelectedScore(score)}
            />
          )
        })}
      </div>

      <QualityScoreDetailsModal
        score={selectedScore}
        onClose={() => setSelectedScore(null)}
      />
    </>
  )
}
