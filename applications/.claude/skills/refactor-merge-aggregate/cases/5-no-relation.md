# Case 5 — No relation at all

Neither aggregate owns the other **and their owner chains never meet** — there is no common ancestor,
so case 4's decomposition has nothing to work with. `MergeAggregates(AggrA, AggrB -> M)` is **not
applicable** to this pair, in any order.

This is the only genuine refusal, and it is a domain fact rather than a limit of the catalogue: with
no cardinality binding an instance of one aggregate to an instance of the other, "one aggregate
containing both" has no structural meaning. In quizzes it is only `User` paired with anything — User
owns nothing, is owned by nothing, and is reached solely through role-named replicas
(`creatorAggregateId`, `studentAggregateId`, `participantAggregateId`).

There is no variant of the operation that preserves behaviour here, so there is nothing to fall back
to. Co-hosting the two aggregates in one process is a *service* merge: it leaves two aggregate roots,
contradicts the *Completeness contract*, and would be recorded by the pipeline as a
`MERGE_AGGREGATES` that never happened — poisoning what Monitor measures and what Knowledge learns.
**Do not do it as a consolation.**

## Action

- **Make no code changes at all.** Do not move packages, do not touch the compose file, do not
  collapse anything. The only file you write is `CLAUDE.md`.
- Report `Result: NO_CHANGE`.
- Emit the `GATE:` verdicts and the restriction block below. **No chain** — unlike case 4 there is no
  sequence that would make this pair mergeable.
- Emit **no** `Topology:`, no `Impact:`, and no `LAYER n:` lines — nothing was merged.

Reporting the operation as inapplicable is the correct, useful outcome. The loop selects a different
candidate on the next iteration.

## Say why, in the code's terms

The verdict must name what is true in *this* codebase, with `file:line`. Two phrasings are wrong and
must not appear, because they are true of every aggregate in the application and therefore say
nothing:

| Do not write | Why it is wrong |
|---|---|
| "`AggrB` is created by its own `<svcB>` service" | Every aggregate is. `<svcB>` is what the merge dissolves. |
| "`AggrB` is loaded by its own `<aggrB>AggregateId`" | Every aggregate is. Retaining that identifier is layer 2's job, not an obstacle. |

Reaching this case means you have already walked both owner chains and found no intersection, so say
that — naming both chains is what distinguishes a true refusal from a case-4 decomposition:

```
GATE: O1 not satisfied — <AggrB> declares no single-valued member holding <aggrA>AggregateId;
      its only @OneToOne member is <member class> (<file:line>), which holds <owner>AggregateId,
      so <AggrB>'s owner is <Owner>
GATE: owner chains do not intersect — <AggrA>: [<chain or none>], <AggrB>: [<chain or none>]
GATE: MergeAggregates(<AggrA>, <AggrB>) not applicable — no relation binds an instance of one to an
      instance of the other
```

When the two are linked only by a **collection** replica, say so explicitly — that is the shape that
most looks like ownership and is not:

```
GATE: O1 not satisfied — <AggrA> holds Set<<AggrA><AggrB>>, a many-to-many collection replica,
      not a single-valued parent link (<file:line>)
```

## There is no follow-up

Case 4 ends by naming a chain; this one cannot. If some *third* aggregate owned `AggrB`, the owner
chains would have intersected and the classifier would have routed to `4-sibling-merge.md`. Reaching
here means no aggregate owns `AggrB`, no aggregate owns `AggrA`, and nothing connects them.

Say so explicitly rather than leaving it implied:

```
GATE: no follow-up — <AggrB> is a root with no owning aggregate, so no chain reaches this pair
```

In quizzes this is only `User`. Every other aggregate sits in the tree rooted at Course
(`../.claude/docs/concepts/aggregate-merge.md` §5, from the working directory), so every other pair decomposes.

## Restriction block

A failed classification is a permanent property of the domain, not a transient one: re-running the
same merge classifies identically. Emit a machine-readable block so the pipeline records it as an
architect restriction and stops proposing the pair. Place it inside the `CLAUDE.md` log entry, after
the `GATE:` lines:

```
<!-- REFACTOR_RESTRICTION -->
operation: MERGE_AGGREGATES
targetService: <svcA>
secondaryTarget: <svcB>
blocking: true
reason: <one sentence naming the architectural fact>
<!-- END_REFACTOR_RESTRICTION -->
```

`reason` states the **architectural fact**, not the mechanical check — it is what a human reads in
the Config page months later. For `question user`:

> *"User is an aggregate root with no owner and owns no aggregate; it is reached only through
> role-named replicas (creator, student, participant). No cardinality binds a Question instance to a
> User instance, so there is no aggregate that could contain both."*

Not *"O1 failed"*. Keep it under 512 characters; the `file:line` evidence belongs in the `GATE:`
lines. Emit exactly one block — the pipeline stores both orderings itself, and turns it into two
blocking `refactor_constraint` rows scoped to this project.
