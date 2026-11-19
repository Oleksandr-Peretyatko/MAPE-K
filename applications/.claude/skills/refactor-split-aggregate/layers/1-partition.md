# Layer 1 — Partition

Carve the satellite out of `<Aggr>`: which classes are created, which move, and where each invariant
lands. **Identity, loading and versioning of the new root are layer 2** — do not solve them here.

---

## 1.1 — Read the root

`<Aggr>.java` in `microservices/<svc>/aggregate/`. The **base class is the source of truth**: it
declares the entities, `verifyInvariants()` and `getEventSubscriptions()`; `Saga<Aggr>` adds only saga
state. Apply the *Completeness contract* and write the resolved partition — entities and invariants,
per side — into the log.

## 1.2 — Scaffold the satellite, in its own service package

Name: `--new-aggregate`, or derived from the satellite entity set (`TournamentParticipant` →
`TournamentParticipation`). Record which, and check it against *Naming the satellite* in `SKILL.md`
**before creating a single file** — the name becomes a directory, a Spring profile, a container and a
database, and renaming it later means redoing this layer.

The satellite is a deployment unit, so it gets **its own package tree** `microservices/<sat>/`, a
sibling of `microservices/<svc>/`. A deployment unit is a scan root: `<Satellite>ServiceApplication`
(layer 4.8) roots `scanBasePackages`, `@EnableJpaRepositories` and `@EntityScan` at
`microservices.<sat>`, and a class outside that root is never seen by the process that owns it.

| Create | Where |
|---|---|
| `<Satellite>` (extends `Aggregate`), `<Satellite>Dto`, `<Satellite>Repository`, `<Satellite>CustomRepository`, `<Satellite>Factory` | `microservices/<sat>/aggregate/` — `<Satellite>Dto` carries the moved fields **and `<aggr>AggregateId`**, so a caller holding the core's id can reach it; `<Satellite>.buildDto()` builds it, and layer 2.7 decides what the core's DTO does with it |
| `Saga<Satellite>` | `microservices/<sat>/aggregate/sagas/` |
| `Sagas<Satellite>Factory` | `microservices/<sat>/aggregate/sagas/factories/` |
| `<Satellite>CustomRepositorySagas` | `microservices/<sat>/aggregate/sagas/repositories/` — create it even when `<Aggr>` has none (`quiz` is the case); layer 2.2's lookup lives here |
| `<Satellite>SagaState` | `microservices/<sat>/aggregate/sagas/states/` — **always**. An aggregate with its own service is reachable only remotely, so any step that mutates it must declare `forbiddenStates` (`../.claude/docs/architecture.md` R4) |

`<Satellite>Service`, `<Satellite>Functionalities`, `<Satellite>CommandHandler` and
`<Satellite>Controller` are layer 3's; the application class, its profile and its infrastructure are
layer 4's. This layer creates the aggregate, not the service around it.

**One level deep, inside the new tree.** The root and every moved entity sit directly in
`microservices/<sat>/aggregate/`, never in a nested package under it: static analysis discovers
entities one level deep only (`StaticAnalysisCollector.warnOnNestedAggregatePackages`), so a class
under `aggregate/participation/` is invisible to the entity count and to the reference resolver.
Discovery is by directory, which is also why the new tree is what makes the service count rise.

## 1.3 — Move the satellite entities

For each `--satellite` entity and its nested children: remove the field from `<Aggr>`, move the class
file to `microservices/<sat>/aggregate/`, declare it on `<Satellite>` with its mapping annotations
unchanged, and re-point the entity's back-reference to the new root.

**The files move across packages**, so every `package` declaration changes
and every import of the moved class — inside `<svc>` and anywhere else in the application — has to
follow. Step 0's inventory is the list; the compiler finds the rest, and layer 3.5 finds what the
compiler cannot see.

## 1.4 — Place the invariants

| Invariant reads | Goes to |
|---|---|
| core fields only | stays in `<Aggr>.verifyInvariants()` |
| satellite fields only | `<Satellite>.verifyInvariants()` |
| both sides, and calls `getPrev()` | **duplicated** on both roots — two version chains have no single `prev` (*Running P2*) |
| both sides, otherwise | case 2 decides: a replicated scalar keeps it intra; a collection makes it an inter-invariant |

The rule name moves between the roots' `INTRA-INVARIANTS` header blocks with its method. **A
straddling invariant is converted or duplicated, never dropped.**

## 1.5 — Carry the consistency annotations across the cut

The analysis reads three annotations. Each travels with what it annotates:

| Annotation | Rule |
|---|---|
| `@InterInvariant` on a registering method | moves with the method, and its rule names move from the core's `INTER-INVARIANTS` header to the satellite's. A method split in two splits its value list the same way, so each rule is claimed exactly once |
| `@Replica` on an entity | moves with the entity. A replica both sides read is duplicated with the annotation on both copies, or the lighter reader re-points — say which |
| `@ServiceGuard` on a service method | moves with the method |

A subscription whose **anchor stays in the core** — the replica carrying the publisher's id and version
— cannot be registered from the satellite until that anchor is replicated. Do `CREATE_REPLICA` first;
never drop the annotation and leave the rule unclaimed.

Dropping any of the three compiles. It makes Monitor report a maintained rule as unbacked, or hide a
replica or guard, and the next Analyze phase prices the version without them.

## 1.6 — Cross-aggregate references are ids

`<Aggr>` holds `<satellite>AggregateId`; `<Satellite>` holds `<aggr>AggregateId`. Never a Java
reference between the two roots — a `@OneToOne` between them makes them one aggregate again, and now
it would not even compile into a working deployment: the two roots live in different scan roots,
different persistence units and different databases. Only now, after 1.4, so no invariant can have
been written to navigate across.

## 1.7 — Verify

```bash
MS=src/main/java/org/anonymous/msrefactor/quizzes/microservices
M=$MS/<svc>; S=$MS/<sat>
B="${TMPDIR:-/tmp}/refactor-baseline-<svc>-split"

# the satellite has its own tree, and its root is one level deep inside it — 1 hit, then nothing
ls $S/aggregate/<Satellite>.java
find $S/aggregate -name "*.java" | grep -v "^$S/aggregate/[^/]*$" | grep -v "/sagas/"
# every moved entity really left the core — must print nothing, per E
grep -rn "class <E>" $M/aggregate --include=*.java
grep -nw "<E>" $M/aggregate/<Aggr>.java
# the package declaration of every moved file names the new tree — one hit each
grep -l "package .*microservices\.<sat>\.aggregate;" $S/aggregate/*.java | wc -l
# class count across BOTH trees — must equal $(cat "$B/aggregate-classes") + the class files 1.2 created
grep -rlE '^(public )?class ' $M/aggregate $S/aggregate --include=*.java | wc -l
```

Then re-run *Running P2* on both roots: every invariant left on either reads only its own side.

Compilation still fails here — layers 2 and 3 have not run. Do not fix service or coordination code
from this layer.

---

## Log line

```
LAYER 1 (partition): <Satellite> scaffolded in microservices/<sat>/ (<n> classes, name <given|derived>, service name <sat>); entities moved: <n>; invariants — <n> core, <n> satellite, <n> duplicated (getPrev), <n> handed to case 2; annotations moved — @InterInvariant <n>, @Replica <n>, @ServiceGuard <n>
```
