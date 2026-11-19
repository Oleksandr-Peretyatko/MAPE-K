---
name: refactor-split-aggregate
description: Partition one aggregate into a core and a satellite aggregate, re-homing entities and invariants across the new boundary. Use for the SPLIT_AGGREGATE operation. Arguments "<Aggr> --core E1,E2 --satellite E3,E4 --invariants I1@core,I2@satellite [--new-aggregate <Name>] [--project-path <path>]"
argument-hint: "<Aggr> --core E1,E2 --satellite E3,E4 --invariants I1@core,I2@satellite [--new-aggregate <Name>] [--project-path <path>]"
---

## Execution Protocol

You are running inside an **automated, headless pipeline**. No human is watching. Follow these rules without exception:

1. **Never ask for confirmation or clarification.** Do not output "Please confirm", "Should I proceed", or any question directed at the user. Make your best judgment and act on it — or reject and document, but never pause.
2. **If `## Refactoring Plan` is present in your prompt:** skip this inadvisability check entirely.
   The plan was validated in the Plan phase — follow it unconditionally. `Result: REJECTED` is
   not permitted; the only valid outcomes are `CHANGED` or `NO_CHANGE`.

   **If invoked standalone (no `## Refactoring Plan` section in your prompt):** if the operation
   is inadvisable, abort code changes, state your reasoning concisely, and proceed to step 3.
3. **Always write a CLAUDE.md entry.** At the end of every execution — whether files were changed, nothing changed, or the operation was rejected — append a timestamped log entry to `CLAUDE.md` in the working directory.

### CLAUDE.md log format

Use `run_bash` with the working directory to append (fill in real values):

```bash
echo "
<!-- refactor-log -->
Date: $(date -u +'%Y-%m-%dT%H:%M:%SZ')
Skill: refactor-split-aggregate
Invocation: <full /skill args as given>
Result: CHANGED | NO_CHANGE | REJECTED
Summary: <1-3 sentences describing what changed, or why nothing changed, or why the operation was rejected>
<!-- end-refactor-log -->
" >> CLAUDE.md
```

This step is mandatory. Do not skip it under any circumstances.

When the *Partition Classifier* returns **case 3, 4 or 5**, this entry is the **only** file you
write — it carries `Result: NO_CHANGE`, the `GATE:` verdicts, and a stop block the pipeline reads back
from this file: a `<!-- REFACTOR_PREREQUISITE -->` block for case 3 (splittable, but not in one step),
a non-blocking `<!-- REFACTOR_RESTRICTION -->` for case 5 (the cut is legal and buys nothing), or —
for case 4, and **only** when `<Aggr>` declares fewer than two entities — a blocking
`<!-- REFACTOR_RESTRICTION -->`. A case 4 reached through degenerate arguments emits no block.

### Rules for stop blocks

The pipeline reads **only the entry you are appending now**, and rejects any block that breaks these:

1. **Only about the aggregate you were given.** `targetService` must name the aggregate in *this*
   invocation. Never emit a block about a different aggregate, however confident you are that some
   other one is unsplittable — that is Analyze's job, and a block naming another target would block
   it for every future run.
2. **Only on a run that changed nothing.** A block is a statement that the operation could not be
   applied. If you changed code, report `Result: CHANGED` and emit no block.
3. **Never carry one forward.** `CLAUDE.md` already contains entries from earlier runs, some with
   their own blocks. Do not read them, restate them, or re-emit their verdicts. They are already
   recorded.
4. **Never on an operation that already ran, nor over arguments.** Once the satellite entities have
   moved, their names no longer resolve on `<Aggr>` and the run lands in case 4 through "unknown
   names" — a permanent block on a target that split successfully. *Step −1* catches that first. More
   generally, a block records a property of the **target**; a bad argument is not one (case 4).

---

# Skill: SplitAggregate for DDD / SAGA Microservice Architectures

## Purpose

You are analyzing the applicability of the operation:

`SplitAggregate(A -> A1, A2, mode)`

This operation splits one aggregate `A` into two new aggregates `A1` and `A2`.

Your role is to determine whether the aggregate is too large, too mixed in responsibilities, too hard to reason about, or too costly to maintain as a single domain boundary, and whether splitting it is justified.

An aggregate is a **domain boundary** and a service is a **runtime boundary**, and in this
architecture they coincide: each microservice owns exactly one aggregate type. So a split that
succeeds ends with two of both. What the operation must never become is the reverse — a package or
process rearrangement that leaves one aggregate root where there were supposed to be two.

---

## Section inventory

The section inventory all four operation skills share is
[`../.claude/docs/concepts/refactoring-surface.md`](../../docs/concepts/refactoring-surface.md).

---

## Operation Signature

`SplitAggregate(A -> A1, A2, mode)`

Where:
- `A` = original aggregate
- `A1`, `A2` = resulting aggregates
- `mode` = split rationale or strategy, such as:
  - responsibility-based
  - invariant-based
  - entity-cluster-based
  - workflow-based

---

## Intent

Use this operation when a single aggregate has become too broad, too complex, too internally heterogeneous, or too difficult to maintain as one unit of consistency.

The goal is to improve cohesion, ownership clarity, and diagnosability by separating concerns into more focused aggregates.

---

## Layers this operation runs

All four, in order, and **none of them is optional**:

| | Layer | What it settles |
|---|---|---|
| 1 | `layers/1-partition.md` | the satellite is scaffolded in its own package tree: entities, invariants, annotations, id references |
| 2 | `layers/2-root-split.md` | identity, loading, mutation, copy constructors, events, deletion, the DTO across the cut |
| 3 | `layers/3-coordination.md` | dispatch, steps, forbidden states, the event quad, external references, the satellite's own surface |
| 4 | `layers/4-deployment.md` | the deployment unit appears: routing, compose, pom, yamls, application class, DB, tests |

