"use client"

import { useState } from "react"
import * as XLSX from "xlsx"
import { Download, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { api } from "@/lib/api"
import { buildIterationWorkbook } from "@/lib/export-iteration"

interface Props {
  iterationId: string
  iterationNumber: number
  iterationName: string
  codeVersionId?: string | null
}

export function ExportIterationButton({ iterationId, iterationNumber, iterationName, codeVersionId }: Props) {
  const [state, setState] = useState<"idle" | "loading" | "error">("idle")
  const [errorMsg, setErrorMsg] = useState("")

  async function handleExport() {
    setState("loading")
    setErrorMsg("")
    try {
      const detail = await api.knowledge.iterations.get(iterationId)
      const versionId = codeVersionId ?? detail.codeVersionId

      const [planResult, archResult, discoveryResult, settingsResult] = await Promise.allSettled([
        detail.planId ? api.plan.get(detail.planId) : Promise.resolve(null),
        api.config.graph(),
        versionId ? api.knowledge.versions.getDiscovery(versionId) : Promise.resolve(null),
        api.config.settings(),
      ])

      const wb = buildIterationWorkbook({
        detail,
        plan:          planResult.status      === "fulfilled" ? planResult.value      : null,
        archGraph:     archResult.status      === "fulfilled" ? archResult.value      : null,
        discoveryGraph: discoveryResult.status === "fulfilled" ? discoveryResult.value : null,
        settings:      settingsResult.status  === "fulfilled" ? settingsResult.value  : null,
      })

      const date = new Date().toISOString().slice(0, 10)
      const safeName = iterationName.replace(/[^a-zA-Z0-9-_]/g, "-").slice(0, 30)
      XLSX.writeFile(wb, `iteration-${iterationNumber}-${safeName}-${date}.xlsx`)
      setState("idle")
    } catch (e: unknown) {
      setErrorMsg(e instanceof Error ? e.message : "Export failed")
      setState("error")
    }
  }

  return (
    <div className="flex items-center gap-1.5">
      <Button
        size="sm"
        variant="outline"
        className="h-7 px-2 text-xs gap-1"
        onClick={handleExport}
        disabled={state === "loading"}
        title="Export iteration data as Excel (.xlsx)"
      >
        {state === "loading"
          ? <Loader2 className="h-3 w-3 animate-spin" />
          : <Download className="h-3 w-3" />}
        Export
      </Button>
      {state === "error" && (
        <span className="text-[10px] text-destructive truncate max-w-[140px]" title={errorMsg}>{errorMsg}</span>
      )}
    </div>
  )
}
