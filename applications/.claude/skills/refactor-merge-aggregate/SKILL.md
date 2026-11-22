---
name: refactor-merge-aggregate
description: Dissolve aggregate B into aggregate A so one root owns both, collapsing B's service and database into A's. Use for the MERGE_AGGREGATES operation. Arguments "<AggrA> <AggrB> [--entities ...] [--project-path <path>]"
argument-hint: "<AggrA> <AggrB> [--entities E1,E2] [--invariants I1,I2] [--project-path <path>]"
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
Skill: refactor-merge-aggregate
Invocation: <full /skill args as given>
Result: CHANGED | NO_CHANGE | REJECTED
Summary: <1-3 sentences describing what changed, or why nothing changed, or why the operation was rejected>
<!-- end-refactor-log -->
" >> CLAUDE.md
```

This step is mandatory. Do not skip it under any circumstances.

When the *Ownership Classifier* returns **case 4** or **case 5**, this entry is the **only** file you
write — it carries `Result: NO_CHANGE`, the `GATE:` verdicts, and a stop block the pipeline reads back
from this file: a `<!-- REFACTOR_PREREQUISITE -->` block for case 4 (reachable, but not in one step),
a **non-blocking** `<!-- REFACTOR_RESTRICTION -->` for case 4's *degenerate chain* (ambiguous
ownership), or a **blocking** `<!-- REFACTOR_RESTRICTION -->` for case 5 (not reachable).

### Rules for stop blocks

The pipeline reads **only the entry you are appending now**, and rejects any block that breaks these:

1. **Only about the pair you were given.** `targetService` and `secondaryTarget` must name the two
   aggregates in *this* invocation. Never emit a block about a different pair, however confident you
   are that the other pair is unmergeable — that is Analyze's job, and a block naming another pair
   would block it for every future run.
2. **Only on a run that changed nothing.** A block is a statement that the operation could not be
   applied. If you changed code, report `Result: CHANGED` and emit no block.
3. **Never carry one forward.** `CLAUDE.md` already contains entries from earlier runs, some with
   their own blocks. Do not read them, restate them, or re-emit their verdicts. They are already
   recorded.
4. **Never on an operation that already ran.** A dissolved `<AggrB>` has no ownership signals left,
   so classifying it from scratch routes to case 5 — a permanent block on a pair that merged
   successfully. *Step −1* catches this first; when it does, report `NO_CHANGE` with no block.

---

# Skill: MergeAggregates for DDD / SAGA Microservice Architectures

## Purpose

`MergeAggregates(A, B -> M)` merges two domain aggregates into a single aggregate `M`. `AggrA`
survives and absorbs `AggrB`; `AggrB` **stops being an aggregate root** and its entities become
members of `M`.

An aggregate is a **domain boundary**; a service is a **runtime boundary**. Relocating `AggrB`'s
package into `<svcA>`'s tree while it keeps its root, `aggregateId`, variants, repository and factory
is a `MergeServices` — a different operation, not in this catalogue. This skill never produces that
shape: either `AggrB` dissolves, or the run reports the operation inapplicable and changes nothing.

> The **Execution Mode** always performs the deployment-topology collapse as well, because the
> pipeline measures a running system: leaving two services deployed after merging their aggregates
> makes the Monitor phase report metrics for a topology the domain model no longer describes.

## Section inventory

The section inventory all four operation skills share is
[`../.claude/docs/concepts/refactoring-surface.md`](../../docs/concepts/refactoring-surface.md) — what
artefacts exist, what each operation does to each, and the sets that must change together.

---

## Operation Signature

`MergeAggregates(A, B -> M)` — `A` survives, `B` dissolves, `M` is `A`'s root now owning `B`'s
entities. There is no third name: nothing is renamed.

## Layers this operation runs

All four, in order, and **none of them is optional**:

| | Layer | What it settles |
|---|---|---|
| 1 | `layers/1-aggregate.md` | `AggrB` becomes a member entity: entities, invariants, replicas, annotations, root machinery |
| 2 | `layers/2-root-join.md` | identity, loading, mutation, copy constructors, events, deletion |
| 3 | `layers/3-coordination.md` | services, handlers, commands, workflows, events, forbidden states, semantic locks |
| 4 | `layers/4-deployment.md` | the deployment unit disappears: routing, compose, pom, yamls, application class, DB, tests |

Stopping at layer 3 leaves a codebase that compiles and a deployment that does not run — the service
whose main class and profile are gone is still being started
(`../.claude/docs/concepts/refactoring-surface.md` §2, *the topology rows*).

---

## Preconditions

- `AggrA` **owns** `AggrB` — every `AggrB` belongs to exactly one `AggrA` for its whole life. This is
  the only hard precondition, and the *Ownership Classifier* below decides it from the code.
- Inter-invariants between `A` and `B` can become local checks in `M.verifyInvariants()`.
- Externally visible business behaviour is unchanged: the same REST paths answer, the same events
  reach the same consumers, the same errors are raised.

**Not preconditions** — these are common misreadings that make the operation look impossible when it
is not:

- *`AggrB` being created by its own service.* Every aggregate is created by its own service; that
  service is what the merge dissolves.
- *`AggrB` being referenced by id from a third aggregate.* That does not block the merge — it decides
  that the absorbed entity keeps its identifier as a member field so those references keep resolving
  (layer 2).
- *`AggrB` having its own controller, functionalities or saga states.* All of it moves.

---

## Before / After

**Before:** `A` and `B` are separate roots in separate services. Workflows coordinate them with saga
steps, semantic locks and compensations. Some invariants are distributed — typically as a counter on
`A` that a remote command from `B`'s flow keeps current. `B` holds a replica of `A`.

**After:** one root `M`. `B`'s entities are members of it. Cross-aggregate steps become direct
service calls in one `UnitOfWork`. Distributed invariants become synchronous checks. `B`'s replica of
`A` is redundant. One service, one database, one command channel.

## Risks

- an oversized aggregate, and weaker separation of concerns
- references to the dissolved root left un-repointed — silent, not a compile error
- external event subscriptions that stop matching (§2.6 of the composition doc)
- harder future splitting if the merge was wrong
- confusing a domain merge with a service merge

---

## Execution Mode

### Invocation

```
/refactor-merge-aggregate <AggrA> <AggrB> [--entities E1,E2] [--invariants I1,I2] [--project-path <path>]
```

| Param | Required | Meaning |
|-------|----------|---------|
| `AggrA` | yes | Surviving aggregate — absorbs the other. May be given as the aggregate class (`Course`) or the service name (`course`) |
| `AggrB` | yes | Dissolved aggregate — **all** of its entities and invariants migrate into `AggrA` |
| `--entities` | no | Informational only. Under dissolution **every** `AggrB` entity migrates whether listed or not; an entity missing from this list still moves, logged `WARNING: entity <X> auto-migrated from AggrB`. It never excludes an entity |
| `--invariants` | no | Informational only. The plan builder passes the inter-invariants it expects to become intra (`BECOMES_INTRA`). The skill converts **every** inter-invariant anchored on `AggrB` regardless (*Six further readings*, 4); a listed name that is not one is ignored with `WARNING: invariant <X> is not an inter-invariant anchored on <AggrB>` |
| `--project-path` | injected | Application module being refactored, **relative to the repository root** (e.g. `applications/quizzes`). Supplied by the pipeline; falls back to `applications/quizzes` when absent |

**Arity is exactly two.** If more than two positional names are supplied, ignore the extras and emit
`WARNING: more than two aggregates supplied; merging only <AggrA> and <AggrB>`. Merging a third
requires a second invocation.

### Resolving names

`<svcA>`/`<svcB>` are **package directory names**, not `<Aggr>.toLowerCase()`. Two aggregates in
quizzes break that formula (`QuizAnswer` lives in `answer/`), so resolve by looking:

```bash
ls -d src/main/java/org/anonymous/msrefactor/*/microservices/*/
```

Match the argument against the directory name first, then against the root class name inside
`<svc>/aggregate/`. Record both forms — `<AggrA>`/`<svcA>`, `<AggrB>`/`<svcB>` — and use the package
form in every path and grep.

**Three names, not two.** `refactoring-surface.md` §1 (*The three names a placeholder can stand for*)
is the authority; resolve all three before layer 1 and write them into the log:

| Form | What it is | It names |
|---|---|---|
| `<SvcA>`/`<SvcB>` | the package directory, capitalised | `<Svc>CommandHandler` and `<Svc>ServiceApplication`. **The handler's name is load-bearing**: the bean is resolved as `getServiceName() + "CommandHandler"`, and `StreamCommandChannelRegistrar` derives the channel by stripping that suffix |
| `<AggrA>`/`<AggrB>` | the root class | the root, `Saga<Aggr>`, `<Aggr>Service`, `<Aggr>Functionalities`, `<Aggr>Controller`, `<Aggr>EventProcessing` |
| `<FamA>`/`<FamB>` | the member-family prefix | `<Fam>Dto`, `<Fam>Factory`, `<Fam>Repository`, `<Fam>CustomRepository`, `<Fam>CustomRepositorySagas`, `<Fam>SagaState`, `<Fam>EventHandling`, the abstract `<Fam>EventHandler`, `<Fam>Subscribes<Event>` |

**`<Fam>` is discovered, never derived.** `ls <svc>/aggregate/ <svc>/aggregate/sagas/states/` and read
the prefix off the files. `execution` is the case that matters: root `Execution`, family
`CourseExecution`, so `<AggrA>SagaState` would be `ExecutionSagaState` and the file is
`CourseExecutionSagaState.java`. `answer` is the case for `<Svc>`: root `QuizAnswer`, service `answer`,
so the handler is `AnswerCommandHandler` and the application class `AnswerServiceApplication`.

Both divergences are silent. Six of the eight services have all three forms equal, so a wrong
derivation greps for a file that is not there — and a check whose expected result is empty reads that
as a pass. This is the same discipline layer 4.2 calls *Discover, do not assume*, applied from layer 1
onwards.

**Reading the layer files.** Layer 1.4 writes the three forms out explicitly, because that is where the
files are enumerated. Elsewhere the layers write `<AggrB>Dto`, `<AggrB>SagaState`,
`<AggrB>EventHandling` and `<AggrB>Subscribes<Event>` for brevity: the prefix on disk for those four is
`<FamB>`, and it is `<AggrB>` only because six services make them the same string. `<AggrB>Service`,
`<AggrB>Functionalities`, `<AggrB>Controller`, `<AggrB>EventProcessing` and `Saga<AggrB>` mean the root
literally.

### Path conventions

Two symbols, and they are not interchangeable — mixing them is how a `grep` silently returns
nothing and a whole class of the inventory goes unhandled:

- **`<projectPath>`** — the `--project-path` value, relative to the **repository root**. Use it for
  git pathspecs and anything addressed from the repo root.
- **Your working directory is `<projectPath>`.** Every shell snippet is written from there, so
  in-module paths are relative: `src`, `docker-compose.yml`, `pom.xml` — *not*
  `<projectPath>/src`, which from the working directory would resolve to
  `<projectPath>/<projectPath>/src` and match nothing.

Reference material outside the module is reached by climbing out of it: `../.claude/docs/`,
`../../docs/`, `../../simulator/`.

**Every path in this skill — `SKILL.md`, `layers/` and `cases/` alike — is relative to the working
directory**, never to the file it appears in. The one exception is a markdown link *target*, which
renderers resolve from the file; its visible text still follows the rule. `../.claude/docs/` is the
authoritative concepts tree: `../../docs/` holds older copies of several of the same files that have
since diverged, so never read a concept from there.

> **Locate by package path, never by class-name prefix.** `<AggrB>`-prefixed names collide with
> unrelated aggregates: `Course*` vs `CourseExecution*` matches 13+ files across `execution`,
> `quiz` and `tournament` that must NOT be touched. Always scope to
> `microservices/<svcB>/` and `commands/<svcB>/`.

---

## Mandatory read order

This skill is split across files so each decision is made once, with only its own rules in view.
Read them **in this order**, and read each one fully before acting on it:

| # | File | When |
|---|------|------|
| 0 | `*-composition.md` in the working directory (`quizzes-composition.md` for the quizzes module) | always — **§3** is the ownership graph the classifier reproduces and **§4 the per-pair verdict already computed** (feasible / inverted / chain / not mergeable), so read §4 before re-deriving what it answers. It is an **input, not a gate**: the classifier stays the authority, and a disagreement means the tree moved since — say so in the log. If the module has none, derive §3 yourself with the classifier's own checks and say so in the log. |
| 1 | `../.claude/docs/concepts/refactoring-surface.md` | always — §1 is the section inventory Step 0 classifies into, §2 the MERGE column, §3 the sets that must change together. It is shared with the other three operation skills; this file and its layers are the authority wherever they are more specific. |
| 2 | this file, through *Step 0* | always |
| 3 | `cases/<n>-*.md` for the case the classifier returned | always — exactly one |
| 4 | `layers/1-aggregate.md` → `2-root-join.md` → `3-coordination.md` → `4-deployment.md` | when the case says to |

Skill files are at `../.claude/skills/refactor-merge-aggregate/` relative to the working directory;
shared docs at `../.claude/docs/`.

**Each layer file ends with a required log line.** Emit all of them, in order, in the CLAUDE.md
entry:

```
LAYER 1 (aggregate): <one line>
LAYER 2 (root join): <one line>
LAYER 3 (coordination): <one line>
LAYER 4 (deployment): <one line>
```

A missing `LAYER n:` line means that file was not applied. Do not emit a line for a layer you did not
work through, and do not report `Result: CHANGED` with a layer missing.

---

## Step −1 — Already applied?

Runs **first**, before the classifier. A retry or a resumed run meets code this operation already
changed; classified from scratch, a dissolved `<AggrB>` has no ownership signals and routes to case 5.

```bash
ls -d src/main/java/org/anonymous/msrefactor/*/microservices/<svcB>/ 2>/dev/null
grep -rn "class <AggrB>\b" src --include=<AggrB>.java | grep "/microservices/<svcA>/aggregate/"
```

| `<svcB>/` | `<AggrB>.java` under `<svcA>/aggregate/` | Verdict |
|---|---|---|
| absent | present, no `extends Aggregate` | **already applied** — `Result: NO_CHANGE`, `Summary: already applied in a previous run`; no stop block, no `LAYER` lines |
| absent | absent | **unknown name** — `WARNING: <AggrB> resolves to no package and no class`, `Result: NO_CHANGE`, no stop block |
| present | present, no `extends Aggregate` | **partially applied** — skip the classifier and run Step 0 and the layers; their deletions are idempotent |
| present | absent | not applied — continue to the classifier |

A `NO_CHANGE` that touched only `CLAUDE.md` is recorded `NOT_APPLICABLE` with no constraint
(`ExecuteService` step 3d) — nothing about the pair outlives the run.

---

## Step 0 — Global impact inventory

Run **before any mutation**, after the classifier returns case 1, 2 or 3. Its output is the work list
for all four layers.

The merge is not confined to the two merged aggregates: **any** aggregate that references `AggrB` is
affected, and not only through functionalities. Discover in one pass:

```bash
grep -rln "microservices\.<svcB>\.\|commands\.<svcB>\.\|ServiceMapping\.<AGGB>\|<AggrB>Dto\|<aggrB>AggregateId" \
  src | grep -v "/microservices/<svcB>/"

# semantic locks aimed at AggrB — release compensations name no AggrB state, so also list every
# file that wraps a command for <svcB> in a SagaCommand
grep -rn "<AggrB>SagaState\." src --include=*.java | grep -v "/microservices/<svcB>/\|/states/"
grep -rl "ServiceMapping\.<AGGB>" src --include=*.java | xargs grep -l "SagaCommand" | grep -v "/microservices/<svcB>/"

# every declared copy of either root, in every service — layer 1.7 decides each name, and a class
# that copies a root without importing its package is invisible to the first grep
grep -rn "@Replica" src --include=*.java | grep -E "\"<AggrA>\"|\"<AggrB>\""
```

Classify every hit into exactly one of these eight classes — they are the coarse grouping of
`refactoring-surface.md` §1, whose §2 gives the MERGE action for each of its 27 sections. **Each class
has a different action**, and fixing only what breaks compilation leaves the rest silently wrong:

| Class | Real example (`course`) | Action |
|-------|------------------------|--------|
| **Replica** — any class whose `@Replica` list names `AggrA` or `AggrB`, in **any** service | `TopicCourse`, `QuestionCourse` (inside the merge); `QuestionTopic`, `TournamentTopic`, `QuizQuestion` (third parties) | classify in layer 1.7: inside `M`, in a third service, or naming a root the merge does not touch |
| **DTO** embedding `AggrB` fields or its id | `CourseExecutionDto.courseAggregateId`, `TopicDto` | keep the field name and the id it carries — layer 2 retains `AggrB`'s identifier |
| **Functionality** (sagas) | `CreateCourseExecutionFunctionalitySagas`, `CreateTopicFunctionalitySagas` | collapse or re-point per layer 3 |
| **Command / handler arm** | `commands/course/*` | classify in layer 3.5; the quad is class + `case` + handler method + service method |
| **Semantic lock on `AggrB`** | `CreateTopicFunctionalitySagas:43` (`CourseSagaState.READ_COURSE` on a course id) | reading 6, then layer 3.9 — the lock, its release compensation and any `forbidden` entry go together |
| **Event consumption** | (course publishes none) | classify in layer 3.6 — the quad is subscription + handler + `@Scheduled` + `process…Event`; act on all four |
| **Controller** (`coordination/webapi`) | `CourseController` | move to `<svcA>`; delete if an empty stub |
| **Test** `.groovy` | 5 files, incl. `CreateCourseExecutionCompensationTest`, `AddParticipantAsyncTest` | re-point imports and wiring (layer 4) |

**Replica rule — layer 1.7 owns it.** In one line: a copy that ends up *inside* `M` stops being a copy
and goes, because the state it copied is now directly reachable; a copy held by a *third* aggregate
survives, keeps pointing at the same identifier (layer 2 retains it), and has its declaration
re-pointed to `AggrA`, the root that survives. The full decision — which names change, which classes
are deleted, and the one case where a copied field is kept — is 1.7(a)–(b), and the objects it drags
into layers 2, 3 and 4 are listed there. Do not settle a replica from this table.

**Aggregates with no references** are listed explicitly as `no references` in the log. Verified
absence, not assumed absence.

Record the per-class counts in the CLAUDE.md `Impact:` line.

## Step 0.5 — Baseline

Runs right after Step 0, **before any mutation**. Every layer's `Verify` checks for *leftovers*: an
empty grep proves nothing was left behind, never that nothing was lost. The *Completeness contract*
promises the same errors, routes and events survive — these anchors are what lets the layers check it.
Written outside `<projectPath>`, which the pipeline stages whole:

```bash
B="${TMPDIR:-/tmp}/refactor-baseline-<svcA>-<svcB>"; mkdir -p "$B"
M=src/main/java/org/anonymous/msrefactor/quizzes/microservices
ERR='(QuizzesErrorMessage\.|QuizzesException\(\s*)[A-Z][A-Z0-9_]+'   # static import and qualified form

grep -rhoE "$ERR" $M/<svcA>/aggregate $M/<svcB>/aggregate --include=*.java | grep -oE '[A-Z][A-Z0-9_]+$' | sort -u > "$B/errors-aggregate"
grep -rhoE "$ERR" $M/<svcA> $M/<svcB> --include=*.java | grep -oE '[A-Z][A-Z0-9_]+$' | sort -u > "$B/errors"
grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping\([^)]*\)' $M/<svcA> $M/<svcB> --include=*.java | sort -u > "$B/routes"
ls src/main/java/org/anonymous/msrefactor/quizzes/events/ | sort > "$B/events"   # shared package, not per service
for s in <svcA> <svcB>; do                                                                # the event quad, per service
  echo "$(ls $M/$s/notification/subscribe/*.java 2>/dev/null | wc -l) \
$(h=$(ls $M/$s/notification/handling/handlers/*.java 2>/dev/null | wc -l); echo $(( h > 0 ? h - 1 : 0 ))) \
$(cat $M/$s/notification/handling/*EventHandling.java 2>/dev/null | grep -c '@Scheduled') \
$(cat $M/$s/coordination/eventProcessing/*.java 2>/dev/null | grep -c 'public void process')" > "$B/quad-$s"
done
```

| Anchor | Checked in | Rule |
|---|---|---|
| `errors-aggregate` | layer 1.8 | post ⊇ pre — a missing constant is a dropped invariant |
| `errors` | layer 4.11 | post ⊇ pre — a missing constant is a dropped invariant or guard |
| `routes` | layer 4.11 | post ⊇ pre — layer 2 retained identifiers precisely so these keep answering |
| `events` | layer 3.6 | post = pre − the events 3.6 deleted, by name |
| `quad-*` | layer 3.6 | differential; the handler figure is net of the abstract base, and 0 for a service with no handlers (`course`) rather than −1 |

A constant or route that legitimately disappears is named in the log with its reason. One that
disappears silently stops `Result: CHANGED`.

---

## Ownership Classifier

Runs before any mutation, after Step 0's greps are available. It returns a **case**, not a pass/fail.

> **The Analyze phase now pre-computes the same verdict** (`OwnershipForest` in the refactor tool), so
> a pair with no ownership edge no longer reaches this skill as a candidate. The two implementations
> must not drift: O1–O3 here are the specification, and a change to them belongs in both.

**The operation is agnostic to argument order.** `MergeAggregates(A, B)` and `MergeAggregates(B, A)`
denote the same operation on the same pair; the order expresses a **preference for which root
survives, not a constraint**:

| | |
|---|---|
| both directions feasible (case 3) | honour the order given — `AggrA` survives |
| one direction feasible (cases 1, 2) | take that direction, whatever was typed |
| neither (cases 4, 5) | order is irrelevant |

A "wrong" order is therefore never an error. It is at most a preference the domain cannot satisfy, and
case 2 records that in the log.

`AggrA` can absorb `AggrB` only if it **owns** it: every `AggrB` belongs to exactly one `AggrA` for
its whole life. Three independent signals decide it — the same three §3 of the composition doc uses,
so your result must reproduce that table:

| | Signal | Check |
|---|---|---|
| **O1** | `B`'s root holds a **single-valued** replica of `A` | see below — this is the authoritative check, and it is the one that decides candidacy |
| **O2** | `B` is created in `A`'s scope | `B`'s creation `@PostMapping` path contains `{<a>AggregateId}`, **or** `create<AggrB>` takes `<a>AggregateId` as its first argument |
| **O3** | a parent-scoped collection query exists | `commands/<svcB>/Find<AggrB>sBy<AggrA>Id*.java`, or `find<AggrB>sBy<AggrA>Id` in `<AggrB>Service` |

### Running O1

**Do not test O1 by looking for a file named `<AggrB><AggrA>.java`.** That check is wrong in both
directions in this codebase:

- **False negatives** — the class name does not reliably contain the owner's aggregate class name.
  `QuizAnswer`'s link to `Quiz` is `AnsweredQuiz`; `Tournament`'s and `Quiz`'s links to `Execution`
  are `TournamentCourseExecution` and `QuizCourseExecution` (the root class is `Execution`, its
  member family is named `CourseExecution*`).
- **False positives** — `microservices/question/aggregate/QuestionTopic.java` exists, but a Question
  holds a `Set<QuestionTopic>`. Topic does not own Question and Question does not own Topic. Reading
  `QuestionTopic` alone cannot tell you: it holds a single `topicAggregateId` like any other replica.
  **The cardinality lives on the owning side, in the root's field declaration.**

Read the root class instead. A single-valued `@OneToOne` member is an ownership candidate; a
`@OneToMany` / `Set` / `List` member is not:

```bash
# 1 — B's single-valued members are the ownership candidates
grep -n "@OneToOne" -A2 microservices/<svcB>/aggregate/<AggrB>.java

# 2 — for each, which aggregate's id does it hold?
grep -n "private Integer .*AggregateId;" microservices/<svcB>/aggregate/<Member>.java
```

Step 2 names the owner: `TopicCourse` holds `courseAggregateId` → the candidate owner is `Course`.

**Resolve the owner by the member's DTO constructor, not by the field name.** Field names are
role-based as often as type-based — `TournamentCreator.creatorAggregateId` and
`AnswerStudent.studentAggregateId` both mean *User*. The member class's other constructor takes the
owner's DTO, and **the package that DTO lives in is the owner's package**:

```bash
grep -n "public <Member>(" microservices/<svcB>/aggregate/<Member>.java   # → <Owner>Dto
```

`AnswerStudent(UserDto)` → `user/`; `QuizCourseExecution(CourseExecutionDto)` → `execution/`, since
`CourseExecutionDto` lives in `microservices/execution/aggregate/`. Where no DTO-typed constructor
exists (`TournamentCreator` takes raw fields), fall back to the field name and say in the verdict
that you did.

**O1 is necessary but not sufficient, and it is frequently ambiguous.** `QuizAnswer` has three
single-valued members (`AnswerCourseExecution`, `AnswerStudent`, `AnsweredQuiz`) and so three
candidate owners; `Tournament` has two. The rule is **O1 and at least one of O2/O3**. When several
candidates qualify, the owner is the one with more signals — for `QuizAnswer` that is `Quiz` (3/3)
over `User` (2/3) and `Execution` (1/3). A genuine tie is ambiguous ownership and not mergeable.

Evaluate ownership in **both** directions, then dispatch:

| A owns B | B owns A | Case | File |
|---|---|---|---|
| yes | no | **1 — ownership merge** | `cases/1-ownership-merge.md` |
| no | yes | **2 — inverted direction** | `cases/2-inverted-direction.md` |
| yes | yes | **3 — peer merge** (1:1) | `cases/3-peer-merge.md` |
| no | no | *walk the owner chains →* | see below |

When neither owns the other, **do not stop**. Ownership forms a forest
(`../.claude/docs/concepts/aggregate-merge.md` §5), so walk both owner chains and intersect them.

**`ownerChain(X)`** is `[X, parent(X), parent(parent(X)), …]`. It **starts with `X` itself**, and each
`parent` is the single-valued O1 link of the element before it — **O1 alone**, because the chain is
structure, not an ownership verdict. Where a root has several O1 candidates, follow the one with the
most O1–O3 signals; a tie ends the chain there.

```
ownerChain(question)  = [question, course]     ownerChain(topic)  = [topic, course]
LCA = course, not an operand                   -> case 4

ownerChain(execution) = [execution, course]    ownerChain(course) = [course]
LCA = course, an operand                       -> degenerate chain (ambiguous ownership)
```

| Lowest common ancestor | Case | File |
|---|---|---|
| exists, **not** an operand | **4 — sibling merge** (decompose into a chain) | `cases/4-sibling-merge.md` |
| exists, **is** an operand | **4 — degenerate chain**: an O1 link exists but fails O2/O3, so the chain would be the pair itself. A genuine O1 tie between candidate owners is the same verdict | `cases/4-sibling-merge.md`, *Degenerate chain* |
| none | **5 — no relation** (the only blocking refusal) | `cases/5-no-relation.md` |

**Cite what you found, not what this document says.** Each verdict names the file and line in *this*
codebase that decided it. Reproducing the composition doc's prose without having run the checks is
not a verdict.

### Six further readings — inputs, not blockers

Run **all six** once the case is known; each selects a rule inside a layer. **None of them stops the
run.** Items 4, 5 and 6 are the easiest to skip and the most silent when skipped.

| Reading | Feeds |
|---|---|
| 1 — third-party references to `AggrB`'s id | layer 2.1 (retain or drop the identifier) |
| 2 — range of `AggrB`'s invariants | layer 1.5 (placement table) |
| 3 — events consumed outside `M` | layer 3.6 (delete or keep), layer 2.5 (publisher id) |
| 4 — `AggrA`'s inter-invariants anchored on `AggrB` | layer 1.5 (become intra checks in `verifyInvariants()`) |
| 5 — every `@Replica` naming `AggrA` or `AggrB` — **including the ones in third services**, which neither package shows — and `AggrB`'s `@ServiceGuard` | layer 1.7 (the per-name decision), layer 3.1 (guards move with the service methods), layer 3.8 (the gate across services) |
| 6 — are the lock states checked? | layer 1.4 (delete or move `<AggrB>SagaState`), layers 3.4(a) and 3.9 (drop, retarget or keep the lock in-process) |

1. **Is `AggrB` referenced by id from a third aggregate?**
   `grep -rn "<aggrB>AggregateId" src | grep -v "/microservices/<svcA>/\|/microservices/<svcB>/"`
   Non-empty → layer 2 **retains** `AggrB`'s identifier on the member. Empty → it may be dropped.
2. **Where do `AggrB`'s invariants range?** Read `<AggrB>.verifyInvariants()`. Own state → a
   member-level check. `A ∪ B` → an intra-aggregate check, and the operation's consistency payoff —
   name it in the log. A third aggregate → it stays an inter-invariant, subscription re-pointed.
3. **Does `AggrB` publish events consumed outside `M`?** Read `events/` and the consumers'
   `getEventSubscriptions()`. Feeds layer 3's re-pointing rules.
4. **Which of `AggrA`'s inter-invariants was about `AggrB`?** Read the `@InterInvariant` annotations
   in `<AggrA>.getEventSubscriptions()`. A rule anchored on `AggrB` **becomes intra** once one root
   owns both: delete its registering method, remove its rule name from `AggrA`'s
   `INTER-INVARIANTS` header, and add the check to `verifyInvariants()` under the same name in the
   `INTRA-INVARIANTS` block. That conversion is the operation's consistency payoff — name it in the
   log. The reverse mistake is silent: leaving the rule in the header with no method claiming it
   makes the Monitor report it as unmaintained.

`AggrB`'s own inter-invariants move to `AggrA` unchanged — their registering methods, their
`@InterInvariant` annotations and their rule names in the header block all migrate together.

5. **Which of `AggrA`'s replicas stop being replicas?** A class in `AggrA` annotated
   `@Replica("<AggrB>")` copied an aggregate that is now a local member: **remove the annotation**, or
   the class if the merge folds it into `AggrB`'s own entity. Leaving it makes the Monitor report a
   cross-aggregate copy inside a boundary that no longer has one — the mirror image of the
   inter-invariant conversion above, and just as silent. `@ServiceGuard` annotations on `AggrB`'s
   service methods move with those methods to `AggrA`'s service.

6. **Are the lock states checked?** A semantic lock has an effect only if something reads it back.
   For an aggregate `X`, its states are *checked* when either grep returns a hit:
   ```bash
   # a forbidden-state list names one of X's constants — the list is usually built a few lines
   # before the setForbiddenStates call, so search per file, not per line
   grep -rl "setForbiddenStates" src --include=*.java | xargs grep -n "<X>SagaState\." | grep -v "setSemanticLock"
   # X's repository filters on the saga state (the …ForSaga queries)
   grep -rn "sagaState" src --include=*Repository.java | grep "/microservices/<svcX>/aggregate/"
   ```
   Both empty → *unchecked*: the lock is a marker nothing reads. Evaluate `AggrB` always, and `AggrA`
   whenever a step that 3.4(a) collapses took a lock on it. Emit one line per aggregate evaluated:
   `GATE: <X> lock states <checked|unchecked> — <evidence>`.

   In quizzes today Topic, Question and Quiz are unchecked; Course (`CourseRepository`),
   CourseExecution (forbidden lists and `CourseExecutionRepository`), Tournament (forbidden lists and
   `TournamentRepository`) and QuizAnswer (`QuizAnswerRepository`) are checked.

### Verdict lines

Emit one line per signal you evaluated, with evidence:

```
GATE: O<n> <satisfied|not satisfied> — <what is true in the code, with file:line>
```

and a single summary line above them:

```
GATE: <AggrA> owns <AggrB> (<n>/3) — case <n>
```

`GATE:` is a verdict, not a fault. Keep `WARNING:` for things that actually went wrong, so a real
problem still stands out in the log.

---

## Completeness contract

Whatever the case, if the merge runs it runs completely:

- **Losslessness.** The merged entity set equals `entities(AggrA) ∪ entities(AggrB)`. Because `AggrB`
  is dissolved, **every** `AggrB` entity and nested child moves. Any not named in `--entities` are
  auto-migrated with `WARNING: entity <X> auto-migrated from AggrB`. **No entity is ever dropped.**
- **Variants.** `Saga<AggrB>` folds into `AggrA`'s variant; `AggrB`'s base class, variant, repository
  and factory are removed once unused. Verify which of those files exist before deleting: `quiz` has
  no custom sagas repository at all (`refactoring-surface.md` §3.3).
- **Invariants.** Every invariant of both aggregates survives in `M`.
- **Behaviour.** The same REST paths answer, the same events reach the same external consumers, the
  same errors are raised. Saga *end states* are preserved; step counts are expected to drop.

Malformed **arguments** never abort the run — auto-resolve to a safe default and record a `WARNING:`
reflected in the `Summary:`. Argument *order* is not even malformed: see the order-agnostic rule in the
classifier.

That licence does not extend to **feasibility**. Case 4 reports `NO_CHANGE` and emits the chain that
would make the pair mergeable; case 5 reports `NO_CHANGE` and refuses. Neither substitutes a different
operation — co-hosting two roots in one process is a `MergeServices`, and reporting it as this
operation would poison what Monitor measures and what Knowledge learns.

---

## Mandatory log lines

Beyond the four `LAYER n:` lines, append to the CLAUDE.md entry:

```
Topology: <svcB>-service dissolved into <svcA>-service (<svcA> keeps port <p> and <svcA>db; <svcB>db destroyed; commands collapsed: <n>; commands re-pointed: <n>; events deleted: <n>)
Impact: affected aggregates: <list>; no references: <list>; replicas retained: <n>; replicas now redundant: <n>; DTOs: <n>; functionalities: <n>; controllers: <n>; tests: <n>; external consumers referencing a member entity: <n>
```

Both lines are emitted **only when the merge actually ran**. Cases 4 and 5 emit neither — they emit
the `GATE:` verdicts and their stop block instead.

**Metrics block.** The lines above are prose for the architect. The same numbers also go into a
delimited `key: value` block, so checking them does not depend on parsing prose. No pipeline code
reads it yet — `RestrictionParser` looks only for its own two markers — so the block is additive:

```
<!-- REFACTOR_METRICS -->
operation: MERGE_AGGREGATES
survivingService: <svcA>
dissolvedService: <svcB>
commandsCollapsed: <n>
commandsRePointed: <n>
stepsCollapsed: <n>
eventsDeleted: <n>
invariantsToIntra: <names or none>
lockStatesChecked: <AggrB>=<checked|unchecked>
locksDropped: <n>
locksRetargeted: <n>
locksKeptInline: <n>
errorsPre: <n>
errorsPost: <n>
routesPre: <n>
routesPost: <n>
testsSagas: green | red
<!-- END_REFACTOR_METRICS -->
```

**`commands collapsed: 0` is a failure, not a statistic.** These lines are a report, so a skipped
layer reads as a successful run unless called out. If `commands collapsed` is 0 while layer 3's
collapse gate still returns hits, the run **must** emit:

```
WARNING: layer 3 not applied — <n> <svcA>-owned step(s) still send <svcB> commands through the
broker; the merge delivered no latency benefit and is a MergeServices in disguise
```

and reflect it in the `Summary:`. It is only legitimate for `commands collapsed` to be 0 when the
gate grep is empty — i.e. `<svcA>` owned no cross-boundary steps to begin with.