**Layer 4 is not conditional.** The satellite is an aggregate, and each microservice owns exactly one
aggregate type, so the split ends with a service, a database, a port and a route that did not exist
before. A satellite left inside `<svc>`'s process shares its transaction, its database and its
`@EntityScan` root with the aggregate it was cut from, and every per-service measure reports the split
as having changed nothing — it is a split that did not happen.

---

## Preconditions

Before recommending `SplitAggregate`, verify or assume the following:

### Structural preconditions
- entities inside `A` can be partitioned into meaningful groups
- a new ownership model can be defined
- one or more new aggregate roots can be identified
- relationships between internal entities can be reclassified as inter-aggregate where needed

### Behavioral preconditions
- current invariants can be:
  - kept local within one resulting aggregate, or
  - explicitly converted to distributed enforcement
- workflows that currently assume one aggregate boundary can be adapted safely
- eventing or coordination can be added where local consistency is lost

### Architectural preconditions
- the team accepts the introduction of new inter-aggregate dependencies
- the resulting split aligns with domain semantics, not only technical convenience
- **the satellite can be deployed**: `lowerFirst(<Satellite>)` is a legal service name, a free
  `server.port` / `grpc.server.port` pair exists — free meaning published by neither compose file and
  not reserved on the host (layer 4.2) — and every REST path that moves can be given a route
  `order` below the core's — see *Naming the satellite* and case 6

**Not preconditions.** These are commonly mistaken for blockers and are not:

- **An invariant reads both sides.** That is case 2, a normal outcome — five of `Tournament`'s twelve
  invariants straddle the cut that moves the participants out. It is a weakening to be logged, not a
  refusal.
- **The arguments are incomplete or wrong.** The *Completeness contract* auto-resolves every
  malformed argument with a `WARNING:`. This skill never aborts for arguments.
- **Entities are referenced from outside `<Aggr>`.** They usually are. Re-pointing those references
  is the work, not an obstacle to it.

---

## Before

Typical BEFORE condition:

- Aggregate `A` contains many entities and rules
- Several responsibilities are enforced under one root
- All checks are local/intra-aggregate
- The aggregate is hard to understand, maintain, or evolve
- Different workflows may touch different parts of `A` unevenly

---

## After

Typical AFTER condition:

- Aggregate `A` disappears as a single domain boundary
- New aggregates `A1` and `A2` each own a focused subset of:
  - entities
  - invariants
  - commands
- Some former internal relations become inter-aggregate dependencies
- Some former intra-invariants may become:
  - split invariants
  - inter-invariants
  - event-enforced rules
- New coordination paths may appear

---

## Risks

Main risks include:

- converting local invariants into distributed invariants unintentionally
- introducing new SAGA steps and events
- degrading consistency or latency
- splitting along technical rather than domain boundaries
- producing an anemic or overly fragmented model
- increasing operational complexity without enough benefit

---

## What the satellite's own service costs

The satellite is a deployment unit, and each item below is a price the operation pays, not a defect.
State each one in the log; an unstated cost reads as an oversight.

| Cost | Mechanism |
|---|---|
| **Every core↔satellite read becomes a broker round-trip** | `architecture.md` R1/R2: a service loads only its own aggregate type and injects only its own repositories. What was a field access becomes a command inside the gateway's `command.gateway.timeout-seconds` budget |
| **`find<Satellite>IdBy<Aggr>Id` stops being a local query** | the satellite's rows live in `<sat>db`, for which the core has no datasource. The query survives inside the satellite; the core reaches it through `commands/<sat>/Find<Satellite>By<Aggr>IdCommand` (layer 2.2), and must still raise `AGGREGATE_NOT_FOUND` so callers and tests see no change |
| **A second database, with its own id sequence** | ids come from a per-database sequence (composition doc §2.4), so `<satellite>AggregateId = 3` and `<aggr>AggregateId = 3` will coexist. **No data is migrated**: the moved entities' existing rows stay in `<svc>db` and are abandoned. Acceptable only because every service runs `ddl-auto: create` and Monitor regenerates state with a load test — say so in the log, as merge 4.9 requires |
| **One more event subscriber, with its own group** | exactly one subscriber per deployment unit still holds. Its consumer group must be `<sat>-service-group`; sharing the core's group would split the event stream between them |
| **The saga-commit naming trap** | `resolveServiceName(Saga<Satellite>)` is `lowerFirst(<Satellite>)`. If `spring.application.name` is anything else, every commit is published to a channel with no consumer and blocks until the gateway times out. **The test profile cannot catch this** — it runs `local`, whose gateway never compares against `spring.application.name` |
| **Visibility, in both directions** | the collector discovers services by directory, so the service count rises and M1/M2 finally move. The same mechanism means a satellite left in the core's package reports as no split at all |

---

## Execution Mode

When invoked with arguments, apply the refactoring directly to the codebase under `<projectPath>/`
— the `--project-path` value, which is also your working directory. It defaults to
`applications/quizzes` when the argument is absent.

### Invocation

```
/split-aggregate <Aggr> --core E1,E2,... --satellite E3,E4,... --invariants I1@core,I2@satellite,... [--new-aggregate <Name>] [--project-path <path>]
```

This is the **main entry point** for the operation. The contract is deliberate: the split is a
**complete, lossless partition** of the aggregate. Every entity and every invariant of `Aggr`
must be explicitly accounted for in the command so that **no entity is ever removed** — entities
are only re-homed between the original (`core`) and the new (`satellite`) aggregate.

### Parameters

