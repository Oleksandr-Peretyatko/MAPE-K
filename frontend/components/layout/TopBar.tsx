"use client"

import { useState, useEffect } from "react"
import { Menu } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useSnapshot } from "@/contexts/SnapshotContext"
import { api, type CodeVersionSummary, type LoopIterationSummary } from "@/lib/api"
import { ThemeToggle } from "@/components/layout/ThemeToggle"
import { ClaudeStatusButton } from "@/components/layout/ClaudeStatusButton"
import { ExportIterationButton } from "@/components/shared/ExportIterationButton"
import { StructureButton } from "@/components/structure/StructureButton"

export function TopBar({ onMenuClick }: { onMenuClick: () => void }) {
  const { iterationId, setIterationId, setSnapshotId, codeVersionId, setCodeVersionId, navRefreshSeq } = useSnapshot()

  const [versions,    setVersions]    = useState<CodeVersionSummary[]>([])
  const [iterations,  setIterations]  = useState<LoopIterationSummary[]>([])
  const [loadingVers, setLoadingVers] = useState(false)
  const [loadingIters,setLoadingIters]= useState(false)
  const [selVerId,    setSelVerId]    = useState<string>(codeVersionId ?? "")

  // Load versions on mount
  useEffect(() => {
    let cancelled = false
    setLoadingVers(true)
    api.knowledge.versions.list()
      .then(list => {
        if (cancelled) return
        setVersions(list)
        if (!codeVersionId && list.length > 0) {
          const pick =
            list.find(v => v.status === "ACTIVE") ??
            list.find(v => v.status === "BASELINE") ??
            list[0]
          setSelVerId(pick.versionId)
          setCodeVersionId(pick.versionId)
        }
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoadingVers(false) })
    return () => { cancelled = true }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Sync external context → local selector (e.g. navigating from /versions)
  useEffect(() => {
    if (codeVersionId && codeVersionId !== selVerId) setSelVerId(codeVersionId)
  }, [codeVersionId]) // eslint-disable-line react-hooks/exhaustive-deps

  // Load iterations when selected version changes
  useEffect(() => {
    if (!selVerId) return
    let cancelled = false
    setLoadingIters(true)
    api.knowledge.versions.getIterations(selVerId)
      .then(list => {
        if (cancelled) return
        setIterations(list)
        // Keep existing if it belongs to this version; else select latest
        if (iterationId && list.some(i => i.iterationId === iterationId)) return
        if (list.length > 0) {
          const latest = list[list.length - 1]
          setIterationId(latest.iterationId)
          if (latest.snapshotId) setSnapshotId(latest.snapshotId)
        }
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoadingIters(false) })
    return () => { cancelled = true }
  }, [selVerId]) // eslint-disable-line react-hooks/exhaustive-deps

  // Refresh iterations when a new one is created from another page (same version),
  // then sync snapshotId to match (clears it for brand-new iterations with no snapshot)
  useEffect(() => {
    if (!iterationId || !selVerId) return
    if (iterations.some(i => i.iterationId === iterationId)) {
      // Already in list — still sync snapshotId in case it changed
      const found = iterations.find(i => i.iterationId === iterationId)
      setSnapshotId(found?.snapshotId ?? null)
      return
    }
    let cancelled = false
    api.knowledge.versions.getIterations(codeVersionId ?? selVerId)
      .then(list => {
        if (cancelled) return
        setIterations(list)
        const found = list.find(i => i.iterationId === iterationId)
        setSnapshotId(found?.snapshotId ?? null)
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [iterationId]) // eslint-disable-line react-hooks/exhaustive-deps

  // Re-fetch versions + iterations (without changing selection) when execution completes
  useEffect(() => {
    if (navRefreshSeq === 0) return
    api.knowledge.versions.list()
      .then(list => { setVersions(list) })
      .catch(() => {})
    if (selVerId) {
      api.knowledge.versions.getIterations(selVerId)
        .then(list => {
          setIterations(list)
          const found = list.find(i => i.iterationId === iterationId)
          if (found) setSnapshotId(found.snapshotId ?? null)
        })
        .catch(() => {})
    }
  }, [navRefreshSeq]) // eslint-disable-line react-hooks/exhaustive-deps

  function handleVersionChange(verId: string) {
    setSelVerId(verId)
    setCodeVersionId(verId)
  }

  function handleIterationChange(iterId: string) {
    setIterationId(iterId)
    const iter = iterations.find(i => i.iterationId === iterId)
    setSnapshotId(iter?.snapshotId ?? null)
  }

  const currentIter = iterations.find(i => i.iterationId === iterationId)

  return (
    <header className="fixed left-0 right-0 top-0 z-20 flex h-14 items-center gap-3 border-b border-border bg-card/95 backdrop-blur px-4 lg:left-56">
      <Button variant="ghost" size="icon" className="lg:hidden" onClick={onMenuClick} aria-label="Open menu">
        <Menu className="h-5 w-5" />
      </Button>
      <span className="text-sm font-semibold text-foreground lg:hidden">Refactor Tool</span>

      <div className="ml-auto flex items-center gap-2">
        {/* Version selector */}
        <div className="flex items-center gap-1.5">
          <span className="hidden sm:block text-xs text-muted-foreground">Version</span>
          <Select value={selVerId} onValueChange={handleVersionChange} disabled={loadingVers}>
            <SelectTrigger className="h-8 w-36 text-xs">
              <SelectValue placeholder={loadingVers ? "Loading…" : "—"} />
            </SelectTrigger>
            <SelectContent>
              {versions.map(v => (
                <SelectItem key={v.versionId} value={v.versionId}>
                  <span className="font-mono font-semibold">v{v.versionNumber}</span>
                  <span className="ml-1.5 text-muted-foreground">{v.versionLabel}</span>
                  <span className={`ml-1.5 text-[9px] font-bold uppercase ${
                    v.status === "ACTIVE"    ? "text-green-500"
                    : v.status === "BASELINE" ? "text-blue-500"
                    : "text-muted-foreground/60"
                  }`}>{v.status}</span>
                </SelectItem>
              ))}
              {versions.length === 0 && !loadingVers && (
                <div className="px-3 py-4 text-center text-xs text-muted-foreground">
                  No versions
                </div>
              )}
            </SelectContent>
          </Select>

          {/* Next to the selector on purpose: the structure is of the selected version, and the
              top bar is mounted on every phase, so it is one click away from all of them. */}
          <StructureButton
            versionId={selVerId || null}
            versionLabel={versions.find(v => v.versionId === selVerId)?.versionLabel}
          />
        </div>

        {/* Iteration selector */}
        <div className="flex items-center gap-1.5">
          <span className="hidden sm:block text-xs text-muted-foreground">Iteration</span>
          <Select
            value={currentIter ? iterationId! : ""}
            onValueChange={handleIterationChange}
            disabled={loadingIters || iterations.length === 0}
          >
            <SelectTrigger className="h-8 w-44 text-xs">
              <SelectValue placeholder={
                loadingIters ? "Loading…"
                : iterations.length === 0 ? "No iterations"
                : "—"
              } />
            </SelectTrigger>
            <SelectContent>
              {iterations.map(i => (
                <SelectItem key={i.iterationId} value={i.iterationId}>
                  <span className="font-mono font-semibold">#{i.iterationNumber}</span>
                  <span className="ml-1.5">{i.name}</span>
                  <span className={`ml-1.5 text-[9px] font-bold uppercase ${
                    i.status === "DEPLOYED" ? "text-green-500"
                    : i.status === "FAILED"   ? "text-red-500"
                    : "text-blue-500/80"
                  }`}>{i.status}</span>
                </SelectItem>
              ))}
              {iterations.length === 0 && !loadingIters && (
                <div className="px-3 py-4 text-center text-xs text-muted-foreground">
                  No iterations for this version
                </div>
              )}
            </SelectContent>
          </Select>
        </div>

        {iterationId && currentIter && (
          <ExportIterationButton
            iterationId={iterationId}
            iterationNumber={currentIter.iterationNumber}
            iterationName={currentIter.name}
            codeVersionId={codeVersionId}
          />
        )}

        <ClaudeStatusButton />
        <ThemeToggle />
      </div>
    </header>
  )
}
