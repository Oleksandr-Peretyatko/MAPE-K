# Case 2 — Inverted direction

The classifier found an ownership path, but it runs the other way: `AggrB` owns `AggrA`.

`merge-aggregate topic course` is the canonical instance. Course owns Topic (composition doc §3), so
Topic cannot absorb Course — but the *pair* is mergeable.

## Action

**Swap the operands and re-enter case 1.**

This is not an argument error. The operation is agnostic to argument order (SKILL.md, *Ownership
Classifier*): the order expresses a **preference for which root survives**, and here the domain cannot
satisfy the preference — only one direction of this pair is feasible. The merge itself is exactly the
one that was asked for.

1. Emit the warning, naming both the swap and the evidence:

   ```
   WARNING: ownership inverted — <AggrB> owns <AggrA> (<AggrA> declares a single-valued
   <member class> holding <aggrB>AggregateId at <file:line>); swapping operands so <AggrB>
   absorbs <AggrA>
   ```

2. Exchange the two symbols everywhere for the rest of the run:

   ```
   AggrA, svcA  ←  the ORIGINAL AggrB   (the owner — now the survivor)
   AggrB, svcB  ←  the ORIGINAL AggrA   (the child — now dissolved)
   ```

3. Re-read the three "further readings" in SKILL.md against the swapped roles. They are not
   symmetric: which aggregate is referenced by id from third parties, where the invariants range and
   who publishes the events all change when the direction flips. Reusing the pre-swap readings is
   how a merge retains the wrong identifier.

4. Run `cases/1-ownership-merge.md` with the swapped symbols.

## Reporting the swap

The pipeline recorded a plan with `targetService` = the *original* `AggrA`. After the swap, the
surviving service is the other one. Say so explicitly, or the Monitor phase attributes the topology
change to the wrong service:

```
Topology: <svcB>-service dissolved into <svcA>-service (…)
```

uses the **post-swap** names, and the `Summary:` states the swap in words:

> Operands swapped: `<original AggrA>` is owned by `<original AggrB>`, so `<original AggrB>` is the
> surviving aggregate.

`Result: CHANGED` — the merge ran.

## When not to swap

Only when the classifier found ownership in exactly one direction, and it is the inverted one. If
**neither** direction has an ownership path, swapping changes nothing — walk the owner chains instead:
a common ancestor means `4-sibling-merge.md`, none means `5-no-relation.md`.