| Param | Required | Meaning |
|-------|----------|---------|
| `Aggr` | yes | Aggregate Java class name to split (e.g. `Tournament`) |
| `--core` | no — auto-resolved | Comma-separated entity class names that REMAIN in the original aggregate. Any entity of `Aggr` not named in `--satellite` lands here anyway, with a `WARNING:` (*Completeness contract*). |
| `--satellite` | yes | Comma-separated entity class names that MOVE to the new aggregate. The only argument that cannot be defaulted: if it resolves to empty there is no cut. |
| `--invariants` | no — auto-resolved | Partition of `Aggr`'s invariants, each tagged `<invariantName>@core` or `<invariantName>@satellite` (e.g. `invariantUniqueParticipant@satellite,invariantDateOrdering@core`). Each should appear once; an omitted one is placed on the side owning the fields it reads, with a `WARNING:`. The run never aborts for this argument. |
| `--new-aggregate` | optional | Explicit class name for the satellite aggregate. If omitted, derive it from the satellite entity set (e.g. `TournamentRoster`). Explicit or derived, it is used only within the *Bounds* of *Naming the satellite*; otherwise `<Aggr>Satellite` replaces it, with a `WARNING:`. |
| `--project-path` | injected | Application module being refactored, relative to the repository root (e.g. `applications/quizzes`). Supplied by the pipeline; defaults to `applications/quizzes` when absent. |

### Resolving names

`<svc>` is the **package directory** of `<Aggr>`, not `<Aggr>.toLowerCase()`. The composition doc
§2.2 is explicit about this: `QuizAnswer` lives in `microservices/answer/`, and `Execution` lives in
`microservices/execution/` while its member family is named `CourseExecution*`. Discover it:

```bash
ls -d src/main/java/org/anonymous/msrefactor/quizzes/microservices/*/
grep -rn "class <Aggr> " src/main/java/org/anonymous/msrefactor/quizzes/microservices/*/aggregate/
```

`<Core>` is `<Aggr>` — the original class keeps its name and its identity. `<Satellite>` is
`--new-aggregate`, or derived from the satellite entity set (`TournamentParticipant` →
`TournamentRoster`) — in both cases only within the *Bounds* below, else `<Aggr>Satellite`.
(`TournamentParticipation`, the form the examples in this skill use, is one character over bound 2.)

**Three names on the core's side, not one.** `refactoring-surface.md` §1 (*The three names a
placeholder can stand for*) is the authority; resolve all three before layer 1 and log them:

| Form | What it is | It names on the core's side |
|---|---|---|
| `<Svc>` capitalised | the package directory | `<Svc>CommandHandler`, `<Svc>ServiceApplication`. The handler's name is load-bearing — the bean is resolved as `getServiceName() + "CommandHandler"` |
| `<Aggr>` | the root class | the root, `Saga<Aggr>`, `<Aggr>Service`, `<Aggr>Functionalities`, `<Aggr>Controller`, `<Aggr>EventProcessing` |
| `<Fam>` | the member-family prefix, **discovered with `ls`, never derived** | `<Fam>Dto`, `<Fam>Factory`, `<Fam>Repository`, `<Fam>CustomRepository`, `<Fam>SagaState`, `<Fam>EventHandling`, the abstract `<Fam>EventHandler`, `<Fam>Subscribes<Event>` |

`execution` is the case for `<Fam>` (root `Execution`, family `CourseExecution`) and `answer` the case
for `<Svc>` (root `QuizAnswer`, service `answer`, so `AnswerServiceApplication.java`). In the other six
all three are the same string, which is why a wrong derivation greps for a file that is not there and
the empty result reads as a pass.

**The satellite's side needs none of this**: its service name is `lowerFirst(<Satellite>)` by the rule
below, so `<Svc>`, `<Aggr>` and `<Fam>` all collapse to `<Satellite>` for every file this operation
creates. That is a property of the naming rule, not luck — break the rule and it stops holding.

### Naming the satellite

`<Satellite>` is a deployment identifier, not a label. It fixes the service name, the Spring profile,
the container, the database and the command channels, so deriving it silently is not acceptable: if
`--new-aggregate` is absent, derive it, **state it in the log, and check it against the rule below**.

**The rule.** The satellite's `spring.application.name` **must** equal `lowerFirst(<Satellite>)`.
That is exactly what `SagaUnitOfWorkService.resolveServiceName` produces from `Saga<Satellite>` when
it addresses a commit or an abort, and exactly what `StreamCommandGateway.send` compares against to
decide whether a command is handled in-process. Any other name and every commit of a saga-locked
satellite is published to a channel with no consumer.

The application already carries that defect: `SagaQuizAnswer` resolves to `quizAnswer`, while the
service is called `answer` (composition doc §2.2). Do not reproduce it.

**Two derived forms, never conflated:**

| Form | Value | Used by |
|---|---|---|
| `<sat>` — camel | `lowerFirst(<Satellite>)`, e.g. `tournamentParticipation` | `spring.application.name`, the `ServiceMapping` constant's value, `<sat>-command-channel`, `<sat>ServiceCommandChannel`, the `<sat>CommandHandler` bean name, `<sat>EventSubscriber` |
| `<sat-infra>` — kebab | `tournament-participation` | the Spring profile `<sat-infra>-service`, the file `application-<sat-infra>-service.yaml`, the pom `<id>`, the compose keys and `container_name`, and `POSTGRES_DB: <satinfra>db` |

Only the camel form is load-bearing for dispatch. Both are derived once, at the start, and written
into the log; deriving either twice is how they drift.

**Prefer a single-word name.** `resolveServiceName` logs `Potential bad service name resolution` for
any result containing an upper-case letter, so a two-word satellite warns on every commit — expected,
noted once, not per commit.

**Bounds.** Checked once, when the name is resolved and before any mutation — and they apply to an
explicit `--new-aggregate` exactly as to a derived name, because an explicit name is not a vetted one:
the Analyze phase used to pass a mechanical `<Aggr><E1><E2><E3>Cluster` as `--new-aggregate`. The name
must be:

1. **UpperCamel** — `[A-Z][A-Za-z0-9]*`;
2. **at most 22 characters** — every file this operation creates carries the name twice (package
   directory and class name), the deepest at `165 + 2·length` characters from the repository root, and
   a Windows checkout cannot create a path over 260. v6 of the evaluation named its satellite
   `TournamentCreatorParticipantParticipantQuizAnswerCluster` (56): the run committed 23 files that a
   Windows clone of the branch silently does not have;
