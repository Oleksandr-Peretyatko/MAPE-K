"use client"

import { GitBranch, GitCommit, Star } from "lucide-react"
import type { VersionTreeNode } from "@/lib/api"
import { QualityScoreDeltaBadge } from "@/components/analyze/QualityScoreDeltaBadge"
import { QA_ATTRIBUTES, QA_SHORT, isMeasured, operationLabel, statusBg, statusText } from "./qa"
import { cn } from "@/lib/utils"

interface Props {
  nodes: VersionTreeNode[]
  selectedQa: string
  selectedVersionId: string | null
  bestVersionId?: string
  /** Version ids on the path from the selected node to the root. */
  highlightedPath: Set<string>
  onSelect: (versionId: string) => void
}

function versionStatusColor(status: string): string {
  if (status === "BASELINE")   return "bg-blue-500/20 text-blue-600"
  if (status === "ACTIVE")     return "bg-green-500/20 text-green-600"
  if (status === "FAILED")     return "bg-red-500/20 text-red-600"
  return "bg-muted/60 text-muted-foreground"
}

function NodeCard({
  node, selectedQa, isSelected, isBest, isOnPath, onSelect,
}: {
  node: VersionTreeNode
  selectedQa: string
  isSelected: boolean
  isBest: boolean
  isOnPath: boolean
  onSelect: (versionId: string) => void
}) {
  const measured = isMeasured(node)
  const operation = operationLabel(node)
  const selectedScore = node.scores?.[selectedQa]
  const selectedDelta = node.deltas?.[selectedQa]

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onSelect(node.versionId)}
      onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onSelect(node.versionId) } }}
      className={cn(
        "w-[22rem] cursor-pointer rounded-lg border bg-card p-3 text-left transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        isSelected  ? "border-primary ring-primary/40"
        : isOnPath  ? "border-primary/50 bg-primary/5"
        : "hover:bg-muted/40",
      )}
    >
      {/* Identity row */}
      <div className="flex items-center gap-2">
        <span className={cn("flex h-7 w-20 flex-shrink-0 items-center justify-center rounded text-xs font-bold",
          versionStatusColor(node.status))}>
          {node.versionLabel}
        </span>
        {isBest && (
          <span className="flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-500/15 px-1.5 py-0.5 text-[9px] font-bold uppercase text-amber-600">
            <Star className="h-2.5 w-2.5 fill-current" /> best
          </span>
        )}
        <span className="ml-auto text-[10px] uppercase tracking-wide text-muted-foreground">{node.status}</span>
      </div>

      {/* Operation that produced this version */}
      {operation ? (
        <p className="mt-2 truncate font-mono text-[11px] text-foreground" title={operation}>{operation}</p>
      ) : (
        <p className="mt-2 text-[11px] italic text-muted-foreground">original codebase</p>
      )}

      <div className="mt-1 flex flex-wrap gap-x-3 text-[10px] text-muted-foreground">
        {node.branchName && (
          <span className="flex min-w-0 items-center gap-1">
            <GitBranch className="h-2.5 w-2.5 flex-shrink-0" />
            <span className="truncate font-mono">{node.branchName}</span>
          </span>
        )}
        {node.gitCommitSha && (
          <span className="flex items-center gap-1">
            <GitCommit className="h-2.5 w-2.5" />
            <span className="font-mono">{node.gitCommitSha.slice(0, 7)}</span>
          </span>
        )}
      </div>

      {/* Scores */}
      {!measured ? (
        <p className="mt-2 rounded border border-dashed border-border px-2 py-1 text-[10px] italic text-muted-foreground">
          not measured — no monitor snapshot on this version
        </p>
      ) : (
        <>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-[10px] uppercase text-muted-foreground">{QA_SHORT[selectedQa] ?? selectedQa}</span>
            {selectedScore ? (
              <>
                <span className={cn("text-lg font-bold leading-none", statusText(selectedScore.status))}>
                  {selectedScore.percentage}%
                </span>
                {selectedDelta !== undefined && (
                  <QualityScoreDeltaBadge
                    current={selectedScore.score}
                    previous={selectedScore.score - selectedDelta}
                  />
                )}
              </>
            ) : (
              <span className="text-sm text-muted-foreground">—</span>
            )}
          </div>

          <div className="mt-2 flex flex-wrap gap-1">
            {QA_ATTRIBUTES.map((attr) => {
              const entry = node.scores[attr]
              if (!entry) return null
              const active = attr === selectedQa
              return (
                <span
                  key={attr}
                  title={`${attr}: ${entry.percentage}% (${entry.status})`}
                  className={cn(
                    "rounded border px-1 py-0.5 text-[9px] font-medium tabular-nums transition-opacity",
                    statusBg(entry.status),
                    active ? "ring-1 ring-primary/50" : "opacity-60",
                  )}
                >
                  {QA_SHORT[attr]} {entry.percentage}
                </span>
              )
            })}
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Renders one subtree: the node card, then its children indented beneath it with
 * CSS-drawn connectors (a vertical spine plus an elbow per child).
 */
function Subtree({
  node, childMap, depth, ...rest
}: Props & { node: VersionTreeNode; childMap: Map<string, VersionTreeNode[]>; depth: number }) {
  const children = childMap.get(node.versionId) ?? []
  const { nodes, selectedQa, selectedVersionId, bestVersionId, highlightedPath, onSelect } = rest

  return (
    <li
      className={cn(
        "relative",
        // Connectors: a vertical spine down the left edge plus a horizontal elbow into the
        // card. On the last sibling the spine stops at the elbow so it does not overshoot.
        depth > 0 && [
          "pl-8",
          "before:absolute before:bottom-0 before:left-0 before:top-0 before:border-l before:border-border before:content-['']",
          "last:before:bottom-auto last:before:h-6",
          "after:absolute after:left-0 after:top-6 after:w-8 after:border-t after:border-border after:content-['']",
        ],
      )}
    >
      <NodeCard
        node={node}
        selectedQa={selectedQa}
        isSelected={node.versionId === selectedVersionId}
        isBest={node.versionId === bestVersionId}
        isOnPath={highlightedPath.has(node.versionId)}
        onSelect={onSelect}
      />

      {children.length > 0 && (
        <ul className="mt-3 space-y-3">
          {children.map((child) => (
            <Subtree
              key={child.versionId}
              node={child}
              childMap={childMap}
              depth={depth + 1}
              nodes={nodes}
              selectedQa={selectedQa}
              selectedVersionId={selectedVersionId}
              bestVersionId={bestVersionId}
              highlightedPath={highlightedPath}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

export function VersionTree(props: Props) {
  const { nodes } = props

  // Group children by parent. A node whose parent is missing is treated as a root so
  // nothing is silently dropped from the view.
  const ids = new Set(nodes.map((n) => n.versionId))
  const childMap = new Map<string, VersionTreeNode[]>()
  const roots: VersionTreeNode[] = []

  for (const node of [...nodes].sort((a, b) => a.versionNumber - b.versionNumber)) {
    const parentId = node.parentVersionId
    if (parentId && ids.has(parentId)) {
      const siblings = childMap.get(parentId) ?? []
      siblings.push(node)
      childMap.set(parentId, siblings)
    } else {
      roots.push(node)
    }
  }

  if (nodes.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border py-12 text-center text-sm text-muted-foreground">
        No versions yet. Run the MAPE-K loop to produce one.
      </div>
    )
  }

  return (
    <div className="overflow-x-auto">
      <ul className="space-y-3">
        {roots.map((root) => (
          <Subtree key={root.versionId} {...props} node={root} childMap={childMap} depth={0} />
        ))}
      </ul>
    </div>
  )
}
