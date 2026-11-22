"use client"

import { useState } from "react"
import { Check, Copy, Terminal } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { cn } from "@/lib/utils"

type Shell = "bash" | "powershell"

/**
 * The stored command is bash. Only its `export A=x B=y` line is shell-specific: PowerShell gets one
 * `$env:A = "x"` line per variable, and every git/docker line is the same in both shells.
 */
function forShell(command: string, shell: Shell): string {
  if (shell === "bash") return command
  return command
    .split("\n")
    .flatMap((line) => {
      if (!line.startsWith("export ")) return [line]
      return line
        .slice("export ".length)
        .trim()
        .split(/\s+/)
        .map((pair) => {
          const eq = pair.indexOf("=")
          return `$env:${pair.slice(0, eq)} = "${pair.slice(eq + 1)}"`
        })
    })
    .join("\n")
}

/**
 * Shows the commands that deploy one code version by hand — checkout, image build, teardown and
 * `docker compose up` with that version's own service set — as recorded by rt-app.
 */
export function DeployCommandButton({
  versionLabel,
  deployCommand,
  className,
}: {
  versionLabel: string
  deployCommand?: string
  className?: string
}) {
  const [open, setOpen] = useState(false)
  const [shell, setShell] = useState<Shell>("bash")
  const [copied, setCopied] = useState(false)

  const text = deployCommand ? forShell(deployCommand, shell) : ""

  function copy() {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <>
      <Button
        size="sm"
        variant="outline"
        className={className ?? "h-7 gap-1 px-2 text-xs"}
        disabled={!deployCommand}
        onClick={() => setOpen(true)}
        title={deployCommand ? "Commands to deploy this version" : "No deploy command recorded"}
      >
        <Terminal className="h-3 w-3" />
        Deploy
      </Button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>Deploy {versionLabel}</DialogTitle>
            <DialogDescription>
              Run from the repository root. It checks out the version&apos;s branch, builds its image and
              brings up exactly the services that version defines.
            </DialogDescription>
          </DialogHeader>

          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center rounded-md border border-border p-0.5">
              {(["bash", "powershell"] as const).map((s) => (
                <button
                  key={s}
                  onClick={() => setShell(s)}
                  className={cn(
                    "rounded px-2.5 py-1 text-xs transition-colors",
                    shell === s
                      ? "bg-primary/15 font-semibold text-foreground"
                      : "text-muted-foreground hover:text-foreground",
                  )}
                >
                  {s === "bash" ? "bash" : "PowerShell"}
                </button>
              ))}
            </div>
            <Button size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" onClick={copy}>
              {copied ? <Check className="h-3 w-3 text-green-500" /> : <Copy className="h-3 w-3" />}
              {copied ? "Copied" : "Copy"}
            </Button>
          </div>

          <pre className="max-h-80 overflow-auto whitespace-pre rounded-md border border-border bg-muted/40 p-3 font-mono text-xs leading-relaxed text-foreground">
            {text}
          </pre>

          <ul className="list-disc space-y-0.5 pl-4 text-xs text-muted-foreground">
            <li>
              The working tree stays on that branch. Check out the base branch again afterwards; the
              containers keep running.
            </li>
            <li>A manual deploy does not change which version rt-app records as ACTIVE.</li>
          </ul>
        </DialogContent>
      </Dialog>
    </>
  )
}