3. **not ending in `Cluster`** — a mechanical name becomes the container and the database name;
4. **not taken** — no class under `src/main/java` and no `microservices/*` directory with that name,
   compared case-insensitively:
   ```bash
   grep -rlE "(class|interface|enum|record) <Satellite>\b" src/main/java
   ls src/main/java/org/anonymous/msrefactor/quizzes/microservices | grep -ix "<sat>"
   ```

A name outside the bounds is **replaced, never refused**: use `<Aggr>Satellite`, then `<Aggr>Satellite2`,
`<Aggr>Satellite3`… until bound 4 holds, and emit
`WARNING: satellite name <X> replaced by <Y> — <bound it broke>`. The fallback is accepted even if it
exceeds bound 2 — the pipeline produces the same `<Aggr>Satellite`, and both sides must agree.

### Path conventions

`<projectPath>` is the application module and **is your working directory**; every path in this file
is relative to it. Source lives under
`src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>/`.

> **Locate by package path, never by class-name prefix.** `TournamentTopic` is `tournament`'s replica
> of Topic; it is a member of `Tournament`, not part of `microservices/topic/`.

**Only files under `<projectPath>/` may be modified.** The pipeline stages
`git add -A -- <projectPath>/`; edits anywhere else are silently discarded.

---

## Mandatory read order

Read these **in this order**, and read each one fully before acting on it:

| # | File | When |
|---|------|------|
| 0 | `*-composition.md` in the working directory (`quizzes-composition.md` for the quizzes module) | always — **§6 is the per-aggregate split feasibility already computed** (feasible / needs a domain decision / degenerate / not partitionable), so read it before the classifier re-derives it; and §2.2 and §2.3 govern how the satellite's commands are dispatched, which is the constraint this operation most often breaks. §6 is an **input, not a gate**: the classifier stays the authority, and a disagreement means the tree moved since — say so in the log. If the module has none, derive those facts yourself and say so in the log. |
| 1 | `../.claude/docs/concepts/refactoring-surface.md` | always — §1 is the section inventory Step 0 classifies into, §2 the SPLIT column, §3 the sets that must change together. Shared with the other three operation skills; this file is the authority wherever it is more specific. |
| 2 | this file, through *Step 0* | always |
| 3 | `cases/<n>-*.md` for the case the classifier returned | always — exactly one |
| 4 | `layers/1-partition.md` → `2-root-split.md` → `3-coordination.md` → `4-deployment.md` | cases 1, 2 and 6 — in this order |

Skill files are at `../.claude/skills/refactor-split-aggregate/` relative to the working directory;
shared docs at `../.claude/docs/`. Every path in `SKILL.md`, `layers/` and `cases/` is relative to the
working directory; only markdown link *targets* resolve from the file.

**Each layer file ends with a required log line.** Emit all four, in order, in the CLAUDE.md entry. A
missing `LAYER n:` line means that layer was not applied — do not report `Result: CHANGED` with one
missing.

---

## Step −1 — Already applied?

Runs **first**, before the classifier. On a retry the `--satellite` entities have already moved, so
they no longer resolve on `<Aggr>` and the classifier would reach case 4 through "unknown names".

```bash
# for each --satellite entity E
M=src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>
find $M -name "<E>.java"                                  # does E exist?
grep -lw "<E>" $M/aggregate/<Aggr>.java                   # does <Aggr> still hold it?
```

| `<E>.java` exists | `<Aggr>.java` references `<E>` | Verdict |
|---|---|---|
| yes, for every E | no, for every E | **already applied** — `Result: NO_CHANGE`, `Summary: already applied in a previous run`; no stop block |
| no, for some E | — | **unknown name** — leave it to the *Completeness contract* and case 4 |
| yes | yes, for some E | not applied — continue to the classifier |

A `NO_CHANGE` that touched only `CLAUDE.md` is recorded `NOT_APPLICABLE` with no constraint
(`ExecuteService` step 3d).

---

## Step 0 — Global impact inventory

Run **before any mutation**, after the classifier returns case 1 or 2. Its output is the work list.

A split is not confined to the aggregate being cut: **any** aggregate that references `<Aggr>` may
now be referencing something that lives on the other side of the new boundary. Discover in one pass:

```bash

# everything outside <svc> that reaches into it
grep -rln "microservices\.<svc>\.\|commands\.<svc>\.\|ServiceMapping\.<AGGR>\|<Aggr>Dto\|<aggr>AggregateId" \
  src | grep -v "/microservices/<svc>/"

# the entity set and the invariant set actually declared on <Aggr> — the partition must cover both.
# verifyInvariants is the entry point: every rule is reachable from it whatever it is named.
grep -n "private \|Set<\|verifyInvariants\|invariant" src/.../microservices/<svc>/aggregate/<Aggr>.java
```

Classify every hit into exactly one of these eight classes. **Each class has a different action** —
fixing only what breaks compilation leaves the rest silently wrong:

