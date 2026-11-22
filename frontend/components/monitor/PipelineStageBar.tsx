"use client"

import { Check, X, Loader2, Clock } from "lucide-react"
import { cn } from "@/lib/utils"

export type StageStatus = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "STOPPED"

export interface PipelineStage {
  id: string
  label: string
  status: StageStatus
}

interface PipelineStageBarProps {
  stages: PipelineStage[]
}

function StageIcon({ status }: { status: StageStatus }) {
  if (status === "RUNNING") return <Loader2 className="h-4 w-4 animate-spin text-blue-500" />
  if (status === "DONE")    return <Check className="h-4 w-4 text-green-500" />
  if (status === "FAILED")  return <X className="h-4 w-4 text-red-500" />
  if (status === "STOPPED") return <X className="h-4 w-4 text-yellow-500" />
  return <Clock className="h-4 w-4 text-muted-foreground" />
}

function stageDotClass(status: StageStatus) {
  if (status === "RUNNING") return "border-blue-500 bg-blue-500/10"
  if (status === "DONE")    return "border-green-500 bg-green-500/10"
  if (status === "FAILED")  return "border-red-500 bg-red-500/10"
  if (status === "STOPPED") return "border-yellow-500 bg-yellow-500/10"
  return "border-border bg-muted/30"
}

function stageLabelClass(status: StageStatus) {
  if (status === "RUNNING") return "text-blue-500 font-medium"
  if (status === "DONE")    return "text-green-600 dark:text-green-400 font-medium"
  if (status === "FAILED")  return "text-red-500 font-medium"
  if (status === "STOPPED") return "text-yellow-600 dark:text-yellow-400 font-medium"
  return "text-muted-foreground"
}

function connectorClass(leftStatus: StageStatus) {
  if (leftStatus === "DONE") return "bg-green-500/40"
  if (leftStatus === "RUNNING") return "bg-blue-500/30"
  return "bg-border"
}

export function PipelineStageBar({ stages }: PipelineStageBarProps) {
  return (
    <div className="flex items-center gap-0">
      {stages.map((stage, i) => (
        <div key={stage.id} className="flex items-center">
          <div className={cn(
            "flex items-center gap-2 rounded-lg border px-3 py-2 text-xs transition-colors",
            stageDotClass(stage.status),
          )}>
            <StageIcon status={stage.status} />
            <span className={cn("transition-colors", stageLabelClass(stage.status))}>
              {stage.label}
            </span>
          </div>
          {i < stages.length - 1 && (
            <div className={cn("h-px w-6 transition-colors", connectorClass(stage.status))} />
          )}
        </div>
      ))}
    </div>
  )
}
