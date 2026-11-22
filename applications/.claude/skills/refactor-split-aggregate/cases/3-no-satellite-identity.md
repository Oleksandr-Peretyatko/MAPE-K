# Case 3 — No satellite identity

P1 and P5 satisfied, P3 **not**: the entities partition cleanly and they are authoritative, but the
satellite set cannot stand as an aggregate. It has no identity of its own and no way to be created,
loaded or addressed except through the core.

## Why this is not a refusal

An aggregate in this codebase is not merely a group of entities. It needs:

- **an `aggregateId`**, drawn from its own service's sequence (`quizzes-composition.md` §2.4);
- **a creation path** — a functionality that brings it into existence, not a constructor the core
  calls inline;
- **a reason to be loaded on its own** — if every read of the satellite starts from the core, the
  split adds a command round-trip to every one of them and buys nothing;
- **a command surface** — after the split the core cannot load the satellite (R1/R2), so every
  interaction between them is a command. A satellite that accepts none can never be written to.

A set of entities failing these is a **value-object cluster**: real domain structure, but structure
that belongs *inside* an aggregate. Splitting it out produces a root that nothing ever addresses
directly, with all the coordination cost of a real aggregate and none of the benefit.

That is fixable. Give the satellite a creation path and it becomes a real aggregate, and the split
becomes case 1 or 2. So the verdict is `NO_CHANGE` with a **prerequisite**, not a restriction.

## Action

1. Say which of the three requirements is missing, with evidence:

   ```bash
   # is the satellite set ever created other than inline by the core?
   grep -rn "new <SatelliteEntity>(" src/.../microservices/<svc>/
   # is it ever addressed by an id of its own?
   grep -rn "<satellite>AggregateId" src/
   # does any read start from it rather than from the core?
   grep -rn "find.*<SatelliteEntity>" src/.../microservices/<svc>/
   ```

2. Emit the `GATE:` lines:

   ```
   GATE: P1 satisfied — core ∪ satellite covers all <n> entities of <Aggr>, disjointly
   GATE: P3 not satisfied — <SatelliteEntity> is only ever constructed inline by <Aggr> at
   <Aggr>.java:<n>; no functionality creates it, no id addresses it, and every read starts from
   <Aggr>
   GATE: <Aggr> partitions into <Core> + <Satellite> (2/5) — case 3
   ```

3. State the consequence the architect is accepting. The prerequisite is not a mechanical step:

   > Giving `<Satellite>` a creation path means deciding that it can exist independently of
   > `<Core>` — that a `<Satellite>` without a `<Core>` is a meaningful state the domain admits. If
   > it is not, the entities belong inside `<Core>` and the right answer is to leave them there.

4. Emit the prerequisite block, inside the CLAUDE.md entry, after the `GATE:` lines:

   ```
   <!-- REFACTOR_PREREQUISITE -->
   operation: SPLIT_AGGREGATE
   targetService: <svc>
   secondaryTarget:
   chain: new-functionality Create<Satellite> <Satellite>, then SPLIT_AGGREGATE <Aggr>
   reason: <one sentence naming which of identity, creation path or independent read is missing>
   <!-- END_REFACTOR_PREREQUISITE -->
   ```

   `secondaryTarget` is empty: a split concerns one aggregate. The pipeline reads the block back,
   records a **non-blocking** constraint and writes the chain into the candidate's `dependencies`.

   Do not create the functionality here. Naming it in `chain:` is a proposal for Analyze to
   evaluate; `new-functionality` is a different skill with its own contract.

5. Emit **no** `Topology:`, no `Impact:`, and change no files other than `CLAUDE.md`.

## Do not split it anyway as a consolation

Two half-measures are both wrong:

| Do not | Why |
|---|---|
| create the root and let the core construct it inline | a root that only the core creates is a member entity with a repository attached; it adds a command round-trip and a version to every read and removes no coupling |
| move the entities into a new *package* without a new root | that is a file reorganisation reported as a `SplitAggregate`. Monitor would measure no change and Knowledge would learn that splitting does nothing |
| create the service and let it start empty | the most expensive possible no-op: a container, a port, a database and an event subscriber for an aggregate nothing creates and nothing addresses |

If, on reading, the satellite turns out to have a creation path you missed — a functionality under
`coordination/` that creates it, or an id it is addressed by — P3 is satisfied after all and the case
is 1 or 2. Cite the functionality, not its absence.