| Class | Real example (`Tournament`) | Action |
|-------|------------------------------|--------|
| **Entity of `<Aggr>`** | `TournamentCreator`, `TournamentParticipant`, `TournamentTopic`, `TournamentQuiz`, `TournamentCourseExecution` | assign to core or satellite; nested children (`TournamentParticipantQuizAnswer`) follow their parent |
| **Invariant of `<Aggr>`** | the twelve rules reached from `verifyInvariants()` | assign to core or satellite; one reading both sides is a *straddling invariant* — see the classifier. Enumerate from `verifyInvariants()`, not from the `invariant…()` prefix: it is a convention of two aggregates, and elsewhere the rule is a differently-named method or an inline check |
| **Replica held by `<Aggr>`** | `TournamentCourseExecution`, `TournamentTopic` | goes to whichever side reads it, **carrying its `@Replica` annotation**. A replica read by both sides must be duplicated or the side that reads it less must re-point — say which in the log; a duplicated class keeps the annotation on both copies |
| **DTO** | `TournamentDto` | one DTO per resulting aggregate; layer 2.7 decides whether the core composes the satellite's or loses the field, and a field that leaves the response is externally visible |
| **Functionality** (sagas) | `CreateTournamentFunctionalitySagas`, `AddParticipantFunctionalitySagas` | steps that touch only one side stay — but on whichever side's service now owns them; a step touching both becomes two steps, and that is a **new distributed interaction** — count it. Every `setForbiddenStates` list the functionality carried survives the move, re-anchored at whichever aggregate still holds the lock — layer 3.3 |
| **Read the core does inline** | any `<Aggr>Service` line that touches a satellite field | becomes a command to `ServiceMapping.<SAT>` (layer 2.2). This is the class that does not break compilation on its own and is the operation's real cost — count each one |
| **Event / subscription / handler** | `TournamentSubscribes*` in `getEventSubscriptions()` | re-home with the entity it guards; a subscription serving a straddling invariant follows the converted rule. **The `@InterInvariant` annotation on the registering method moves with it**, and the rule name moves from the core's `INTER-INVARIANTS` header to the satellite's — layer 1.5 |
| **Deployment slot** | host ports published by `docker-compose.yml` and the root file it includes (8080–8089, 8761, 5672, 15672, 16686, 14250, 14268, 4317–4318) are taken, as are the reserved 8090/8100 and any `## Reserved host ports`; grpc 9092–9099 are taken; `ServiceMapping` has 8 constants; `docker-compose.yml` has 8 service blocks | the satellite needs one of each. Allocate in case 6, create in layer 4 — a split that skips this class compiles, passes and does not run |
| **Test** `.groovy` | | re-point construction and assertions across both aggregates, and add the satellite's beans to `BeanConfigurationSagas` |

**Aggregates with no references to `<Aggr>`** are listed explicitly as `no references` in the log —
checked, not assumed.

The consistency annotations — `@InterInvariant`, `@Replica`, `@ServiceGuard` — travel with what they
annotate; layer 1.5 governs how. Dropping one compiles and blinds the next Analyze phase to it.

Record the per-class counts in the CLAUDE.md `Impact:` line.

## Step 0.5 — Baseline

Runs right after Step 0, **before any mutation**. Layers 1.7, 3.4 and 4.11 compare against these;
without them a check can only count, never detect a loss. Written outside `<projectPath>`, which the
pipeline stages whole:

```bash
B="${TMPDIR:-/tmp}/refactor-baseline-<svc>-split"; mkdir -p "$B"
M=src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>
grep -rhoE '(QuizzesErrorMessage\.|QuizzesException\(\s*)[A-Z][A-Z0-9_]+' $M --include=*.java \
  | grep -oE '[A-Z][A-Z0-9_]+$' | sort -u > "$B/errors"
grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping\([^)]*\)' $M --include=*.java | sort -u > "$B/routes"
grep -rlE '^(public )?class ' $M/aggregate --include=*.java | wc -l > "$B/aggregate-classes"
echo "$(ls $M/notification/subscribe/*.java 2>/dev/null | wc -l) \
$(h=$(ls $M/notification/handling/handlers/*.java 2>/dev/null | wc -l); echo $(( h > 0 ? h - 1 : 0 ))) \
$(cat $M/notification/handling/*EventHandling.java 2>/dev/null | grep -c '@Scheduled') \
$(cat $M/coordination/eventProcessing/*.java 2>/dev/null | grep -c 'public void process')" > "$B/quad"
```

| Anchor | Checked in | Rule |
|---|---|---|
| `aggregate-classes` | layer 1.7 | post = pre + the class files (not interfaces) layer 1.2 created — entities move within `aggregate/`, so moving changes no count |
| `quad` | layer 3.4 | each figure rises by exactly the consumptions layer 3.4 created |
| `errors` | layer 4.11 | post ⊇ pre. A constant that legitimately disappears — a straddling rule converted in case 2 whose replacement no longer throws it — is named in the log with that reason |
| `routes` | layer 4.11 | post ⊇ pre |

---

## Partition Classifier

Runs before any mutation, after Step 0's greps are available. It returns a **case**, not a pass/fail.

The question is not "is `<Aggr>` too big" — Analyze already answered that. It is: **does a cut exist
that leaves each side a real aggregate, and a deployable one?** Five signals decide it:

| | Signal | Check |
|---|---|---|
| **P1** | the partition is total and disjoint | `core ∪ satellite` covers every entity declared on `<Aggr>`, `core ∩ satellite` is empty — resolved automatically by the *Completeness contract* below, so P1 fails only when resolution leaves one side empty |
| **P5** | the satellite is **authoritative** | it owns at least one field that no synchronising event writes, **and** the core keeps at least one member entity. A satellite that is only a cache of another aggregate is a replica with a container around it — see case 5 |
| **P3** | the satellite is a **real aggregate** | it has, or can be given, its own `aggregateId`, a creation path, **and a command surface** — after the split every core→satellite interaction is a command, so a satellite that accepts none can never be written to |
| **P4** | the satellite is **deployable and addressable** | `lowerFirst(<Satellite>)` is a legal service name within the *Bounds* (*Naming the satellite*), a free `server.port`/`grpc.server.port` pair exists — published by neither compose file and not reserved (layer 4.2) — and every REST path that moves can take a route `order` below the core's — see case 6 |
| **P2** | every `invariant…()` reads entities from exactly one side | see below — the authoritative check for *which* of the executable cases you are in |

**Evaluate in the order P1 → P5 → P3 → P4 → P2**, so the cheapest refusal fires first: there is no
point classifying invariants for a cut that produces a cache with a database attached.

### Running P2

**Do not decide P2 from the invariant's name.** Read the method body and list the fields it touches.
The names in this codebase are actively misleading in both directions.

