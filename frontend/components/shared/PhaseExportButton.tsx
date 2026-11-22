"use client"

import { useState } from "react"
import { Download, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"

interface PhaseExportButtonProps {
  url: string | null | undefined
  filename: string
  label?: string
}

export function PhaseExportButton({ url, filename, label = "Export" }: PhaseExportButtonProps) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState("")

  async function handleClick() {
    if (!url) return
    setLoading(true)
    setError("")
    try {
      const res = await fetch(url)
      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
      const blob = await res.blob()
      const objectUrl = URL.createObjectURL(blob)
      const a = document.createElement("a")
      a.href = objectUrl
      a.download = filename
      a.click()
      URL.revokeObjectURL(objectUrl)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Download failed")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex items-center gap-1.5">
      <Button
        size="sm"
        variant="outline"
        className="h-7 px-2 text-xs gap-1"
        onClick={handleClick}
        disabled={!url || loading}
        title={url ? `Download ${filename}` : "No data yet — complete this phase first"}
      >
        {loading
          ? <Loader2 className="h-3 w-3 animate-spin" />
          : <Download className="h-3 w-3" />}
        {label}
      </Button>
      {error && (
        <span className="text-[10px] text-destructive truncate max-w-[120px]" title={error}>
          {error}
        </span>
      )}
    </div>
  )
}
