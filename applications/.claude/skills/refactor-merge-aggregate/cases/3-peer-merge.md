# Case 3 — Peer merge (1:1)

Each aggregate holds a mandatory single-valued replica of the other, and neither is created without
the other. `A` and `B` are in bijection: they are one concept split across two roots.

**No pair in quizzes is in this shape** (composition doc §3 — every O1 relation there is N:1). This
file exists so the classifier has a defined outcome if a future application has one. Verify the
bijection before trusting the classification: a mutual O1 can also mean two independently-created
aggregates that happen to reference each other, in which case there is no ownership and the correct
case is 4.

## Confirming the bijection

All three must hold, each cited with `file:line`:

1. **Mutual O1** — `<AggrA><AggrB>` and `<AggrB><AggrA>` both exist, both single-valued, both
   assigned in the constructor.
2. **Co-creation** — one is never created without the other. In practice: the same functionality
   creates both, in adjacent steps of the same workflow.
3. **No independent creation path** — neither has a `@PostMapping` that creates it alone.

If (2) or (3) fails, this is not a peer merge. Fall through to case 4 and report *ambiguous
ownership*, not a peer merge.

## How it differs from case 1

Run the same four layers, with two changes:

**Direction.** With no owner, the argument order decides: `AggrA` survives as given. Do not swap. Say
so in the log, because unlike case 1 the choice is arbitrary rather than derived:

```
GATE: peer merge — <AggrA> and <AggrB> are in bijection; <AggrA> survives by argument order
```

**Identity (layer 2).** A 1:1 relation is the one shape where `AggrB`'s identifier *can* be dropped
rather than retained: every `AggrB` maps to exactly one `M`, so `M`'s `aggregateId` addresses it
unambiguously. Two options, and the choice must be recorded:

| | Retain `<aggrB>AggregateId` on the member | Re-point external references to `M`'s id |
|---|---|---|
| External references | unchanged — nothing outside `M` is touched | every `<aggrB>AggregateId` in the codebase is rewritten |
| REST paths | `/…/{<aggrB>AggregateId}` keeps answering | paths change — an externally visible behaviour change |
| Cost | one extra column | a codebase-wide rewrite, and a migration for anything holding old ids |

**Default to retaining**, exactly as case 1 does. Re-pointing is only worth it when the third-party
reference count from SKILL.md's first "further reading" is zero, and even then retention is harmless.
This is the one decision where the two cases genuinely differ, so state which you took:

```
LAYER 2 (root join): peer merge — <aggrB>AggregateId retained on the member (<n> external references)
```

Everything else — layers 1, 3 and 4 — is identical to case 1. Read
`cases/1-ownership-merge.md` for the dissolution table and the failure checks, then run the layers.