Take `Tournament` cut as core = `{TournamentCreator, TournamentTopic, TournamentQuiz,
TournamentCourseExecution}` + scalars, satellite = `{TournamentParticipant,
TournamentParticipantQuizAnswer}`:

| Invariant | Reads | Verdict |
|---|---|---|
| `invariantStartTimeBeforeEndTime` | `startTime`, `endTime` | core only ✔ |
| `invariantUniqueParticipant` | `tournamentParticipants` | satellite only ✔ |
| `invariantMustHaveOneTopic` | `tournamentTopics` | core only ✔ |
| `invariantNumberOfQuestionsPositive` | `numberOfQuestions` | core only ✔ |
| `invariantNumberOfQuestionsAtMost30` | `numberOfQuestions` | core only ✔ |
| `invariantCreatorIsNotAnonymous` | `tournamentCreator` | core only ✔ |
| `invariantFinalAfterStart` | `tournamentTopics`, five scalars, `getPrev()` | core only ✔ |
| `invariantParticipantsEnrolledBeforeStarTime` | `p.getEnrollTime()` **and** `this.startTime` | **straddles** |
| `invariantAnswerBeforeStart` | `t.getParticipantAnswer()` **and** `this.startTime` | **straddles** |
| `invariantDeleteWhenNoParticipants` | `getState()` **and** `getTournamentParticipants()` | **straddles** |
| `invariantCreatorParticipantConsistency` | `tournamentCreator` **and** `tournamentParticipants` | **straddles** |
| `invariantCancelledFieldsAreFinal` | `tournamentTopics` **and** `tournamentParticipants`, plus `getPrev()` | **straddles, and cannot be re-homed** |

Five of twelve straddle, and none of the five is obvious from its name —
`invariantUniqueParticipant` sounds satellite-scoped and is;
`invariantParticipantsEnrolledBeforeStarTime` sounds satellite-scoped and is not, because it compares
against the core's `startTime`. Cite the field you found, not what this document says.

Two of the rows above are traps worth naming:

- **`invariantCancelledFieldsAreFinal` reads two collections**, `tournamentTopics` *and*
  `tournamentParticipants` (line 347), although its header comment mentions only the first. Trusting
  the comment classifies it as core-only.
- **It also calls `getPrev()`**, and so does `invariantFinalAfterStart`. A rule that compares against
  the aggregate's previous version cannot span a boundary at all: after the split each side keeps its
  own version chain, so there is no single `prev` to compare against. Such a rule is not expensive to
  re-home — it is impossible, and the only resolution is to **duplicate it on both sides**. Do not
  convert it into an event-enforced rule; say in the log that it was duplicated.

The mechanical check:

```bash
sed -n '/invariant<Name>/,/^    }/p' src/.../microservices/<svc>/aggregate/<Aggr>.java
```

for each invariant in turn, then map every `this.<field>` and every accessor on a member to its side.

**P2 not satisfied is not a blocker** — it is case 2, and the straddling invariants become
event-enforced inter-aggregate rules. It is a *weakening*, which is why it gets its own case rather
than being buried in the action list.

### Verdict lines

Emit one line per signal you evaluated, with evidence:

```
GATE: P<n> <satisfied|not satisfied> — <what is true in the code, with file:line>
```

and a single summary line above them:

```
GATE: <Aggr> partitions into <Core> + <Satellite> (<n>/5) — case <n>
```

`GATE:` is a verdict, not a fault. Keep `WARNING:` for things that actually went wrong, so a real
problem still stands out in the log.

| P1 | P5 | P3 | P4 | P2 | Case | File |
|---|---|---|---|---|---|---|
| **no** | — | — | — | — | **4 — not partitionable** (a blocking refusal only when `<Aggr>` has fewer than two entities) | `cases/4-not-partitionable.md` |
| yes | **no** | — | — | — | **5 — degenerate satellite** (the cut is legal and buys nothing) | `cases/5-degenerate-satellite.md` |
| yes | yes | **no** | — | — | **3 — no satellite identity** | `cases/3-no-satellite-identity.md` |
| yes | yes | yes | **no** | — | **6 — no independent address** (allocate, then run) | `cases/6-no-independent-address.md` |
| yes | yes | yes | yes | **no** | **2 — straddling invariants** | `cases/2-straddling-invariants.md` |
| yes | yes | yes | yes | yes | **1 — clean partition** | `cases/1-clean-partition.md` |

Cases 3, 4 and 5 change no code. Cases 1, 2 and 6 run the layers; case 6 is case 1 or 2 with a
deployment allocation done first, and it says which.

---
## Completeness contract

Apply these rules **before any mutation**, after reading `Aggr`. This skill runs headless, so it
**never aborts** for incomplete arguments — it auto-resolves to a safe default and records a
`WARNING:` line that must be reflected in the mandatory CLAUDE.md `Summary:`.

- **Completeness (warn-only):** Enumerate every entity class owned by `Aggr`. Let
  `listed = core ∪ satellite`. If an owned entity is NOT in `listed`, assign it to **core** and
  emit `WARNING: entity <X> was not listed; auto-assigned to core`. Do NOT abort.
- **Disjointness:** `core ∩ satellite` must be empty. If an entity appears on both sides, treat
  it as **satellite** (tie-break: satellite wins) and emit a `WARNING:`.
- **Unknown names:** Any token in `--core`/`--satellite`/`--invariants` that does not match an
  entity/invariant actually declared on `Aggr` is ignored with a `WARNING:`.
- **Nested entities follow their parent:** child entities (e.g. `TournamentParticipantQuizAnswer`
  owned by `TournamentParticipant`) move with their parent automatically. Listing them is
  optional, but if listed they must be on the same side as their parent (otherwise warn and
  follow the parent).
