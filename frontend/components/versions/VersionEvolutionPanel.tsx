"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { AlertCircle, Loader2, Plus, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { QualityScoresPanel } from "@/components/analyze/QualityScoresPanel"
import { api, type VersionTree as VersionTreeData, type VersionTreeNode } from "@/lib/api"
import { VersionTree } from "./VersionTree"
import { QaLeaderboard } from "./QaLeaderboard"
import { DeployCommandButton } from "./DeployCommandButton"
import { operationLabel, pathToRoot } from "./qa"

interface Props {
  onCreateIteration: (versionId: string) => void
  /** Bump to refetch the tree (wired to the page's Refresh button). */
  refreshKey?: number
}

/** Detail strip for the selected node: its operation, the path that produced it, and QA scores. */
function SelectedVersionDetail({
  node, nodes, onCreateIteration, onClose,
}: {
  node: VersionTreeNode
  nodes: VersionTreeNode[]
  onCreateIteration: (versionId: string) => void
  onClose: () => void
}) {
  // Root-first so the trajectory reads in the order the operations were applied.
  const path = useMemo(() => {
    const ids = pathToRoot(nodes, node.versionId)
    return nodes.filter((n) => ids.has(n.versionId)).sort((a, b) => a.versionNumber - b.versionNumber)
  }, [nodes, node.versionId])

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs font-bold text-foreground">
              {node.versionLabel}
            </span>
            <span className="text-sm font-semibold text-foreground">
              {operationLabel(node) ?? "Original codebase"}
            </span>
          </div>

          {/* Evolution path — the sequence of operations that produced this version */}
          <div className="mt-2 flex flex-wrap items-center gap-1 text-[11px]">
            {path.map((step, i) => (
              <span key={step.versionId} className="flex items-center gap-1">
                {i > 0 && <span className="text-muted-foreground">→</span>}
                <span
                  className="rounded border border-border bg-muted/40 px-1.5 py-0.5 font-mono text-muted-foreground"
                  title={operationLabel(step) ?? "baseline"}
                >
                  {step.versionLabel}
                  {step.operation ? ` ${step.operation}` : ""}
                </span>
              </span>
            ))}
          </div>
        </div>

        <div className="flex flex-shrink-0 items-center gap-1.5">
          <DeployCommandButton versionLabel={node.versionLabel} deployCommand={node.deployCommand} />
          <Button size="sm" className="h-7 gap-1 px-2 text-xs" onClick={() => onCreateIteration(node.versionId)}>
            <Plus className="h-3 w-3" />
            New Iteration
          </Button>
          <button className="text-muted-foreground hover:text-foreground" onClick={onClose} aria-label="Close detail">
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="mt-4">
        {node.snapshotId ? (
          // Full scores (with signals + formula) are fetched from the snapshot, so the
          // details modal has everything the Analyze view shows.
          <QualityScoresPanel snapshotId={node.snapshotId} />
        ) : (
          <p className="rounded border border-dashed border-border px-3 py-4 text-center text-xs text-muted-foreground">
            This version has no monitor snapshot, so it has no quality scores. Create an iteration
            and run the Monitor phase to measure it.
          </p>
        )}
      </div>
    </div>
  )
}

export function VersionEvolutionPanel({ onCreateIteration, refreshKey }: Props) {
  const [tree, setTree] = useState<VersionTreeData | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedQa, setSelectedQa] = useState<string>("PERFORMANCE")
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setTree(await api.knowledge.versions.tree())
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to load version tree")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load, refreshKey])

  const nodes = tree?.nodes ?? []
  const selectedNode = nodes.find((n) => n.versionId === selectedVersionId) ?? null
  const highlightedPath = useMemo(
    () => pathToRoot(nodes, selectedVersionId),
    [nodes, selectedVersionId],
  )

  if (loading && !tree) {
    return (
      <div className="flex items-center gap-3 text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm">Loading evolution tree…</span>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
        <AlertCircle className="h-4 w-4 flex-shrink-0" />
        {error}
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {selectedNode && (
        <SelectedVersionDetail
          node={selectedNode}
          nodes={nodes}
          onCreateIteration={onCreateIteration}
          onClose={() => setSelectedVersionId(null)}
        />
      )}

      <div className="flex flex-col gap-5 lg:flex-row">
        <aside className="w-full flex-shrink-0 lg:w-72">
          <QaLeaderboard
            nodes={nodes}
            bestByAttribute={tree?.bestByAttribute ?? {}}
            selectedQa={selectedQa}
            selectedVersionId={selectedVersionId}
            onSelectQa={setSelectedQa}
            onSelectVersion={setSelectedVersionId}
          />
        </aside>

        <div className="min-w-0 flex-1">
          <VersionTree
            nodes={nodes}
            selectedQa={selectedQa}
            selectedVersionId={selectedVersionId}
            bestVersionId={tree?.bestByAttribute?.[selectedQa]}
            highlightedPath={highlightedPath}
            onSelect={setSelectedVersionId}
          />
        </div>
      </div>
    </div>
  )
}
