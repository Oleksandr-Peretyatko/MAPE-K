"use client"

import { useState } from "react"
import { TrendingUp, TrendingDown, Minus, ChevronDown, ChevronRight } from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { HotspotsGrid } from "@/components/analyze/Hotspots"
import { QAMetricsPanel } from "@/components/analyze/QAMetricsPanel"
import { QualityScoresPanel } from "@/components/analyze/QualityScoresPanel"
import { SimpleRankCandidateList } from "@/components/analyze/SimpleRankCandidateList"
import type { AnalysisResult, CandidateOperation } from "@/lib/api"

const COLLAPSIBLE_TRIGGER_CLASS =
  "flex w-full items-center justify-between rounded-lg border border-border bg-card px-4 py-3 text-sm font-medium hover:bg-accent transition-colors"

interface Props {
  result: AnalysisResult
  priorities: string[]
  onSelectForPlan?: (c: CandidateOperation) => void
}

function TrendBadge({ trend }: { trend: string }) {
  if (trend === "IMPROVING") return <Badge variant="success" className="gap-1"><TrendingUp className="h-3 w-3" />Improving</Badge>
  if (trend === "DEGRADING") return <Badge variant="destructive" className="gap-1"><TrendingDown className="h-3 w-3" />Degrading</Badge>
  if (trend === "STABLE")    return <Badge variant="secondary" className="gap-1"><Minus className="h-3 w-3" />Stable</Badge>
  return <Badge variant="outline">Insufficient Data</Badge>
}

export function SimpleRankAnalyzeView({ result, priorities, onSelectForPlan }: Props) {
  const [crossOpen,    setCrossOpen]    = useState(false)
  const [hotspotsOpen, setHotspotsOpen] = useState(false)
  const [metricsOpen,  setMetricsOpen]  = useState(false)

  const criticalCount = result.hotspots?.filter((h) => h.severity > 0.7).length ?? 0
  const warnCount     = result.hotspots?.filter((h) => h.severity >= 0.4 && h.severity <= 0.7).length ?? 0
  const infoCount     = result.hotspots?.filter((h) => h.severity < 0.4).length ?? 0

  return (
    <div className="space-y-5">
      {/* Hotspots — collapsible, default closed */}
      <Collapsible open={hotspotsOpen} onOpenChange={setHotspotsOpen}>
        <CollapsibleTrigger asChild>
          <button className={COLLAPSIBLE_TRIGGER_CLASS}>
            <div className="flex items-center gap-2">
              Hotspots
              {criticalCount > 0 && <Badge variant="destructive" className="text-[10px]">{criticalCount} critical</Badge>}
              {warnCount     > 0 && <Badge variant="warning"     className="text-[10px]">{warnCount} warning</Badge>}
              {infoCount     > 0 && <Badge variant="outline"     className="text-[10px]">{infoCount} info</Badge>}
            </div>
            {hotspotsOpen
              ? <ChevronDown  className="h-4 w-4 text-muted-foreground" />
              : <ChevronRight className="h-4 w-4 text-muted-foreground" />}
          </button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <Card className="mt-1 rounded-t-none border-t-0">
            <CardContent className="pt-4">
              <HotspotsGrid hotspots={result.hotspots} />
            </CardContent>
          </Card>
        </CollapsibleContent>
      </Collapsible>

      {/* Evidence metrics (structural + runtime) — collapsible, default closed */}
      {(result.structuralEvidence || result.runtimeEvidence) && (
        <Collapsible open={metricsOpen} onOpenChange={setMetricsOpen}>
          <CollapsibleTrigger asChild>
            <button className={COLLAPSIBLE_TRIGGER_CLASS}>
              Metrics by Quality Attribute
              {metricsOpen
                ? <ChevronDown  className="h-4 w-4 text-muted-foreground" />
                : <ChevronRight className="h-4 w-4 text-muted-foreground" />}
            </button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <Card className="mt-1 rounded-t-none border-t-0">
              <CardContent className="pt-4">
                <QAMetricsPanel result={result} />
              </CardContent>
            </Card>
          </CollapsibleContent>
        </Collapsible>
      )}

      {/* Quality attribute scores */}
      {result.snapshotId && (
        <Card>
          <CardHeader>
            <CardTitle>Quality Attribute Scores</CardTitle>
          </CardHeader>
          <CardContent>
            <QualityScoresPanel
              snapshotId={result.snapshotId}
              qualityScores={result.qualityScores}
              hotspots={result.hotspots}
            />
          </CardContent>
        </Card>
      )}

      {/* Ranked candidate list */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Candidate Operations
            <span className="text-[10px] font-mono rounded border border-border bg-muted/30 px-1.5 py-0.5 text-muted-foreground">
              SIMPLE_RANK
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <SimpleRankCandidateList
            candidates={result.candidateOperations}
            priorities={priorities}
            onSelectForPlan={onSelectForPlan}
          />
        </CardContent>
      </Card>

      {/* Cross-version clues */}
      {result.crossVersionClues && (
        <Collapsible open={crossOpen} onOpenChange={setCrossOpen}>
          <CollapsibleTrigger asChild>
            <button className="flex w-full items-center justify-between rounded-lg border border-border bg-card px-4 py-3 text-sm font-medium hover:bg-accent transition-colors">
              Cross-Version Clues
              {crossOpen
                ? <ChevronDown className="h-4 w-4 text-muted-foreground" />
                : <ChevronRight className="h-4 w-4 text-muted-foreground" />}
            </button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <Card className="mt-1 rounded-t-none border-t-0">
              <CardContent className="pt-4 space-y-3">
                <div className="flex items-center gap-4 flex-wrap">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground">M1 Trend</span>
                    <TrendBadge trend={result.crossVersionClues.m1Trend} />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground">M2 Trend</span>
                    <TrendBadge trend={result.crossVersionClues.m2Trend} />
                  </div>
                </div>
                {result.crossVersionClues.notes.length > 0 && (
                  <ul className="space-y-1">
                    {result.crossVersionClues.notes.map((n, i) => (
                      <li key={i} className="text-xs text-muted-foreground">• {n}</li>
                    ))}
                  </ul>
                )}
              </CardContent>
            </Card>
          </CollapsibleContent>
        </Collapsible>
      )}

    </div>
  )
}
