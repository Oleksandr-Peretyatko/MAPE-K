import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
import { EmptyState } from "@/components/shared/EmptyState"
import type { Hotspot } from "@/lib/api"
import { cn } from "@/lib/utils"
import { AlertTriangle } from "lucide-react"

const HOTSPOT_COLORS: Record<string, string> = {
  HIGH_LATENCY: "destructive",
  HIGH_ERROR_RATE: "destructive",
  TIGHT_COUPLING: "orange",
  HIGH_ENTITY_COUNT: "orange",
  LOW_COHESION: "warning",
  READ_HEAVY: "warning",
  HIGH_COMPENSATION: "warning",
  CONSISTENCY_VIOLATION: "blue",
}

export function HotspotsGrid({ hotspots }: { hotspots: Hotspot[] }) {
  if (!hotspots?.length)
    return <EmptyState title="No hotspots detected" description="Run an analysis to identify architectural issues." />

  const critical = hotspots.filter((h) => h.severity > 0.7)
  const warning  = hotspots.filter((h) => h.severity >= 0.4 && h.severity <= 0.7)
  const info     = hotspots.filter((h) => h.severity < 0.4)

  const defaultTab = critical.length ? "critical" : warning.length ? "warning" : "info"

  return (
    <Tabs defaultValue={defaultTab}>
      <TabsList className="flex-wrap h-auto gap-1">
        <TabsTrigger value="critical" disabled={critical.length === 0} className="gap-1.5 text-xs">
          Critical
          {critical.length > 0 && (
            <span className="rounded-full bg-destructive/15 text-destructive px-1.5 py-0 text-[10px] font-semibold">
              {critical.length}
            </span>
          )}
        </TabsTrigger>
        <TabsTrigger value="warning" disabled={warning.length === 0} className="gap-1.5 text-xs">
          Warning
          {warning.length > 0 && (
            <span className="rounded-full bg-warning/20 text-warning px-1.5 py-0 text-[10px] font-semibold">
              {warning.length}
            </span>
          )}
        </TabsTrigger>
        <TabsTrigger value="info" disabled={info.length === 0} className="gap-1.5 text-xs">
          Info
          {info.length > 0 && (
            <span className="rounded-full bg-primary/10 text-primary px-1.5 py-0 text-[10px] font-semibold">
              {info.length}
            </span>
          )}
        </TabsTrigger>
      </TabsList>

      <TabsContent value="critical">
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 mt-3">
          {critical.map((h, i) => <HotspotCard key={i} hotspot={h} borderClass="border-destructive/30" />)}
        </div>
      </TabsContent>
      <TabsContent value="warning">
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 mt-3">
          {warning.map((h, i) => <HotspotCard key={i} hotspot={h} borderClass="border-warning/30" />)}
        </div>
      </TabsContent>
      <TabsContent value="info">
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 mt-3">
          {info.map((h, i) => <HotspotCard key={i} hotspot={h} borderClass="border-primary/30" />)}
        </div>
      </TabsContent>
    </Tabs>
  )
}

function HotspotCard({ hotspot, borderClass }: { hotspot: Hotspot; borderClass: string }) {
  const colorVariant = (HOTSPOT_COLORS[hotspot.type] ?? "secondary") as "destructive" | "orange" | "warning" | "blue" | "secondary"
  return (
    <div className={cn("rounded-lg border bg-card p-4 space-y-3", borderClass)}>
      <div className="flex items-center justify-between">
        <Badge variant={colorVariant} className="text-[10px]">{hotspot.type.replace(/_/g, " ")}</Badge>
        <span className="text-xs text-muted-foreground">{(hotspot.severity * 100).toFixed(0)}%</span>
      </div>
      <div className="flex flex-wrap gap-1">
        {hotspot.affectedServices.map((s) => (
          <span key={s} className="rounded bg-secondary px-1.5 py-0.5 text-[10px] font-mono text-secondary-foreground">{s}</span>
        ))}
      </div>
      <p className="text-xs text-muted-foreground leading-relaxed">{hotspot.evidence}</p>
      <Progress value={hotspot.severity * 100} className="h-1" />
    </div>
  )
}
