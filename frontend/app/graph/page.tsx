'use client';

import { useCallback, useEffect, useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { AlertCircle, GitBranch, Loader2, Sparkles, ChevronDown, ChevronRight } from 'lucide-react';
import { api, ServiceStaticMetrics, StaticVersion, DiscoveryGraph, DiscoveredService, DiscoveredAggregate, CodeVersionSummary } from '@/lib/api';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useSnapshot } from '@/contexts/SnapshotContext';
import CytoscapeGraph from '@/components/graph/CytoscapeGraph';

// ─── Static graph element builders ───────────────────────────────────────────

interface CyNode { data: { id: string; label: string; type: string } }
interface CyEdge { data: { source: string; target: string } }

function buildElements(version: StaticVersion): { nodes: CyNode[]; edges: CyEdge[] } {
  const { services } = version.metrics;
  const serviceIds = new Set(Object.keys(services));
  const nodes: CyNode[] = Object.keys(services).map(name => ({
    data: { id: name, label: name, type: 'service' },
  }));
  const edgeSet = new Set<string>();
  const edges: CyEdge[] = [];
  for (const [source, svc] of Object.entries(services)) {
    for (const [target, count] of Object.entries(svc.externalRelations)) {
      if (count > 0 && serviceIds.has(target)) {
        const key = `${source}::${target}`;
        if (!edgeSet.has(key)) {
          edgeSet.add(key);
          edges.push({ data: { source, target } });
        }
      }
    }
  }
  return { nodes, edges };
}

// ─── Discovery graph element builders ────────────────────────────────────────

function buildDiscoveryElements(graph: DiscoveryGraph): { nodes: CyNode[]; edges: CyEdge[] } {
  const nodes: CyNode[] = graph.services.map(s => ({
    data: { id: s.name, label: s.name, type: 'service' },
  }));
  const edgeSet = new Set<string>();
  const edges: CyEdge[] = [];
  for (const svc of graph.services) {
    for (const agg of svc.aggregates ?? []) {
      for (const rel of agg.externalRelations ?? []) {
        const key = `${svc.name}::${rel.to}`;
        if (!edgeSet.has(key)) {
          edgeSet.add(key);
          edges.push({ data: { source: svc.name, target: rel.to } });
        }
      }
    }
  }
  return { nodes, edges };
}

// ─── Discovery detail panel ───────────────────────────────────────────────────