- **Invariant partition (required):** every `invariant…()` method on `Aggr` must be tagged
  `@core` or `@satellite`. A missing/untagged invariant is assigned to the side that owns the
  entities it reads (else `core`) with a `WARNING:`. An invariant that reads entities from
  **both** sides cannot remain intra-aggregate — that is a *straddling invariant*, the classifier
  returns case 2, and `cases/2-straddling-invariants.md` governs how it is converted.
- **Satellite name:** a `--new-aggregate` (or derived name) outside the *Bounds* of *Naming the
  satellite* is replaced by `<Aggr>Satellite` (numbered if taken) with a `WARNING:`. Do NOT abort.
- **Guarantee:** the union of `core` and `satellite` entities always equals `Aggr`'s original
  entity set. **No entity is ever removed by this operation.**

Beyond entities and invariants, if the split runs it runs completely:

- **Variants.** `Saga<Satellite>`, a factory interface, the sagas factory implementation, a repository
  and a states enum are produced — the shape `<Aggr>` already has. A satellite with only a base class
  cannot take part in a saga.
- **Deployment.** The satellite's service, database, port, profile, `ServiceMapping` constant and
  event subscriber are created (layer 4). A split reported as `CHANGED` with the deployment-unit count
  unchanged is a split that did not happen.
- **Behaviour.** The same REST paths answer — from whichever service now owns them — the same events
  reach the same external consumers, and the same errors are raised. Step counts are expected to
  *rise*, not fall: that is the cost the operation trades for a smaller boundary.

Malformed **arguments** never abort the run, per the rules above.

That licence does not extend to **feasibility**. Case 3 reports `NO_CHANGE` and emits the step that
would give the satellite an identity; case 5 reports `NO_CHANGE` and names the cheaper operation;
case 4 reports `NO_CHANGE`, and refuses outright only when `<Aggr>` has fewer than two entities. None
of them substitutes a different operation — moving entities into a new *service* while leaving one
aggregate root is a service extraction, not this operation, and reporting it as one would poison what
Monitor measures and what Knowledge learns.

## Run the layers

Cases 1, 2 and 6 — cases 3, 4 and 5 write the CLAUDE.md entry and stop. Work the Step 0 inventory, not
the layer headings: the layers give the order, the inventory gives the scope.

| Layer | Does | Must not |
|---|---|---|
| 1 — partition | scaffolds the satellite in its own `microservices/<sat>/` tree, moves entities, places invariants, carries the consistency annotations | solve identity or loading |
| 2 — root split | identity, loading across the boundary, copy-on-write on two roots, copy constructors, event publisher ids, deletion | touch workflows |
| 3 — coordination | dispatch, step splitting, forbidden states, the event quad, the satellite's own service/functionalities/controller, external references | wire the deployment unit |
| 4 — deployment | `ServiceMapping`, compose, pom, the two YAMLs, the application class, the database, test beans, the loss checks, the test gate | change anything layers 1–3 did not require |

**All four are mandatory**, exactly as on a merge: layers 1–3 leave a codebase that compiles and a
deployment where the satellite's commits address a service that was never created. Layer 1 leaves a
codebase that does not compile; that is expected until layer 3.

## What Must Be Preserved

- **The complete set of entities of `Aggr` is preserved across `{core ∪ satellite}`** — no entity
  is dropped or silently merged away.
- The `Saga*` variant of the new satellite aggregate is produced.
- All domain events currently published by `Aggr` must continue to be published (from whichever
  aggregate owns them post-split).
- Existing command handler method signatures must not change unless consolidation is necessary.
- All tests wired by `BeanConfigurationSagas.groovy` must pass.
- Invariants that require both core and satellite data must be converted to inter-aggregate checks explicitly.

---

## Mandatory log lines

Beyond the `GATE:` verdicts, append to the CLAUDE.md entry:

```
Topology: <Aggr> split into <Core> (<svc>-service, unchanged) + <Satellite> (<sat-infra>-service, port <n>/grpc <n>, <satinfra>db created empty — no data migrated); routes moved: <paths or none>; ServiceMapping.<SAT> added; satelliteDispatch: domain=broker, sagaCommit=<in-process | broker | n/a — satellite never saga-locked>
Impact: entities kept: <n>; entities moved: <n>; invariants — <n> @core, <n> @satellite, <n> converted to inter-aggregate (<names>), <n> duplicated; replicas re-homed: <n>; replicas duplicated: <n>; functionalities touched: <n>; saga steps added: <n>; commands created: <n>; DTOs: <n>; events re-homed: <n>; test beans added: <n>; tests: <n>; no references: <list>
```

The `Topology:` line states a **presence that was verified**: the deployment unit exists, and layer
4.11 proves it from the running system, not from the diff. It also carries the two facts an architect
cannot recover later — which routes moved, and that `<satinfra>db` starts empty because **no data is
migrated** (composition doc §2.4).

`sagaCommit=in-process` is the healthy verdict once the satellite has its own service and its name
obeys *Naming the satellite*; `broker` means the name does not match and every commit is crossing the
network to reach a handler in the same process; `unwired` means it reaches nothing at all.

Both lines are emitted **only when the split actually ran**. Cases 3, 4 and 5 emit neither — they emit
the `GATE:` verdicts and their stop block instead.

**Metrics block.** The lines above are prose for the architect. The same numbers also go into a
delimited `key: value` block, so checking them does not depend on parsing prose. No pipeline code
reads it yet — `RestrictionParser` looks only for its own two markers — so the block is additive:

```
<!-- REFACTOR_METRICS -->
operation: SPLIT_AGGREGATE
targetService: <svc>
satellite: <Satellite>
satelliteService: <sat>
satellitePort: <n>
dispatchDomain: broker | in-process
dispatchSagaCommit: in-process | broker | unwired | n/a
deploymentUnits: <pre> -> <post>
invariantsConverted: <names or none>
invariantsDuplicated: <names or none>
sagaStepsAdded: <n>
errorsPre: <n>
errorsPost: <n>
routesPre: <n>
routesPost: <n>
testsSagas: green | red
<!-- END_REFACTOR_METRICS -->
```

