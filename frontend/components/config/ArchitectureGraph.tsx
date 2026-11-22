"use client"

import dynamic from "next/dynamic"
import type { ArchGraph, ArchGraphService } from "@/lib/api"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"

const SERVICE_COLORS = [
  "#3b82f6", "#10b981", "#f59e0b", "#ef4444",
  "#8b5cf6", "#06b6d4", "#ec4899", "#84cc16",
]

interface Props {
  graph: ArchGraph
}

const CytoscapeGraph = dynamic(
  () => import("@/components/graph/CytoscapeGraph").then((m) => m.default),
  { ssr: false },
)

function buildCytoscapeData(graph: ArchGraph) {
  const nodes: { data: { id: string; label: string; type: string } }[] = []
  const edges: { data: { source: string; target: string } }[] = []
  const colorMap: Record<string, string> = {}

  graph.services.forEach((svc, i) => {
    colorMap[svc.name] = SERVICE_COLORS[i % SERVICE_COLORS.length]
    nodes.push({
      data: {
        id: svc.name,
        label: svc.name.replace("-service", ""),
        type: "service",
      },
    })
    ;(svc.entities ?? []).forEach((entity) => {
      nodes.push({
        data: {
          id: `${svc.name}::${entity}`,
          label: entity,
          type: "entity",
        },
      })
    })
  })

  graph.services.forEach((svc) => {
    Object.entries(svc.externalRelations ?? {}).forEach(([target]) => {
      edges.push({
        data: { source: svc.name, target },
      })
    })
  })

  return { nodes, edges }
}

export function ArchitectureGraph({ graph }: Props) {
  if (!graph.services || graph.services.length === 0) {
    return (
      <div className="rounded-lg border border-border p-8 text-center">
        <p className="text-sm text-muted-foreground">
          {graph.error ?? "No static analysis data available. Run a Monitor collection first."}
        </p>
      </div>
    )
  }

  const { nodes, edges } = buildCytoscapeData(graph)

  return (
    <div className="space-y-4">
      {/* Summary KPIs */}
      <div className="grid grid-cols-3 gap-3">
        {[
          { label: "Services", value: graph.serviceCount },
          { label: "M1 Cohesion", value: graph.m1?.toFixed(4) ?? "—" },
          { label: "M2 Coupling", value: graph.m2?.toFixed(4) ?? "—" },
        ].map((kpi) => (
          <div key={kpi.label} className="rounded-lg border border-border bg-muted/20 p-3 text-center">
            <p className="text-lg font-bold tabular-nums text-foreground">{kpi.value}</p>
            <p className="text-[10px] text-muted-foreground">{kpi.label}</p>
          </div>
        ))}
      </div>

      {/* Cytoscape graph */}
      <div className="h-[500px] rounded-lg border border-border overflow-hidden">
        <CytoscapeGraph nodes={nodes} edges={edges} onNodeSelect={() => {}} />
      </div>

      {/* Service cards */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {graph.services.map((svc) => (
          <ServiceCard key={svc.name} service={svc} />
        ))}
      </div>
    </div>
  )
}

function ServiceCard({ service: s }: { service: ArchGraphService }) {
  const extCount = Object.values(s.externalRelations ?? {}).reduce((a, b) => a + b, 0)
  return (
    <Card className="text-xs">
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-mono">{s.name}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex gap-2 flex-wrap">
          <span className="text-muted-foreground">Entities: <strong className="text-foreground">{s.entityCount}</strong></span>
          <span className="text-muted-foreground">μ: <strong className="text-foreground">{s.internalRelations}</strong></span>
          <span className="text-muted-foreground">σ: <strong className="text-foreground">{extCount}</strong></span>
          <span className="text-muted-foreground">M1: <strong className="text-foreground">{s.m1Contribution?.toFixed(4)}</strong></span>
        </div>
        {s.entities?.length > 0 && (
          <div className="flex gap-1 flex-wrap">
            {s.entities.map((e) => (
              <Badge key={e} variant="outline" className="text-[10px]">{e}</Badge>
            ))}
          </div>
        )}
        {Object.entries(s.externalRelations ?? {}).length > 0 && (
          <div className="flex gap-1 flex-wrap">
            {Object.entries(s.externalRelations).map(([target, sigma]) => (
              <span key={target} className="text-[10px] text-muted-foreground rounded bg-muted px-1.5 py-0.5">
                → {target.replace("-service", "")} (σ={sigma})
              </span>
            ))}
          </div>
        )}
        {s.invariants?.length > 0 && (
          <p className="text-[10px] text-muted-foreground">
            Invariants: {s.invariants.join(", ")}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
