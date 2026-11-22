"use client"

import { useState } from "react"
import { Network } from "lucide-react"
import { Button } from "@/components/ui/button"
import { StructureDialog } from "@/components/structure/StructureDialog"

/**
 * Opens the aggregate structure of one code version.
 *
 * <p>Mounted twice: in the top bar, bound to the globally selected version so the view is one click
 * away from every phase, and on each card of the versions page.
 */
export function StructureButton({
  versionId,
  versionLabel,
  variant = "outline",
  className,
  label,
}: {
  versionId: string | null
  versionLabel?: string
  variant?: "outline" | "ghost"
  className?: string
  label?: string
}) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button
        size="sm"
        variant={variant}
        className={className ?? "h-8 gap-1.5 text-xs"}
        disabled={!versionId}
        onClick={() => setOpen(true)}
        title={versionId
          ? "View aggregates, entities, invariants and relations"
          : "Select a version first"}
      >
        <Network className="h-3.5 w-3.5" />
        <span className={label ? "" : "hidden sm:inline"}>{label ?? "Structure"}</span>
      </Button>

      <StructureDialog
        versionId={versionId}
        versionLabel={versionLabel}
        open={open}
        onOpenChange={setOpen}
      />
    </>
  )
}