### `domain=broker` and `sagaCommit=unwired` are failures, not statistics

The dispatch verdict in `Topology:` and the metrics block comes from layer 3.1, which explains why the
satellite has two dispatch paths wired differently and gives each one's checks and `WARNING:` text.
`sagaCommit=broker` on its own is the operation's cost, not a failure.

### `invariants converted to inter-aggregate: 0` in case 2

Case 2 exists because straddling invariants were found. If the classifier returned case 2 and this
count is 0, the invariants were dropped rather than converted. Emit:

```
WARNING: case 2 reported <n> straddling invariant(s) but 0 were converted — the rules were removed
from verifyInvariants() without an event-enforced replacement, so guarantees that held before the
split no longer hold
```

---

# Analyst reference

The sections below are not part of the execution path. They are read by the Analyze and Plan
phases to decide *whether* to propose
`SplitAggregate`. During execution the classifier above has already settled that question.

## When This Operation Is Typically Applicable

`SplitAggregate` is a strong candidate when one or more of the following signals are present:

### Structural signals
- many entities under one aggregate root
- weak cohesion between subsets of entities
- multiple unrelated responsibilities in the same aggregate
- internal relation graph suggests separable clusters
- aggregate root is overloaded with many commands and rules

### Behavioral/coordination signals
- different workflows touch different subsets of the aggregate with minimal overlap
- one part changes often while another remains stable
- conflict or violation patterns are concentrated in one sub-area of the aggregate
- runtime behavior suggests one local consistency boundary is trying to do too much

### Consistency/maintainability signals
- too many invariants attached to one aggregate
- one invariant actually bundles several distinct business rules
- violations are hard to localize or debug
- developers cannot clearly explain aggregate ownership boundaries

---

## When This Operation Is Usually NOT Appropriate

Do not recommend `SplitAggregate` just because an aggregate is large.

This operation is usually a poor fit when:
- the aggregate is large but still cohesive
- most rules truly require atomic local consistency
- the split would create many new inter-invariants
- the split would only move complexity into SAGA/event logic
- the real issue is deployment/scaling rather than the domain boundary — the catalogue has no
  operation for that, so say so rather than splitting the aggregate to approximate it
- the apparent complexity is caused by poor implementation, not poor aggregate boundaries

---

## Expected Quality Attribute Impact

### Performance
Expected effect: **mixed**

Positive:
- smaller aggregate operations may become lighter
- less data loaded or locked per command
- clearer locality for some workflows

Negative:
- former local calls/checks may become distributed
- more SAGA/event coordination may appear
- critical path can worsen if the split creates network boundaries later

Strength:
- positive only when aggregate size itself is the performance issue
- negative when the split introduces too much coordination

---

### Consistency
Expected effect: **mixed to negative unless carefully designed**

Positive:
- rules may become easier to understand and enforce correctly
- smaller aggregates can reduce internal rule complexity

Negative:
- former local consistency may become distributed consistency
- new inter-invariants may appear
- more event lag / coordination failure modes may be introduced

Strength:
- positive when the original aggregate had correctness issues due to excessive complexity
- negative when atomicity was genuinely needed across the split parts

---

### Maintainability
Expected effect: **often positive**

Why:
- better cohesion
- clearer responsibilities
- easier rule attribution
- smaller aggregate mental model
- simpler ownership

Possible downside:
- too fine-grained split can create fragmentation
- more coordination code and conceptual overhead may appear

Strength:
- strong when the aggregate was genuinely doing too much

---

## Dependencies With Other Operations

`SplitAggregate` may require or imply:

- `CreateReplica(E, A1 -> A2, fields, syncEvent)` if local read access is lost and latency becomes a problem
- workflow/SAGA adaptation after the split
- `MergeAggregates(A1, A2 -> M)` to reverse the split if the new boundary proves wrong

---

## Comparison With Nearby Operations

### vs `CreateReplica`
- `SplitAggregate` redraws the aggregate boundary comprehensively
- `CreateReplica` leaves the boundary intact and adds a local read copy across it

Use `CreateReplica` when the boundary is sound and only read latency hurts.  
Use `SplitAggregate` when the aggregate boundary itself is too broad or mixed.

### vs `MergeAggregates`
- `SplitAggregate` divides one boundary into two
- `MergeAggregates` collapses two into one

They are inverses: reach for the split when cohesion is the problem, the merge when coupling is.

---

## Decision Heuristics

Prefer `SplitAggregate` when:

- aggregate `A` has low cohesion across internal parts
- multiple unrelated responsibilities are visible
- different entity clusters support different workflows
- internal rule complexity is a major source of maintainability or correctness pain
- the split can be aligned with clear domain semantics

Reject or lower confidence when:

- most invariants truly need one local consistency boundary
- the split would mainly transform one domain problem into many coordination problems
- runtime pain is caused by service placement rather than aggregate design
- there is no meaningful way to partition entities and invariants

---

## Output Format

When evaluating this operation, use:

### Operation
`SplitAggregate(A -> A1, A2, mode)`

### Intent
Explain why one consistency boundary should become two.

### Preconditions
List explicit structural, behavioral, and semantic assumptions.

### Before
Describe the current oversized or mixed aggregate.

### After
Describe the resulting two-aggregate structure and new dependencies.

### QA Impact
- Performance:
- Consistency:
- Maintainability:

### Risks
Main downsides.

### Dependencies
Required companion or follow-up operations.

### Recommendation
State whether this is:
- strong candidate
- plausible but risky
- not recommended

---

## Golden Rule

Do not recommend `SplitAggregate` only because an aggregate is large.

Recommend it when:
- the aggregate is large **and** insufficiently cohesive, or
- one consistency boundary is trying to enforce multiple semantically distinct responsibilities, and
- the resulting distributed coordination cost is acceptable or manageable.

---

