"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { Activity, Search, Network, ClipboardList, Play, Settings, GitBranch, List } from "lucide-react"
import { cn } from "@/lib/utils"

const PHASES = [
  { label: "Monitor", tab: "monitor", icon: Activity },
  { label: "Analyze", tab: "analyze", icon: Search },
  { label: "Plan",    tab: "plan",    icon: ClipboardList },
  { label: "Execute", tab: "execute", icon: Play },
]

const TOOLS = [
  { label: "Config", href: "/config", icon: Settings },
  { label: "Graph",  href: "/graph",  icon: Network },
]

export function Sidebar({ open, onClose }: { open?: boolean; onClose?: () => void }) {
  const pathname = usePathname()
  const onLoop   = pathname === "/loop"
  const onIter   = pathname.startsWith("/iterations") || onLoop

  return (
    <>
      {/* Mobile overlay */}
      {open && (
        <div
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}
      <aside
        className={cn(
          "fixed left-0 top-0 z-40 flex h-full w-56 flex-col border-r border-border bg-card transition-transform duration-200",
          "lg:translate-x-0",
          open ? "translate-x-0" : "-translate-x-full lg:translate-x-0",
        )}
      >
        {/* Brand */}
        <div className="flex h-14 items-center border-b border-border px-4">
          <span className="text-sm font-bold tracking-tight text-foreground">Refactor Tool</span>
          <span className="ml-2 rounded bg-primary/20 px-1.5 py-0.5 text-[10px] font-semibold text-primary">MAPE-K</span>
        </div>

        <nav className="flex-1 overflow-y-auto py-3">
          <ul className="space-y-0.5 px-2">
            {/* Versions */}
            <li>
              <Link
                href="/versions"
                onClick={onClose}
                className={cn(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
                  pathname.startsWith("/versions")
                    ? "bg-primary/10 text-primary font-medium"
                    : "text-muted-foreground hover:bg-accent hover:text-foreground",
                )}
              >
                <GitBranch className="h-4 w-4 shrink-0" aria-hidden="true" />
                Versions
              </Link>
            </li>

            {/* Iterations — parent + nested phases */}
            <li>
              <Link
                href="/iterations"
                onClick={onClose}
                className={cn(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
                  onIter
                    ? "bg-primary/10 text-primary font-medium"
                    : "text-muted-foreground hover:bg-accent hover:text-foreground",
                )}
              >
                <List className="h-4 w-4 shrink-0" aria-hidden="true" />
                Iterations
              </Link>

              {/* Nested phases */}
              <ul className="mt-0.5 space-y-0.5 pl-4">
                {PHASES.map((phase) => {
                  const Icon = phase.icon
                  return (
                    <li key={phase.tab}>
                      <Link
                        href={`/loop?tab=${phase.tab}`}
                        onClick={onClose}
                        className={cn(
                          "flex items-center gap-2 rounded-md px-3 py-1.5 text-xs transition-colors",
                          onLoop
                            ? "text-foreground hover:bg-accent"
                            : "text-muted-foreground hover:bg-accent hover:text-foreground",
                        )}
                      >
                        <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                        {phase.label}
                      </Link>
                    </li>
                  )
                })}
              </ul>
            </li>

            {/* Config + Graph */}
            {TOOLS.map((tool) => {
              const Icon   = tool.icon
              const active = pathname.startsWith(tool.href)
              return (
                <li key={tool.href}>
                  <Link
                    href={tool.href}
                    onClick={onClose}
                    className={cn(
                      "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
                      active
                        ? "bg-primary/10 text-primary font-medium"
                        : "text-muted-foreground hover:bg-accent hover:text-foreground",
                    )}
                  >
                    <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
                    {tool.label}
                  </Link>
                </li>
              )
            })}
          </ul>
        </nav>

        <div className="border-t border-border p-4">
          <p className="text-[10px] text-muted-foreground">
            API:{" "}
            <span className="font-mono text-foreground/60">
              {process.env.NEXT_PUBLIC_RT_API_URL ?? "localhost:8090"}
            </span>
          </p>
        </div>
      </aside>
    </>
  )
}
