"use client"

import { useCallback, useEffect, useState } from "react"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { api, type ClaudeAuthState, type ClaudeAuthStatus } from "@/lib/api"
import { cn } from "@/lib/utils"

const STATE_LABEL: Record<ClaudeAuthState, string> = {
  OK:             "Logged in",
  NOT_LOGGED_IN:  "Not logged in",
  BINARY_MISSING: "claude CLI not found",
  UNAVAILABLE:    "API unavailable",
  ERROR:          "Check failed",
}

const STATE_HINT: Partial<Record<ClaudeAuthState, string>> = {
  NOT_LOGGED_IN:  "Run `claude login` on the host, then click to check again. ~/.claude is mounted into rt-app, so no restart is needed.",
  BINARY_MISSING: "Install @anthropic-ai/claude-code in the rt-app image.",
  UNAVAILABLE:    "The Claude API is overloaded or did not answer. Try again in a minute.",
}

function dotColor(state: ClaudeAuthState | "UNREACHABLE" | null): string {
  switch (state) {
    case "OK":             return "bg-green-500"
    case "NOT_LOGGED_IN":
    case "BINARY_MISSING": return "bg-red-500"
    case "UNAVAILABLE":
    case "ERROR":
    case "UNREACHABLE":    return "bg-amber-500"
    default:               return "bg-muted-foreground/50"
  }
}

/** Navbar indicator for the Claude session rt-app uses. Checks on mount; click re-validates. */
export function ClaudeStatusButton() {
  const [status,   setStatus]   = useState<ClaudeAuthStatus | null>(null)
  const [netError, setNetError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  const check = useCallback(async (refresh: boolean) => {
    setChecking(true)
    try {
      setStatus(await api.claude.status(refresh))
      setNetError(null)
    } catch (e) {
      setNetError(e instanceof Error ? e.message : String(e))
    } finally {
      setChecking(false)
    }
  }, [])

  useEffect(() => { check(false) }, [check])

  const state = netError ? "UNREACHABLE" : status?.state ?? null
  const label = netError ? "rt-app unreachable" : status ? STATE_LABEL[status.state] : "Checking…"
  const hint  = status && !netError ? STATE_HINT[status.state] : undefined

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="h-8 gap-1.5 px-2 text-xs"
          onClick={() => check(true)}
          disabled={checking}
          aria-label={`Claude: ${label}. Click to check again.`}
        >
          {checking
            ? <Loader2 className="h-3 w-3 animate-spin" />
            : <span className={cn("h-2 w-2 rounded-full", dotColor(state))} />}
          <span className="hidden sm:inline">Claude</span>
        </Button>
      </TooltipTrigger>
      <TooltipContent side="bottom" align="end" className="max-w-xs space-y-1">
        <p className="font-semibold">Claude: {checking ? "checking…" : label}</p>
        {status && !netError && (
          <p className="text-muted-foreground">
            claude CLI
            {status.authMethod && ` · ${status.authMethod}`}
            {status.account && ` · ${status.account}`}
          </p>
        )}
        {hint && <p>{hint}</p>}
        {(netError || (status && status.state !== "OK" && status.message)) && (
          <p className="break-words font-mono text-[10px] text-muted-foreground">
            {netError ?? status?.message}
          </p>
        )}
        {status && !netError && (
          <p className="text-[10px] text-muted-foreground">
            Checked {new Date(status.checkedAt).toLocaleTimeString()}
            {status.cached && " (cached)"} · click to re-check
          </p>
        )}
      </TooltipContent>
    </Tooltip>
  )
}
