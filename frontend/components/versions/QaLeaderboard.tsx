"use client"

import { Star } from "lucide-react"
import type { VersionTreeNode } from "@/lib/api"
import { QA_ATTRIBUTES, isMeasured, operationLabel, qaLabel, rankedBy, statusText } from "./qa"
import { cn } from "@/lib/utils"

interface Props {
  nodes: VersionTreeNode[]
  bestByAttribute: Record<string, string>
  selectedQa: string
  selectedVersionId: string | null
  onSelectQa: (attribute: string) => void
  onSelectVersion: (versionId: string) => void
}

export function QaLeaderboard({
  nodes, bestByAttribute, selectedQa, selectedVersionId, onSelectQa, onSelectVersion,
}: Props) {
  const byId = new Map(nodes.map((n) => [n.versionId, n]))
  const measuredCount = nodes.filter(isMeasured).length
  const ranking = rankedBy(nodes, selectedQa)

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-sm font-semibold text-foreground">Best version by quality attribute</h2>
        <p className="mt-0.5 text-xs text-muted-foreground">
          Pick an attribute to rank versions and highlight the winning path in the tree
        </p>
      </div>

      {measuredCount === 0 ? (
        <div className="rounded-lg border border-dashed border-border px-3 py-6 text-center text-xs text-muted-foreground">
          No version has a monitor snapshot yet, so nothing can be ranked. Run the Monitor phase on
          an iteration to score its version.
        </div>
      ) : (
        <>
          {/* Attribute selector — each row shows its current winner */}
          <div className="space-y-1">
            {QA_ATTRIBUTES.map((attr) => {
              const winner = byId.get(bestByAttribute[attr])
              const active = attr === selectedQa
              return (
                <button
                  key={attr}
                  onClick={() => onSelectQa(attr)}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-md border px-2.5 py-1.5 text-left transition-colors",
                    active ? "border-primary bg-primary/10" : "border-transparent hover:bg-muted/50",
                  )}
                >
                  <span className={cn("flex-1 truncate text-xs", active ? "font-semibold text-foreground" : "text-muted-foreground")}>
                    {qaLabel(attr)}
                  </span>
                  {winner ? (
                    <>
                      <span className="rounded bg-muted px-1 py-0.5 font-mono text-[10px] text-foreground">
                        {winner.versionLabel}
                      </span>
                      <span className={cn("w-9 text-right text-xs font-bold tabular-nums",
                        statusText(winner.scores[attr]?.status))}>
                        {winner.scores[attr]?.percentage}%
                      </span>
                    </>
                  ) : (
                    <span className="text-[10px] italic text-muted-foreground">no data</span>
                  )}
                </button>
              )
            })}
          </div>

          {/* Full ranking for the selected attribute */}
          <div>
            <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {qaLabel(selectedQa)} ranking
            </h3>
            <ol className="space-y-1">
              {ranking.map((node, index) => {
                const entry = node.scores[selectedQa]
                const operation = operationLabel(node)
                return (
                  <li key={node.versionId}>
                    <button
                      onClick={() => onSelectVersion(node.versionId)}
                      className={cn(
                        "flex w-full items-center gap-2 rounded-md border px-2 py-1.5 text-left transition-colors",
                        node.versionId === selectedVersionId
                          ? "border-primary bg-primary/10"
                          : "border-transparent hover:bg-muted/50",
                      )}
                    >
                      <span className="w-4 flex-shrink-0 text-center text-[10px] tabular-nums text-muted-foreground">
                        {index === 0 ? <Star className="mx-auto h-3 w-3 fill-current text-amber-500" /> : index + 1}
                      </span>
                      <span className="flex-shrink-0 rounded bg-muted px-1 py-0.5 font-mono text-[10px] text-foreground">
                        {node.versionLabel}
                      </span>
                      <span className="min-w-0 flex-1 truncate font-mono text-[10px] text-muted-foreground"
                            title={operation ?? undefined}>
                        {operation ?? "baseline"}
                      </span>
                      <span className={cn("w-9 text-right text-xs font-bold tabular-nums", statusText(entry.status))}>
                        {entry.percentage}%
                      </span>
                    </button>
                  </li>
                )
              })}
            </ol>
          </div>
        </>
      )}
    </div>
  )
}
