# Case 4 — Not partitionable

P1 not satisfied. After the *Completeness contract* has auto-resolved every malformed argument, one
side of the partition is empty: there is no cut.

Because the contract is warn-only and auto-assigns unlisted entities to core, reaching this case
means something stronger than "the arguments were wrong". It means that once every entity was placed,
**they all ended up on the same side**. The three ways that happens:

| Shape | What it means |
|---|---|
| `--satellite` named nothing that exists on `<Aggr>` | every token was ignored as an unknown name, so satellite resolved to empty |
| `--satellite` named only nested children | nested entities follow their parent, so all of them were pulled back to core |
| `<Aggr>` declares one entity, or none | there is nothing to partition |

**Not this case:** an aggregate with exactly one composition unit, where the only possible satellite
is that unit and the core would keep nothing but scalars. P1 is technically satisfiable there, and the
verdict is **case 5(b)** with a non-blocking restriction — see `5-degenerate-satellite.md`.

Only the third shape is a property of the domain. The first two are properties of the **arguments** —
the next invocation, with different ones, may well split the same aggregate — so they must not leave a
record that outlives this run:

| Shape | Stop block |
|---|---|
| unknown satellite names | **none** |
| nested children only | **none** |
| one entity, or none | blocking `REFACTOR_RESTRICTION` — a single-entity aggregate does not become splittable by adding a functionality |

A run with no block and no code change is recorded `NOT_APPLICABLE` with no constraint
(`ExecuteService` step 3d) — the right weight for an argument error. A blocking restriction there would
veto `SPLIT_AGGREGATE` on this target for good because of a typo.

## Action

1. Emit the `GATE:` lines with the resolved partition, not the requested one.
2. **Single-entity shape only:** emit the `REFACTOR_RESTRICTION` block below. For the two argument
   shapes emit instead `WARNING: --satellite resolved to empty (<shape>) — no stop block; arguments are
   not a property of <Aggr>`.
3. Change no files other than `CLAUDE.md`. Emit no `Topology:`, no `Impact:`.

## Say why, in the code's terms

The `reason` is read months later by an architect on the Config page who cannot see this run. It has
to name the **fact in the code**, not the check that failed.

| Do not write | Why it is wrong |
|---|---|
| "P1 was not satisfied" | names the mechanism, not the domain. True of every case-4 verdict and tells the reader nothing. |
| "the aggregate cannot be split" | restates the verdict as its own justification. |
| "the entities are too coupled" | true of every aggregate — cohesion is what makes it an aggregate. |

Name the entity set you actually found.

### The three shapes, with their `GATE:` lines

**Unknown satellite names** — quote what `<Aggr>` really declares:

```
GATE: P1 not satisfied — --satellite named <X>, <Y>; neither is declared on <Aggr>, whose entities
are <the real list> (<Aggr>.java:<n>-<m>). After the completeness contract resolved the unknown
names, satellite is empty
GATE: <Aggr> partitions into <Core> + <Satellite> (0/5) — case 4
```

**Nested children only** — name the parent that pulled them back:

```
GATE: P1 not satisfied — --satellite named only TournamentParticipantQuizAnswer, a nested child of
TournamentParticipant (TournamentParticipant.java:23); nested entities follow their parent, so it
was reassigned to core and satellite is empty
GATE: <Aggr> partitions into <Core> + <Satellite> (0/5) — case 4
```

Splitting a nested child away from its parent is not a narrower split; it is a different operation on
a different boundary. If the intent was to separate the answer from the participant, the argument
should have named `TournamentParticipant` as satellite too.

**Single entity** — the honest one:

```
GATE: P1 not satisfied — <Aggr> declares one entity (<X>) besides its scalars; there is no pair of
entities to place on opposite sides of a boundary
GATE: <Aggr> partitions into <Core> + <Satellite> (0/5) — case 4
```

## Restriction block

Single-entity shape only. Place it inside the `CLAUDE.md` log entry, after the `GATE:` lines:

```
<!-- REFACTOR_RESTRICTION -->
operation: SPLIT_AGGREGATE
targetService: <svc>
secondaryTarget:
blocking: true
reason: <one sentence naming the entity set that could not be divided>
<!-- END_REFACTOR_RESTRICTION -->
```

`secondaryTarget` is empty — a split concerns one aggregate. `reason` states the architectural fact,
not the mechanical check; keep it under 512 characters, with the `file:line` evidence in the `GATE:`
lines. Emit exactly one block; the pipeline turns it into a blocking `refactor_constraint` row scoped
to this project, so Analyze stops proposing the target.

Worked example, for `course --core Course --satellite CourseType`:

```
reason: Course declares no entity other than its own scalars — CourseType is an enum, not an entity —
so there is no second group of state to give a boundary of its own.
```

## There is no follow-up

Unlike case 3, do not suggest a chain. Emit:

```
GATE: no follow-up — there is no second entity group here, so no prerequisite would make this candidate valid
```

If the aggregate really is too large but along a boundary these arguments did not describe, that is a
*different* candidate on the same target, and Analyze will produce it from its own signals. Naming
the boundary you would have cut in the `Summary:` is useful; emitting a block about it is not — a
block must only ever concern the target and operation of *this* invocation.
