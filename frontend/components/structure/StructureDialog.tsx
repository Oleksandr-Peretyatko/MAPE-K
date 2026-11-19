"use client"

import { useEffect, useMemo, useState } from "react"
import {
  AlertCircle, Boxes, ChevronDown, ChevronRight, Clock, Copy, Download,
  Loader2, Plug, Radio, RefreshCw, Search, Shield, Workflow,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { cn } from "@/lib/utils"
import {
  api,
  type AggregateRelation,
  type AggregateStructure,
  type CommandInfo,
  type EntityNode,
  type EventFlow,
  type FunctionalityRef,
  type InterInvariantInfo,
  type InvariantInfo,
  type ProjectStructure,
  type SentCommandRef,
  type WorkflowStepRef,
} from "@/lib/api"

// ─── Cache ────────────────────────────────────────────────────────────────────
// Each call re-reads the version's source tree (working tree or git tree), which costs seconds.
// The structure of a version cannot change unless the version is rebuilt, so holding the answer for
// the life of the tab is safe; the refresh button is there for when it is not.

const cache = new Map<string, ProjectStructure>()

// ─── Reading an invariant's footprint ─────────────────────────────────────────

/**
 * What the rule reads, in the terms that decide where it can live: member entities travel with the
 * entities, root scalars and state pin it to the root, and getPrev() pins it to a single version
 * chain. Rendering only the name would put the reader back to guessing from it, which is what the
 * collector was rewritten to stop doing.
 */
function footprint(inv: InvariantInfo | InterInvariantInfo): string {
  const parts: string[] = []
  if (inv.memberEntities?.length) parts.push(inv.memberEntities.join(", "))
  if (inv.rootScalars?.length) parts.push(`root.${inv.rootScalars.join(", root.")}`)
  if (inv.readsState) parts.push("state")
  return parts.length ? parts.join(" + ") : "no state read"
}

// ─── Inter-invariant row ──────────────────────────────────────────────────────

/**
 * A cross-aggregate rule reads as three things: what it constrains locally, what it listens to, and
 * how much of it is guesswork. A DECLARED rule is a line in the aggregate's header the collector
 * could not tie to any subscription — real often enough (course publishes no events, and the header
 * says so) but never verified, so it is drawn as a claim rather than as a rule.
 */
function InterInvariantRow({ rule, match }: { rule: InterInvariantInfo; match: (s: string) => boolean }) {
  const guarded = rule.kind === "SERVICE_GUARD"
  const declared = rule.kind === "DECLARED"
  // Neither kind has subscriptions; only DECLARED means nothing implements the rule.
  const bySubscription = !guarded && !declared
  const subscriptions = rule.subscriptions ?? []

  return (
    <div className={cn("rounded px-1 py-0.5", match(rule.name) && "bg-yellow-500/15")}>
      <div className="flex flex-wrap items-center gap-1.5">
        <span
          className={cn(
            "font-mono text-[11px]",
            declared ? "text-muted-foreground"
                     : guarded ? "text-sky-600 dark:text-sky-400"
                               : "text-purple-600 dark:text-purple-400"
          )}
        >
          {rule.name}
        </span>
        {guarded && (
          <span
            className="rounded bg-sky-500/15 px-1 text-[9px] font-semibold uppercase text-sky-700 dark:text-sky-400"
            title="Enforced synchronously by a service method that claims it with @ServiceGuard"
          >service guard</span>
        )}
        {declared && (
          <span
            className="rounded bg-muted px-1 text-[9px] font-semibold uppercase text-muted-foreground"
            title="Named in a header block and claimed by no @InterInvariant or @ServiceGuard"
          >declared</span>
        )}
        {/* The domain name the header uses, which the method name need not resemble:
            USER_EXISTS is implemented by interInvariantStudentExists. */}
        {bySubscription && rule.declaredNames?.map(n => (
          <span key={n} className="rounded bg-purple-500/10 px-1 font-mono text-[9px] text-purple-700 dark:text-purple-300">
            {n}
          </span>
        ))}
        {bySubscription && !rule.declaredNames?.length && (
          <span
            className="rounded bg-amber-500/15 px-1 text-[9px] font-semibold uppercase text-amber-700 dark:text-amber-400"
            title="No @InterInvariant annotation: the header rules this method maintains cannot be linked to it"
          >unclaimed</span>
        )}
        {rule.iterated && (
          <span className="rounded bg-muted px-1 text-[9px] uppercase text-muted-foreground"
            title="Ranges over a collection: one subscription per element">∀</span>
        )}
        {rule.conditional && (
          <span className="rounded bg-muted px-1 text-[9px] uppercase text-muted-foreground"
            title="Subscribes only for some elements">cond</span>
        )}
        {bySubscription && rule.guardedByActiveState === false && (
          <span
            className="rounded bg-amber-500/15 px-1 text-[9px] font-semibold uppercase text-amber-700 dark:text-amber-400"
            title="Registered outside if (getState() == ACTIVE): the aggregate keeps subscribing after deletion"
          >unguarded</span>
        )}
      </div>

      {bySubscription ? (
        <>
          <div className="pl-2 font-mono text-[10px] text-muted-foreground">
            constrains: {footprint(rule)}
          </div>
          {subscriptions.map(s => (
            <div key={s.subscriptionClass} className="pl-2 font-mono text-[10px] text-muted-foreground">
              ← {s.eventType}
              {s.publisherAggregate && (
                <span className="text-muted-foreground/70"> from {s.publisherAggregate}</span>
              )}
            </div>
          ))}
        </>
      ) : (
        <>
          {guarded && rule.enforcedIn && (
            <div className="pl-2 font-mono text-[10px] text-muted-foreground">
              enforced in {rule.enforcedIn}
            </div>
          )}
          {rule.note && <div className="pl-2 text-[10px] italic text-muted-foreground">{rule.note}</div>}
        </>
      )}
    </div>
  )
}

// ─── Functionalities and event flow ───────────────────────────────────────────

function FunctionalitySection({
  functionalities, match,
}: {
  functionalities: FunctionalityRef[]
  match: (s: string) => boolean
}) {
  return (
    <div>
      <p className="mb-1.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
        <Workflow className="h-3 w-3" /> Functionalities ({functionalities.length})
      </p>
      {functionalities.length === 0 ? (
        <p className="text-[11px] italic text-muted-foreground">
          No cross-service operation is owned here.
        </p>
      ) : (
        <div className="space-y-0.5">
          {functionalities.map(f => (
            <div
              key={f.className ?? f.operation}
              className={cn("flex flex-wrap items-baseline gap-x-2 text-[11px]",
                match(f.operation) && "bg-yellow-500/15 rounded px-1")}
            >
              <span className="font-mono font-medium text-emerald-600 dark:text-emerald-400">
                {f.operation}
              </span>
              <span className="text-[10px] text-muted-foreground">
                {f.protocol} · {f.stepCount} step{f.stepCount === 1 ? "" : "s"}
              </span>
              {f.touchedAggregates?.length ? (
                <span className="font-mono text-[10px] text-muted-foreground/80">
                  → {f.touchedAggregates.join(", ")}
                </span>
              ) : null}
              {f.httpEndpoint && (
                <span className="font-mono text-[10px] text-blue-600/80 dark:text-blue-400/80">
                  {f.httpMethod} {f.httpEndpoint}
                </span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Publisher and consumer sides, kept apart.
 *
 * The publisher on a `consumes` row is read from the `registerEvent` call site; when no service
 * registers the event it is left blank rather than filled in from the subscription's anchor, which
 * answers a different question.
 */
function EventFlowSection({
  publishes, consumes, match,
}: {
  publishes: EventFlow[]
  consumes: EventFlow[]
  match: (s: string) => boolean
}) {
  return (
    <div>
      <p className="mb-1.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
        <Radio className="h-3 w-3" /> Event flow ({publishes.length} out / {consumes.length} in)
      </p>
      {publishes.length === 0 && consumes.length === 0 ? (
        <p className="text-[11px] italic text-muted-foreground">
          This aggregate neither publishes nor consumes events.
        </p>
      ) : (
        <div className="space-y-0.5">
          {publishes.map(e => (
            <div key={`p-${e.eventType}`}
              className={cn("flex flex-wrap items-baseline gap-x-2 text-[11px]",
                match(e.eventType) && "bg-yellow-500/15 rounded px-1")}>
              <span className="w-4 shrink-0 text-muted-foreground" title="publishes">→</span>
              <span className="font-mono text-purple-600 dark:text-purple-400">{e.eventType}</span>
              {e.consumerAggregates?.length ? (
                <span className="font-mono text-[10px] text-muted-foreground">
                  to {e.consumerAggregates.join(", ")}
                </span>
              ) : (
                <span className="text-[10px] italic text-amber-700 dark:text-amber-400"
                  title="Published, but no aggregate subscribes to it">
                  no consumer
                </span>
              )}
              {e.publishedIn && (
                <span className="font-mono text-[10px] text-muted-foreground/70">{e.publishedIn}</span>
              )}
            </div>
          ))}
          {consumes.map(e => (
            <div key={`c-${e.eventType}`}
              className={cn("flex flex-wrap items-baseline gap-x-2 text-[11px]",
                match(e.eventType) && "bg-yellow-500/15 rounded px-1")}>
              <span className="w-4 shrink-0 text-muted-foreground" title="consumes">←</span>
              <span className="font-mono text-purple-600 dark:text-purple-400">{e.eventType}</span>
              {e.publisherAggregate ? (
                <span className="font-mono text-[10px] text-muted-foreground">
                  from {e.publisherAggregate}
                </span>
              ) : (
                <span className="text-[10px] italic text-amber-700 dark:text-amber-400"
                  title="No service registers this event; the publisher is unknown, not guessed">
                  no publisher found
                </span>
              )}
              {e.publishedIn && (
                <span className="font-mono text-[10px] text-muted-foreground/70">{e.publishedIn}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * The synchronous seam, both halves at once: what the aggregate accepts, and who sends it.
 *
 * A command with no sender is a surface nothing calls; a sender the target has no case for is a
 * step that silently does nothing. Neither breaks the build, so neither shows up anywhere else.
 */
function CommandSection({
  commands, match,
}: {
  commands: CommandInfo[]
  match: (s: string) => boolean
}) {
  return (
    <div>
      <p className="mb-1.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
        <Plug className="h-3 w-3" /> Commands ({commands.length})
      </p>
      {commands.length === 0 ? (
        <p className="text-[11px] italic text-muted-foreground">
          This aggregate accepts no command.
        </p>
      ) : (
        <div className="space-y-1">
          {commands.map(c => (
            <div key={c.name} className={cn("rounded px-1 py-0.5", match(c.name) && "bg-yellow-500/15")}>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="font-mono text-[11px] text-indigo-600 dark:text-indigo-400">{c.name}</span>
                {c.serviceMethod && (
                  <span className="font-mono text-[10px] text-muted-foreground">→ {c.serviceMethod}</span>
                )}
                {!c.sentBy?.length && (
                  <span
                    className="rounded bg-amber-500/15 px-1 text-[9px] font-semibold uppercase text-amber-700 dark:text-amber-400"
                    title="No functionality constructs this command: a surface nothing calls"
                  >no sender</span>
                )}
              </div>
              {c.sentBy?.map(s => (
                <div key={s.functionality} className="pl-2 font-mono text-[10px] text-muted-foreground">
                  ← {s.functionality.replace(/Functionality(Sagas)?$/, "")}
                  {s.fromAggregate && <span className="text-muted-foreground/70"> ({s.fromAggregate})</span>}
                  {s.access && <span className="text-muted-foreground/70"> · {s.access.toLowerCase()}</span>}
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}


// ─── The workflow, step by step ───────────────────────────────────────────────

/** One command a step sends, in the terms a refactoring has to re-point it with. */
function CommandLine({ c, match, compensating }: {
  c: SentCommandRef
  match: (s: string) => boolean
  compensating?: boolean
}) {
  return (
    <div className={cn("flex flex-wrap items-baseline gap-x-2 text-[10px]",
      match(c.name) && "rounded bg-yellow-500/15 px-1")}>
      <span className="font-mono text-indigo-600 dark:text-indigo-400">{c.name}</span>
      {c.toAggregate && (
        <span className="font-mono text-muted-foreground">→ {c.toAggregate}</span>
      )}
      {c.access && (
        <span className="text-muted-foreground/70">{c.access.toLowerCase()}</span>
      )}
      {/* The base Command class carries no entity: it exists so SagaCommandHandler can put the
          semantic lock back. Every handler's default arm ignores it, and that is correct. */}
      {compensating && c.name === "Command" && (
        <span className="italic text-muted-foreground/70">releases the lock</span>
      )}
      {c.unhandled && (
        <span
          className="rounded bg-red-500/15 px-1 text-[9px] font-semibold uppercase text-red-600 dark:text-red-400"
          title="No handler in the target service has a case for this command: at runtime the step silently does nothing"
        >unhandled</span>
      )}
    </div>
  )
}

/**
 * The steps, grouped by the wave that actually orders them.
 *
 * The index says where the step is written; the wave says when it can run. Steps in one wave have
 * no order between them — `SagaWorkflow.planOrder()` topologically sorts the dependency graph and
 * takes ready steps in whatever order a HashMap yields — so a flat 1..N list would assert a
 * sequence the engine never promised, and would make a legal reordering look like a regression.
 */
function StepList({ steps, stepCount, match }: {
  steps: WorkflowStepRef[]
  stepCount?: number
  match: (s: string) => boolean
}) {
  const waves = Array.from(new Set(steps.map(s => s.wave))).sort((a, b) => a - b)

  return (
    <div className="space-y-2">
      {typeof stepCount === "number" && stepCount !== steps.length && (
        <p className="text-[10px] italic text-amber-700 dark:text-amber-400">
          {steps.length} of {stepCount} step constructions parsed — the rest use a shape the reader
          does not recognise.
        </p>
      )}
      {waves.map(w => (
        <div key={w}>
          <p className="mb-0.5 text-[9px] font-semibold uppercase tracking-widest text-muted-foreground/70">
            wave {w + 1}
            {steps.filter(s => s.wave === w).length > 1 && (
              <span className="ml-1.5 font-normal normal-case tracking-normal italic">
                — unordered between themselves
              </span>
            )}
          </p>
          <div className="space-y-1.5 border-l border-border/60 pl-2">
            {steps.filter(s => s.wave === w).map(s => (
              <div key={s.name}>
                <div className="flex flex-wrap items-baseline gap-x-2">
                  <span className="w-4 shrink-0 text-right font-mono text-[10px] text-muted-foreground/60">
                    {s.index}
                  </span>
                  <span className={cn("font-mono text-[11px] font-medium",
                    match(s.name) && "rounded bg-yellow-500/15 px-1")}>
                    {s.name}
                  </span>
                  {s.dependsOn?.length ? (
                    <span className="font-mono text-[10px] text-muted-foreground/70">
                      after {s.dependsOn.join(", ")}
                    </span>
                  ) : null}
                  {s.registered === false && (
                    <span
                      className="rounded bg-amber-500/15 px-1 text-[9px] font-semibold uppercase text-amber-700 dark:text-amber-400"
                      title="No workflow.addStep(…) names this step: it is constructed and never runs"
                    >not registered</span>
                  )}
                  {!s.sends?.length && (
                    <span className="text-[10px] italic text-muted-foreground/60">
                      no command — local work only
                    </span>
                  )}
                </div>
                <div className="space-y-0.5 pl-6">
                  {s.sends?.map((c, i) => (
                    <CommandLine key={`${c.name}-${i}`} c={c} match={match} />
                  ))}
                </div>
                {s.compensates?.length ? (
                  <div className="pl-6">
                    <p className="text-[10px] text-muted-foreground/70">↩ compensation</p>
                    <div className="space-y-0.5 pl-3">
                      {s.compensates.map((c, i) => (
                        <CommandLine key={`k-${c.name}-${i}`} c={c} match={match} compensating />
                      ))}
                    </div>
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

/** A functionality with the aggregate(s) whose card it was read from. */
type OwnedFunctionality = FunctionalityRef & { owners: string[] }

function FunctionalityCard({
  f, open, onToggle, match,
}: {
  f: OwnedFunctionality
  open: boolean
  onToggle: () => void
  match: (s: string) => boolean
}) {
  const steps = f.steps ?? []
  const unhandled = steps.flatMap(s => s.sends ?? []).filter(c => c.unhandled).length

  return (
    <div className="rounded-lg border border-border bg-card">
      <button
        onClick={onToggle}
        className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-left hover:bg-muted/40"
      >
        {open ? <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              : <ChevronRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />}
        <span className={cn("font-mono text-[13px] font-semibold text-emerald-600 dark:text-emerald-400",
          match(f.operation) && "rounded bg-yellow-500/20 px-1")}>
          {f.operation}
        </span>
        <span className="text-[11px] text-muted-foreground">
          {f.owners.join(", ")} · {f.protocol} · {f.stepCount ?? steps.length}
          {" "}step{(f.stepCount ?? steps.length) === 1 ? "" : "s"}
        </span>
        {f.httpEndpoint && (
          <span className="font-mono text-[10px] text-blue-600/80 dark:text-blue-400/80">
            {f.httpMethod} {f.httpEndpoint}
          </span>
        )}
        <span className="ml-auto flex items-center gap-2 text-[10px]">
          {f.touchedAggregates?.length ? (
            <span className="font-mono text-muted-foreground/80">
              → {f.touchedAggregates.join(", ")}
            </span>
          ) : (
            <span className="italic text-muted-foreground/60">local to its aggregate</span>
          )}
          {unhandled > 0 && (
            <span className="rounded bg-red-500/15 px-1 text-[9px] font-semibold uppercase text-red-600 dark:text-red-400">
              {unhandled} unhandled
            </span>
          )}
        </span>
      </button>

      {open && (
        <div className="space-y-2 border-t border-border px-3 py-2.5">
          {steps.length === 0 ? (
            <p className="text-[11px] italic text-muted-foreground">
              No step was read from this workflow. The commands it sends are still listed below.
            </p>
          ) : (
            <StepList steps={steps} stepCount={f.stepCount} match={match} />
          )}

          {steps.length === 0 && f.sendsCommands?.length ? (
            <div className="space-y-0.5">
              {f.sendsCommands.map((c, i) => (
                <CommandLine key={`${c.name}-${i}`} c={c} match={match} />
              ))}
            </div>
          ) : null}

          {f.looseCommands?.length ? (
            <div className="rounded border border-amber-500/30 bg-amber-500/5 p-2">
              <p className="mb-1 text-[10px] font-semibold text-amber-700 dark:text-amber-400">
                Outside every step
              </p>
              <div className="space-y-0.5">
                {f.looseCommands.map((c, i) => (
                  <CommandLine key={`l-${c.name}-${i}`} c={c} match={match} />
                ))}
              </div>
            </div>
          ) : null}
        </div>
      )}
    </div>
  )
}

// ─── Entity tree ──────────────────────────────────────────────────────────────

function EntityTree({ node, depth = 0, match }: { node: EntityNode; depth?: number; match: (s: string) => boolean }) {
  const hit = match(node.name)
  return (
    <div>
      <div
        className={cn("flex items-center gap-1.5 py-0.5 font-mono text-[11px]", hit && "bg-yellow-500/15 rounded px-1")}
        style={{ paddingLeft: depth * 14 }}
      >
        {depth > 0 && <span className="text-muted-foreground/50">└</span>}
        <span className={depth === 0 ? "font-semibold text-foreground" : "text-foreground/90"}>
          {node.name}
        </span>
        {node.replicaOf && (
          <span
            className="rounded bg-amber-500/15 px-1 py-px text-[9px] font-semibold uppercase text-amber-700 dark:text-amber-400"
            title="Declared with @Replica — the aggregate root this class holds a copy of"
          >
            replica → {node.replicaOf}
          </span>
        )}
      </div>
      {node.children?.map(c => (
        <EntityTree key={c.name} node={c} depth={depth + 1} match={match} />
      ))}
    </div>
  )
}

// ─── Relation row ─────────────────────────────────────────────────────────────

function RelationRow({
  rel, direction, show,
}: {
  rel: AggregateRelation
  direction: "out" | "in"
  show: { structural: boolean; events: boolean; sagas: boolean }
}) {
  const bits: React.ReactNode[] = []
  if (show.structural && rel.structuralRefs > 0) {
    bits.push(
      <span key="s" className="text-blue-600 dark:text-blue-400" title={rel.viaEntities?.join(", ")}>
        σ{rel.structuralRefs}
        {rel.viaEntities?.length ? ` (${rel.viaEntities.join(", ")})` : ""}
      </span>
    )
  }
  if (show.events && rel.events?.length) {
    bits.push(
      <span key="e" className="text-purple-600 dark:text-purple-400">
        {rel.events.length} event{rel.events.length > 1 ? "s" : ""}: {rel.events.join(", ")}
      </span>
    )
  }
  if (show.sagas && rel.functionalities?.length) {
    bits.push(
      <span key="f" className="text-emerald-600 dark:text-emerald-400">
        {rel.functionalities.length} saga{rel.functionalities.length > 1 ? "s" : ""}
      </span>
    )
  }
  if (bits.length === 0) return null

  return (
    <div className="flex items-baseline gap-2 py-0.5 text-[11px]">
      <span className="w-4 shrink-0 text-muted-foreground">{direction === "out" ? "→" : "←"}</span>
      <span className="w-32 shrink-0 truncate font-mono font-medium">{rel.toAggregate}</span>
      <span className="flex flex-wrap gap-x-3 gap-y-0.5">{bits}</span>
    </div>
  )
}

// ─── Aggregate card ───────────────────────────────────────────────────────────

function AggregateCard({
  agg, open, onToggle, query, show,
}: {
  agg: AggregateStructure
  open: boolean
  onToggle: () => void
  query: string
  show: { structural: boolean; events: boolean; sagas: boolean }
}) {
  const match = (s: string) => query.length > 0 && s.toLowerCase().includes(query)
  const invariants = agg.intraInvariants ?? []
  const interInvariants = agg.interInvariants ?? []
  // The header badge counts only rules a subscription maintains: that is what the "inter" dimension
  // costs to move. A service guard is enforced elsewhere and a DECLARED rule by nothing at all.
  const bySubscription = interInvariants.filter(
    r => r.kind !== "DECLARED" && r.kind !== "SERVICE_GUARD")
  const outgoing = agg.outgoing ?? []
  const incoming = agg.incoming ?? []
  const functionalities = agg.functionalities ?? []
  const publishes = agg.publishes ?? []
  const consumes = agg.consumes ?? []
  const commands = agg.commands ?? []

  return (
    <div className="rounded-lg border border-border bg-card">
      <button
        onClick={onToggle}
        className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-muted/40"
      >
        {open ? <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
              : <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />}
        <span className={cn("font-mono text-sm font-semibold", match(agg.name) && "bg-yellow-500/20 rounded px-1")}>
          {agg.name}
        </span>
        <span className="text-xs text-muted-foreground">{agg.serviceName}</span>
        <span className="ml-auto flex items-center gap-3 text-[11px] text-muted-foreground">
          <span className="flex items-center gap-1" title="Entities">
            <Boxes className="h-3 w-3" />{agg.entityCount}
          </span>
          <span className="flex items-center gap-1" title="Intra-invariants">
            <Shield className="h-3 w-3" />{invariants.length}
          </span>
          <span className="flex items-center gap-1" title="Inter-invariants held by subscription">
            <Radio className="h-3 w-3" />{bySubscription.length}
          </span>
          <span className="flex items-center gap-1" title="Related aggregates">
            <Copy className="h-3 w-3" />{outgoing.length + incoming.length}
          </span>
        </span>
      </button>

      {open && (
        <div className="grid gap-4 border-t border-border px-3 py-3 lg:grid-cols-3">
          {/* Entities */}
          <div>
            <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
              Entities ({agg.entityCount})
            </p>
            <EntityTree node={agg.root} match={match} />
          </div>

          {/* Consistency rules — both kinds, because an aggregate can have none of one and several
              of the other. QuizAnswer's verifyInvariants() is empty and it maintains three rules. */}
          <div className="space-y-3">
            <div>
              <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
                Intra-invariants ({invariants.length})
              </p>
              {invariants.length === 0 ? (
                <p className="text-[11px] italic text-muted-foreground">
                  No P1 rule: <code className="font-mono">verifyInvariants()</code> is empty.
                </p>
              ) : (
                <div className="space-y-1">
                  {invariants.map(inv => (
                    <div key={inv.name} className={cn("rounded px-1 py-0.5", match(inv.name) && "bg-yellow-500/15")}>
                      <div className="flex items-center gap-1.5">
                        <span className="font-mono text-[11px] text-blue-600 dark:text-blue-400">{inv.name}</span>
                        {inv.kind === "INLINE" && (
                          <span className="rounded bg-muted px-1 text-[9px] uppercase text-muted-foreground">inline</span>
                        )}
                        {inv.readsPrev && (
                          <span
                            className="rounded bg-red-500/15 px-1 text-[9px] font-semibold uppercase text-red-600 dark:text-red-400"
                            title="Compares against getPrev(): cannot span a boundary, only be duplicated"
                          >prev</span>
                        )}
                      </div>
                      <div className="pl-2 font-mono text-[10px] text-muted-foreground">
                        reads: {footprint(inv)}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="border-t border-border/60 pt-2.5">
              <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
                Inter-invariants ({bySubscription.length})
              </p>
              {interInvariants.length === 0 ? (
                <p className="text-[11px] italic text-muted-foreground">
                  Nothing outside this aggregate constrains it:{" "}
                  <code className="font-mono">getEventSubscriptions()</code> registers nothing.
                </p>
              ) : (
                <div className="space-y-1">
                  {interInvariants.map(rule => (
                    <InterInvariantRow key={`${rule.kind}-${rule.name}`} rule={rule} match={match} />
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Relations */}
          <div>
            <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
              Relations
            </p>
            {outgoing.length === 0 && incoming.length === 0 ? (
              <p className="text-[11px] italic text-muted-foreground">No dependency on other aggregates.</p>
            ) : (
              <div className="space-y-0.5">
                {outgoing.map(r => <RelationRow key={`o-${r.toService}`} rel={r} direction="out" show={show} />)}
                {incoming.map(r => <RelationRow key={`i-${r.toService}`} rel={r} direction="in" show={show} />)}
              </div>
            )}
            {agg.relationsAreServiceWide && (
              <p className="mt-1.5 text-[10px] italic text-muted-foreground">
                The service holds several roots. Coupling is measured per service, so these edges are
                the service&apos;s, not this aggregate&apos;s alone.
              </p>
            )}
          </div>

          {/* Full width: both lists run long, and reading an operation next to the events it emits
              is the point — a narrow column would wrap every line. */}
          <div className="lg:col-span-3 grid gap-4 border-t border-border/60 pt-3 lg:grid-cols-3">
            <FunctionalitySection functionalities={functionalities} match={match} />
            <EventFlowSection publishes={publishes} consumes={consumes} match={match} />
            <CommandSection commands={commands} match={match} />
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Dialog ───────────────────────────────────────────────────────────────────

export function StructureDialog({
  versionId, versionLabel, open, onOpenChange,
}: {
  versionId: string | null
  versionLabel?: string
  open: boolean
  onOpenChange: (v: boolean) => void
}) {
  const [structure, setStructure] = useState<ProjectStructure | null>(null)
  const [loading, setLoading]     = useState(false)
  const [error, setError]         = useState<string | null>(null)
  const [query, setQuery]         = useState("")
  const [expanded, setExpanded]   = useState<Set<string>>(new Set())
  const [openFuncs, setOpenFuncs] = useState<Set<string>>(new Set())
  const [tab, setTab]             = useState("aggregates")
  const [show, setShow]           = useState({ structural: true, events: true, sagas: true })

  async function load(force = false) {
    if (!versionId) return
    if (!force && cache.has(versionId)) {
      setStructure(cache.get(versionId)!)
      setError(null)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const s = await api.knowledge.versions.structure(versionId)
      cache.set(versionId, s)
      setStructure(s)
    } catch (e: unknown) {
      setStructure(null)
      setError(e instanceof Error ? e.message : "Could not read the structure")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (open) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, versionId])

  /**
   * Saves what is on screen, not a fresh read. The endpoint re-reads the version's source tree on
   * every call, so a re-fetch could hand back something the reader never saw.
   */
  function downloadJson() {
    if (!structure) return
    const blob = new Blob([JSON.stringify(structure, null, 2)], { type: "application/json" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = `structure-${structure.versionLabel ?? versionId}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  const q = query.trim().toLowerCase()

  const visible = useMemo(() => {
    const all = structure?.aggregates ?? []
    if (!q) return all
    const hits = (agg: AggregateStructure) => {
      if (agg.name.toLowerCase().includes(q) || agg.serviceName.toLowerCase().includes(q)) return true
      if ((agg.intraInvariants ?? []).some(i => i.name.toLowerCase().includes(q))) return true
      if ((agg.interInvariants ?? []).some(i =>
        i.name.toLowerCase().includes(q)
        || (i.subscriptions ?? []).some(s => s.eventType.toLowerCase().includes(q)))) return true
      if ((agg.functionalities ?? []).some(f => f.operation.toLowerCase().includes(q))) return true
      if ((agg.commands ?? []).some(c => c.name.toLowerCase().includes(q))) return true
      if ([...(agg.publishes ?? []), ...(agg.consumes ?? [])]
        .some(e => e.eventType.toLowerCase().includes(q))) return true
      const inTree = (n: EntityNode): boolean =>
        n.name.toLowerCase().includes(q) || (n.children ?? []).some(inTree)
      return inTree(agg.root)
    }
    return all.filter(hits)
  }, [structure, q])

  // A search that narrows to a handful should show them open; browsing should not force it.
  useEffect(() => {
    if (q && visible.length > 0 && visible.length <= 3) {
      setExpanded(new Set(visible.map(a => a.name)))
    }
  }, [q, visible])

  function toggle(name: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name); else next.add(name)
      return next
    })
  }

  function toggleFunc(className: string) {
    setOpenFuncs(prev => {
      const next = new Set(prev)
      if (next.has(className)) next.delete(className); else next.add(className)
      return next
    })
  }

  /**
   * The same functionalities the aggregate cards carry, read the other way round.
   *
   * Nothing new is fetched: a functionality is already nested under the aggregate that owns it. A
   * service with several roots repeats it on each, so the merge keys on the class name and keeps
   * the owners rather than picking one.
   */
  const functionalities = useMemo(() => {
    const byClass = new Map<string, OwnedFunctionality>()
    for (const agg of structure?.aggregates ?? []) {
      for (const f of agg.functionalities ?? []) {
        const key = f.className ?? f.operation
        const existing = byClass.get(key)
        if (existing) existing.owners.push(agg.name)
        else byClass.set(key, { ...f, owners: [agg.name] })
      }
    }
    return Array.from(byClass.values()).sort((a, b) => a.operation.localeCompare(b.operation))
  }, [structure])

  const visibleFuncs = useMemo(() => {
    if (!q) return functionalities
    const hits = (f: OwnedFunctionality) =>
      f.operation.toLowerCase().includes(q)
      || f.owners.some(o => o.toLowerCase().includes(q))
      || (f.touchedAggregates ?? []).some(a => a.toLowerCase().includes(q))
      || (f.steps ?? []).some(s =>
        s.name.toLowerCase().includes(q)
        || [...(s.sends ?? []), ...(s.compensates ?? [])]
          .some(c => c.name.toLowerCase().includes(q)
            || (c.toAggregate ?? "").toLowerCase().includes(q)))
      || (f.sendsCommands ?? []).some(c => c.name.toLowerCase().includes(q))
    return functionalities.filter(hits)
  }, [functionalities, q])

  useEffect(() => {
    if (q && visibleFuncs.length > 0 && visibleFuncs.length <= 3) {
      setOpenFuncs(new Set(visibleFuncs.map(f => f.className ?? f.operation)))
    }
  }, [q, visibleFuncs])

  const workflowTotals = useMemo(() => {
    const steps = functionalities.flatMap(f => f.steps ?? [])
    return {
      steps: steps.length,
      unhandled: steps.flatMap(s => s.sends ?? []).filter(c => c.unhandled).length,
    }
  }, [functionalities])

  const totals = useMemo(() => {
    const aggs = structure?.aggregates ?? []
    return {
      aggregates: aggs.length,
      entities: aggs.reduce((n, a) => n + a.entityCount, 0),
      intra: aggs.reduce((n, a) => n + (a.intraInvariants?.length ?? 0), 0),
      inter: aggs.reduce(
        (n, a) => n + (a.interInvariants ?? [])
          .filter(r => r.kind !== "DECLARED" && r.kind !== "SERVICE_GUARD").length, 0),
    }
  }, [structure])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[88vh] w-[95vw] max-w-6xl flex-col gap-3 overflow-hidden p-0">
        <DialogHeader className="border-b border-border px-4 pb-3 pt-4">
          <DialogTitle className="flex flex-wrap items-center gap-2 text-base">
            Project structure
            {structure && (
              <>
                <span className="font-mono text-sm text-muted-foreground">{structure.versionLabel}</span>
                <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                  {structure.resolvedFrom}
                </span>
              </>
            )}
            {!loading && (
              <span className="ml-auto flex items-center gap-1">
                {/* Exports the object already on screen rather than re-fetching: /structure re-reads
                    the source tree on every call, so a second read could disagree with the view. */}
                <Button
                  variant="ghost" size="sm" className="h-7 gap-1.5 text-xs"
                  disabled={!structure} onClick={downloadJson}
                  title="Download this view as JSON"
                >
                  <Download className="h-3.5 w-3.5" /> Export JSON
                </Button>
                <Button variant="ghost" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => load(true)}>
                  <RefreshCw className="h-3.5 w-3.5" /> Re-read
                </Button>
              </span>
            )}
          </DialogTitle>
        </DialogHeader>

        <Tabs value={tab} onValueChange={setTab} className="flex min-h-0 flex-1 flex-col gap-3">
          {/* Toolbar */}
          <div className="flex flex-wrap items-center gap-3 px-4">
            <TabsList className="h-8">
              <TabsTrigger value="aggregates" className="h-6 px-2.5 text-xs">
                Aggregates {structure ? `(${totals.aggregates})` : ""}
              </TabsTrigger>
              <TabsTrigger value="functionalities" className="h-6 px-2.5 text-xs">
                Functionalities {structure ? `(${functionalities.length})` : ""}
              </TabsTrigger>
            </TabsList>
            <div className="relative flex-1 min-w-[200px]">
              <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
              <input
                value={query}
                onChange={e => setQuery(e.target.value)}
                placeholder={tab === "aggregates"
                  ? "Aggregate, entity, invariant or event…"
                  : "Operation, step, command or target aggregate…"}
                className="h-8 w-full rounded border border-border bg-background pl-7 pr-2 text-xs outline-none focus:border-ring"
              />
            </div>
            {/* The three dimensions belong to the relation rows, which only the aggregate view draws. */}
            {tab === "aggregates" && ([
              ["structural", "σ structural", "text-blue-600 dark:text-blue-400"],
              ["events", "events", "text-purple-600 dark:text-purple-400"],
              ["sagas", "sagas", "text-emerald-600 dark:text-emerald-400"],
            ] as const).map(([key, label, color]) => (
              <button
                key={key}
                onClick={() => setShow(s => ({ ...s, [key]: !s[key] }))}
                className={cn(
                  "rounded border px-2 py-1 text-[11px] font-medium transition-colors",
                  show[key] ? `border-border bg-muted/60 ${color}` : "border-border/60 text-muted-foreground/60"
                )}
              >
                {label}
              </button>
            ))}
            {structure && (
              <span className="ml-auto text-[11px] text-muted-foreground">
                {tab === "aggregates" ? (
                  <>
                    {totals.aggregates} aggregates · {totals.entities} entities ·{" "}
                    {totals.intra} intra · {totals.inter} inter
                  </>
                ) : (
                  <>
                    {functionalities.length} functionalities · {workflowTotals.steps} steps
                    {workflowTotals.unhandled > 0 && (
                      <span className="text-red-600 dark:text-red-400">
                        {" "}· {workflowTotals.unhandled} unhandled
                      </span>
                    )}
                  </>
                )}
              </span>
            )}
          </div>

          {/* Body */}
          <div className="flex-1 space-y-2 overflow-y-auto px-4 pb-4">
            {loading && (
              <div className="flex items-center gap-2 py-10 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                Reading the version&apos;s source tree…
              </div>
            )}

            {error && (
              <div className="flex items-start gap-2 rounded border border-destructive/30 bg-destructive/5 p-3">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
                <p className="text-xs text-destructive">{error}</p>
              </div>
            )}

            <TabsContent value="aggregates" className="mt-0 space-y-2">
              {!loading && !error && structure && visible.length === 0 && (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  {q ? `Nothing matches “${query}”.` : "The analysis found no aggregates in this version."}
                </p>
              )}

              {!loading && visible.map(agg => (
                <AggregateCard
                  key={`${agg.serviceName}-${agg.name}`}
                  agg={agg}
                  open={expanded.has(agg.name)}
                  onToggle={() => toggle(agg.name)}
                  query={q}
                  show={show}
                />
              ))}

              {!loading && structure?.orphanEntities && (
                <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
                  <p className="mb-1 text-[11px] font-semibold text-amber-700 dark:text-amber-400">
                    Entities outside the composition forest
                  </p>
                  <p className="mb-2 text-[10px] text-muted-foreground">
                    No root reaches them: either they have no owner, or the collector missed the relation.
                  </p>
                  {Object.entries(structure.orphanEntities).map(([svc, names]) => (
                    <div key={svc} className="text-[11px]">
                      <span className="text-muted-foreground">{svc}:</span>{" "}
                      <span className="font-mono">{names.join(", ")}</span>
                    </div>
                  ))}
                </div>
              )}

              {structure && (
                <p className="flex items-center gap-1.5 pt-1 text-[10px] text-muted-foreground">
                  <Clock className="h-3 w-3" />
                  Static analysis of {structure.resolvedFrom}, read at {new Date(structure.collectedAt).toLocaleTimeString()}.
                  Intra-invariants are the P1 rules reachable from <code className="font-mono">verifyInvariants()</code>;
                  inter-invariants are the rules registered in <code className="font-mono">getEventSubscriptions()</code>.
                  Service-layer guards and rules held by construction appear in neither.
                </p>
              )}
            </TabsContent>

            <TabsContent value="functionalities" className="mt-0 space-y-1.5">
              {!loading && !error && structure && visibleFuncs.length === 0 && (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  {q ? `Nothing matches “${query}”.`
                     : "No cross-service operation was read from this version."}
                </p>
              )}

              {!loading && visibleFuncs.map(f => (
                <FunctionalityCard
                  key={f.className ?? f.operation}
                  f={f}
                  open={openFuncs.has(f.className ?? f.operation)}
                  onToggle={() => toggleFunc(f.className ?? f.operation)}
                  match={(s: string) => q.length > 0 && s.toLowerCase().includes(q)}
                />
              ))}

              {structure && visibleFuncs.length > 0 && (
                <p className="flex items-start gap-1.5 pt-1 text-[10px] text-muted-foreground">
                  <Clock className="h-3 w-3 shrink-0 translate-y-0.5" />
                  <span>
                    Steps are numbered in declaration order, and grouped by the wave that orders them:
                    the engine sorts the dependency graph topologically, so steps in one wave run in no
                    fixed order relative to each other.
                  </span>
                </p>
              )}
            </TabsContent>
          </div>
        </Tabs>
      </DialogContent>
    </Dialog>
  )
}
