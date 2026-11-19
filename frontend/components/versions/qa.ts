import type { QaStatus, VersionTreeNode } from "@/lib/api"

/** The seven attributes produced by QualityAttributeCalculator, in display order. */
export const QA_ATTRIBUTES = [
  "PERFORMANCE",
  "CONSISTENCY",
  "MAINTAINABILITY",
  "COUPLING",
  "COHESION",
  "SCALABILITY",
  "AUTONOMY",
] as const

/** Short labels for the per-node chip row, where full names do not fit. */
export const QA_SHORT: Record<string, string> = {
  PERFORMANCE:     "PERF",
  CONSISTENCY:     "CONS",
  MAINTAINABILITY: "MAINT",
  COUPLING:        "COUP",
  COHESION:        "COH",
  SCALABILITY:     "SCAL",
  AUTONOMY:        "AUTO",
}

export function qaLabel(attribute: string): string {
  return attribute.charAt(0) + attribute.slice(1).toLowerCase()
}

export function statusText(status: QaStatus | undefined): string {
  switch (status) {
    case "excellent": return "text-green-500"
    case "good":      return "text-emerald-500"
    case "warning":   return "text-amber-500"
    case "poor":      return "text-destructive"
    default:          return "text-muted-foreground"
  }
}

export function statusBg(status: QaStatus | undefined): string {
  switch (status) {
    case "excellent": return "bg-green-500/15 text-green-600 border-green-500/30"
    case "good":      return "bg-emerald-500/15 text-emerald-600 border-emerald-500/30"
    case "warning":   return "bg-amber-500/15 text-amber-700 border-amber-500/30"
    case "poor":      return "bg-destructive/15 text-destructive border-destructive/30"
    default:          return "bg-muted text-muted-foreground border-border"
  }
}

export function isMeasured(node: VersionTreeNode): boolean {
  return Object.keys(node.scores ?? {}).length > 0
}

/** Versions carrying a score for `attribute`, best first. */
export function rankedBy(nodes: VersionTreeNode[], attribute: string): VersionTreeNode[] {
  return nodes
    .filter((n) => n.scores?.[attribute] !== undefined)
    .sort((a, b) => b.scores[attribute].score - a.scores[attribute].score)
}

/**
 * Walks `parentVersionId` from `versionId` up to the root, returning every id on the path
 * (including the starting node). Guards against cycles from bad parent data.
 */
export function pathToRoot(nodes: VersionTreeNode[], versionId: string | null): Set<string> {
  const path = new Set<string>()
  if (!versionId) return path
  const byId = new Map(nodes.map((n) => [n.versionId, n]))
  let current = byId.get(versionId)
  while (current && !path.has(current.versionId)) {
    path.add(current.versionId)
    current = current.parentVersionId ? byId.get(current.parentVersionId) : undefined
  }
  return path
}

/** Formats the refactoring that produced a version, e.g. "MERGE_AGGREGATES · tournament → answer". */
export function operationLabel(node: VersionTreeNode): string | null {
  if (!node.operation) return null
  const target = node.secondaryTarget
    ? `${node.targetService} → ${node.secondaryTarget}`
    : node.targetService
  return target ? `${node.operation} · ${target}` : node.operation
}