function AggregateCard({ agg }: { agg: DiscoveredAggregate }) {
  const [open, setOpen] = useState(false);
  const intraInvariants = agg.invariants?.filter(i => i.type === 'intra') ?? [];
  const interInvariants = agg.invariants?.filter(i => i.type === 'inter') ?? [];
  return (
    <div className="rounded border border-border bg-muted/20 p-2.5 text-xs">
      <button
        className="flex w-full items-center justify-between gap-2 font-medium text-foreground"
        onClick={() => setOpen(o => !o)}
      >
        <span className="font-mono">{agg.name}</span>
        <span className="flex items-center gap-1 text-muted-foreground">
          <span>{agg.entities?.length ?? 0} entities</span>
          {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        </span>
      </button>

      {open && (
        <div className="mt-2 space-y-2 border-t border-border pt-2">
          {agg.entities && agg.entities.length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-widest text-muted-foreground mb-1">Entities</p>
              <div className="flex flex-wrap gap-1">
                {agg.entities.map(e => (
                  <span key={e} className="rounded bg-background border border-border px-1.5 py-0.5 font-mono text-[10px]">{e}</span>
                ))}
              </div>
            </div>
          )}
          {intraInvariants.length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-widest text-muted-foreground mb-1">Intra-invariants</p>
              <div className="space-y-0.5">
                {intraInvariants.map(inv => (
                  <div key={inv.name} className="font-mono text-[10px] text-blue-600 dark:text-blue-400">{inv.name}</div>
                ))}
              </div>
            </div>
          )}
          {interInvariants.length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-widest text-muted-foreground mb-1">Inter-invariants</p>
              <div className="space-y-0.5">
                {interInvariants.map(inv => (
                  <div key={inv.name} className="font-mono text-[10px] text-purple-600 dark:text-purple-400">{inv.name}</div>
                ))}
              </div>
            </div>
          )}
          {agg.externalRelations && agg.externalRelations.length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-widest text-muted-foreground mb-1">External relations</p>
              <div className="space-y-0.5">
                {agg.externalRelations.map((r, i) => (
                  <div key={i} className="flex items-center gap-1 text-[10px]">
                    <span className="font-mono text-muted-foreground">{r.via}</span>
                    <span className="text-muted-foreground">→</span>
                    <span className="font-mono font-medium">{r.to}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function DiscoveryServicePanel({ svc }: { svc: DiscoveredService }) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-semibold text-foreground">{svc.name}</p>
      {svc.aggregates?.map(agg => (
        <AggregateCard key={agg.name} agg={agg} />
      ))}
      {(!svc.aggregates || svc.aggregates.length === 0) && (
        <p className="text-xs text-muted-foreground italic">No aggregates detected</p>
      )}
    </div>
  );
}

// ─── Page ──────────────────────────────────────────────────────────────────────

export default function GraphPage() {
  const { snapshotId, codeVersionId, setCodeVersionId } = useSnapshot();

  // Static analysis tab
  const [version,    setVersion]    = useState<StaticVersion | null>(null);
  const [loading,    setLoading]    = useState(false);
  const [error,      setError]      = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // Discovery tab
  const [codeVersions,   setCodeVersions]   = useState<CodeVersionSummary[]>([]);
  const [selectedVerId,  setSelectedVerId]  = useState<string | null>(codeVersionId);
  const [discovery,      setDiscovery]      = useState<DiscoveryGraph | null>(null);
  const [discLoading,    setDiscLoading]    = useState(false);
  const [discError,      setDiscError]      = useState<string | null>(null);
  const [discSelected,   setDiscSelected]   = useState<string | null>(null);
  const [discovering,    setDiscovering]    = useState(false);

  const loadGraph = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const v = await api.monitor.staticVersion('current');
      setVersion(v);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load graph data');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadCodeVersions = useCallback(async () => {
    try {
      const list = await api.knowledge.versions.list();
      setCodeVersions(list);
      // Pre-select from context, or the first ACTIVE/BASELINE version
      if (!selectedVerId && list.length > 0) {
        const preferred = list.find(v => v.status === 'ACTIVE') ?? list.find(v => v.status === 'BASELINE') ?? list[0];
        setSelectedVerId(preferred.versionId);
      }
    } catch { /* silent */ }
  }, [selectedVerId]);

  const loadDiscovery = useCallback(async () => {
    const vid = selectedVerId;
    if (!vid) return;
    setDiscLoading(true);
    setDiscError(null);
    try {
      const g = await api.knowledge.versions.getDiscovery(vid);
      setDiscovery(g);
    } catch (e: unknown) {
      if (e instanceof Error && e.message.includes('404')) {
        setDiscovery(null);
      } else {
        setDiscError(e instanceof Error ? e.message : 'Failed to load discovery');
      }
    } finally {
      setDiscLoading(false);
    }
  }, [selectedVerId]);

  useEffect(() => { loadGraph(); }, [loadGraph]);
  useEffect(() => { loadCodeVersions(); }, [loadCodeVersions]);
  useEffect(() => { loadDiscovery(); }, [loadDiscovery]);

  // Sync context codeVersionId → local selector
  useEffect(() => { if (codeVersionId) setSelectedVerId(codeVersionId); }, [codeVersionId]);

  function handleVersionSelect(versionId: string) {
    setSelectedVerId(versionId);
    setCodeVersionId(versionId);
    setDiscovery(null);
  }

  async function handleDiscover() {
    const vid = selectedVerId;
    if (!vid) return;
    setDiscovering(true);
    setDiscError(null);
    try {
      const g = await api.knowledge.versions.runDiscovery(vid);
      setDiscovery(g);
    } catch (e: unknown) {
      setDiscError(e instanceof Error ? e.message : 'Discovery failed');
    } finally {
      setDiscovering(false);
    }
  }

  // Static graph
  const { nodes, edges } = version ? buildElements(version) : { nodes: [], edges: [] };
  const selectedSvc: ServiceStaticMetrics | null =
    selectedId && version ? (version.metrics.services[selectedId] ?? null) : null;

  // Discovery graph
  const { nodes: dNodes, edges: dEdges } = discovery ? buildDiscoveryElements(discovery) : { nodes: [], edges: [] };
  const selectedDiscSvc = discSelected && discovery
    ? discovery.services.find(s => s.name === discSelected) ?? null
    : null;

  return (
    <div className="flex h-full flex-col gap-6">
      <div>
        <h1 className="text-lg font-semibold text-foreground">Service Graph</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Dependency topology and Claude-powered architecture discovery
        </p>
      </div>

        <Tabs defaultValue="static" className="flex flex-1 flex-col">
          <TabsList className="w-fit">
            <TabsTrigger value="static">Static Analysis</TabsTrigger>
            <TabsTrigger value="discovery">Discovery</TabsTrigger>
          </TabsList>

          {/* ── Static Analysis tab ── */}
          <TabsContent value="static" className="flex flex-1 flex-col">
            {error && (
              <div className="flex items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4 mb-4">
                <AlertCircle className="w-5 h-5 text-destructive flex-shrink-0 mt-0.5" />
                <p className="text-sm text-destructive">{error}</p>
              </div>
            )}
            {loading && (
              <div className="flex items-center gap-3 text-muted-foreground mb-4">
                <Loader2 className="w-5 h-5 animate-spin" />
                <span className="text-sm">Loading graph data…</span>
              </div>
            )}
            {version && (
              <div className="flex flex-1 gap-4" style={{ minHeight: '500px' }}>
                <div className="flex-1 rounded-lg border border-border overflow-hidden">
                  <CytoscapeGraph nodes={nodes} edges={edges} onNodeSelect={setSelectedId} />
                </div>
                <div className="w-72 flex flex-col gap-4 overflow-y-auto">
                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm">Summary</CardTitle>
                    </CardHeader>
                    <CardContent className="text-xs space-y-1">
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">M1 cohesion</span>
                        <span className="font-mono">{version.metrics.m1.toFixed(4)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">M2 coupling</span>
                        <span className="font-mono">{version.metrics.m2.toFixed(4)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Services</span>
                        <span className="font-mono">{version.metrics.serviceCount}</span>
                      </div>
                      {version.metrics.sourceDir && (
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Source</span>
                          <span className="font-mono text-[10px] truncate ml-2">{version.version}</span>
                        </div>
                      )}
                    </CardContent>
                  </Card>

                  {selectedSvc ? (
                    <Card>
                      <CardHeader className="pb-2">
                        <CardTitle className="flex items-center gap-2 text-sm">
                          <GitBranch className="w-4 h-4" />
                          {selectedSvc.serviceName}
                        </CardTitle>
                      </CardHeader>
                      <CardContent className="text-xs space-y-3">
                        <div className="space-y-1">
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Entities (N)</span>
                            <span className="font-mono">{selectedSvc.entityCount}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">Internal rels (μ)</span>
                            <span className="font-mono">{selectedSvc.internalRelations}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="text-muted-foreground">M1 contribution</span>
                            <span className="font-mono">{selectedSvc.m1Contribution.toFixed(4)}</span>
                          </div>
                        </div>
                        {selectedSvc.entityNames && selectedSvc.entityNames.length > 0 && (
                          <div>
                            <p className="text-muted-foreground mb-1">Entities</p>
                            <div className="space-y-0.5">
                              {selectedSvc.entityNames.map(n => (
                                <div key={n} className="font-mono text-[11px]">{n}</div>
                              ))}
                            </div>
                          </div>
                        )}
                        {Object.keys(selectedSvc.externalRelations).length > 0 && (
                          <div>
                            <p className="text-muted-foreground mb-1">External deps (σ)</p>
                            <div className="space-y-0.5">
                              {Object.entries(selectedSvc.externalRelations).map(([target, count]) => (
                                <div key={target} className="flex justify-between">
                                  <span className="font-mono">{target}</span>
                                  <span className="text-muted-foreground">{count}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                        {selectedSvc.invariants && selectedSvc.invariants.length > 0 && (
                          <div>
                            <p className="text-muted-foreground mb-1">Invariants ({selectedSvc.invariants.length})</p>
                            <div className="space-y-1">
                              {selectedSvc.invariants.map(inv => (
                                <div key={inv.name}>
                                  <div className="font-mono text-[11px]">{inv.name}</div>
                                  {/* The footprint is the point: a rule's name says nothing about
                                      which side of a cut it can live on, its reads do. */}
                                  <div className="pl-2 font-mono text-[10px] text-muted-foreground">
                                    {[
                                      inv.memberEntities?.join(", "),
                                      inv.rootScalars?.length ? `root.${inv.rootScalars.join(", root.")}` : null,
                                      inv.readsState ? "state" : null,
                                      inv.readsPrev ? "prev" : null,
                                    ].filter(Boolean).join(" + ") || "no state read"}
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  ) : (
                    <Card>
                      <CardContent className="py-8 text-center text-muted-foreground text-xs">
                        Click a node to see service details
                      </CardContent>
                    </Card>
                  )}
                </div>
              </div>
            )}
          </TabsContent>

          {/* ── Discovery tab ── */}
          <TabsContent value="discovery" className="flex flex-1 flex-col gap-4">
            {/* Toolbar */}
            <div className="flex flex-wrap items-center gap-3">
              <Select value={selectedVerId ?? ""} onValueChange={handleVersionSelect}>
                <SelectTrigger className="w-52">
                  <SelectValue placeholder="Select a code version" />
                </SelectTrigger>
                <SelectContent>
                  {codeVersions.map(v => (
                    <SelectItem key={v.versionId} value={v.versionId}>
                      {v.versionLabel} — {v.status}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="flex-1 text-sm text-muted-foreground">
                {discovery
                  ? `Discovered ${new Date(discovery.discoveredAt).toLocaleString()} · ${codeVersions.find(v => v.versionId === selectedVerId)?.versionLabel ?? ''}`
                  : selectedVerId
                    ? 'No discovery graph yet for this version.'
                    : 'Select a code version to view or run discovery.'}
              </p>
              <Button
                size="sm"
                variant="outline"
                onClick={handleDiscover}
                disabled={!selectedVerId || discovering}
                className="gap-1.5"
              >
                {discovering
                  ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  : <Sparkles className="h-3.5 w-3.5" />}
                {discovering ? 'Discovering…' : discovery ? 'Re-run Discovery' : 'Run Discovery'}
              </Button>
            </div>

            {discError && (
              <div className="flex items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4">
                <AlertCircle className="w-5 h-5 text-destructive flex-shrink-0 mt-0.5" />
                <p className="text-sm text-destructive">{discError}</p>
              </div>
            )}

            {discLoading && (
              <div className="flex items-center gap-3 text-muted-foreground">
                <Loader2 className="w-5 h-5 animate-spin" />
                <span className="text-sm">Loading discovery graph…</span>
              </div>
            )}

            {discovery && discovery.services.length > 0 && (
              <div className="flex flex-1 gap-4" style={{ minHeight: '500px' }}>
                {/* Cytoscape canvas */}
                <div className="flex-1 rounded-lg border border-border overflow-hidden">
                  <CytoscapeGraph
                    nodes={dNodes}
                    edges={dEdges}
                    onNodeSelect={setDiscSelected}
                  />
                </div>

                {/* Detail panel */}
                <div className="w-80 overflow-y-auto space-y-4">
                  {/* Summary */}
                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm">Summary</CardTitle>
                    </CardHeader>
                    <CardContent className="text-xs space-y-1">
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Services</span>
                        <span className="font-mono">{discovery.services.length}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Aggregates</span>
                        <span className="font-mono">
                          {discovery.services.reduce((s, svc) => s + (svc.aggregates?.length ?? 0), 0)}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Total entities</span>
                        <span className="font-mono">
                          {discovery.services.reduce((s, svc) =>
                            s + (svc.aggregates?.reduce((a, agg) => a + (agg.entities?.length ?? 0), 0) ?? 0), 0)}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Invariants</span>
                        <span className="font-mono">
                          {discovery.services.reduce((s, svc) =>
                            s + (svc.aggregates?.reduce((a, agg) => a + (agg.invariants?.length ?? 0), 0) ?? 0), 0)}
                        </span>
                      </div>
                    </CardContent>
                  </Card>

                  {/* Selected service or all services */}
                  {selectedDiscSvc ? (
                    <Card>
                      <CardHeader className="pb-2">
                        <CardTitle className="flex items-center gap-2 text-sm">
                          <GitBranch className="w-4 h-4" />
                          {selectedDiscSvc.name}
                        </CardTitle>
                      </CardHeader>
                      <CardContent className="text-xs space-y-3">
                        <DiscoveryServicePanel svc={selectedDiscSvc} />
                        {selectedDiscSvc.serviceOperations && selectedDiscSvc.serviceOperations.length > 0 && (
                          <div>
                            <p className="text-[10px] uppercase tracking-widest text-muted-foreground mb-1">
                              Service operations
                            </p>
                            <div className="space-y-0.5">
                              {selectedDiscSvc.serviceOperations.slice(0, 10).map(op => (
                                <div key={op} className="font-mono text-[10px]">{op}</div>
                              ))}
                              {selectedDiscSvc.serviceOperations.length > 10 && (
                                <p className="text-muted-foreground text-[10px]">
                                  +{selectedDiscSvc.serviceOperations.length - 10} more
                                </p>
                              )}
                            </div>
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  ) : (
                    <Card>
                      <CardContent className="py-6 text-center text-muted-foreground text-xs">
                        Click a node to see aggregate details
                      </CardContent>
                    </Card>
                  )}
                </div>
              </div>
            )}

            {discovery && discovery.services.length === 0 && (
              <div className="py-12 text-center text-sm text-muted-foreground">
                Discovery completed but no services were detected. Check Claude output.
              </div>
            )}
          </TabsContent>
        </Tabs>
    </div>
  );
}
