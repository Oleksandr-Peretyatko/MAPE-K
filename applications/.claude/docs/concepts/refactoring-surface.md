# The Refactoring Surface

Every artefact a refactoring operation can touch, and what each operation does to it.

The four operation skills share a skeleton but each one discovers the application's shape on its own.
This is the shared inventory they read first, so that "which sections do I have to modify" is answered
once, in one place, instead of being reassembled from four `Step 0` tables and thirty layer
subsections.

> The application implements a single transactional model, sagas.

**Where things live** is not repeated here.
[`architecture.md` §Package Structure Convention](../architecture.md) is the canonical directory
layout and is already written sagas-only. This document adds the three things that exist nowhere
else: what each operation *does* to each section, which sections must change *together*, and how many
of each there are today.

---

## §1 — Sections

One row per artefact kind. The **Layer** column names the execution layer whose rules govern it.
The two boundary operations both decompose into those four layers, with the same numbering and the
same themes — `refactor-merge-aggregate/layers/1-aggregate.md` … `4-deployment.md` and
`refactor-split-aggregate/layers/1-partition.md` … `4-deployment.md`. `CreateReplica` and
`RemoveReplica` have no layer decomposition, so for them the column reads as a grouping and §2's
column is what tells them what to touch.

| # | Section | Path under `microservices/{svc}/` | Layer |
|---|---------|-----------------------------------|-------|
| 1 | Aggregate root | `aggregate/{Xxx}.java` | 1 Aggregate |
| 2 | Member entities | `aggregate/*.java` | 1 Aggregate |
| 3 | Replica classes | `aggregate/{Other}{Src}.java`, `@Replica` | 1 Aggregate |
| 4 | DTO | `aggregate/{Fam}Dto.java` | 2 Root join |
| 5 | Sagas variant | `aggregate/sagas/Saga{Xxx}.java` | 1 Aggregate |
| 6 | Saga states | `aggregate/sagas/states/{Fam}SagaState.java` | 1 Aggregate |
| 7 | Factory | `aggregate/{Fam}Factory.java` + `aggregate/sagas/factories/Sagas{Fam}Factory.java` | 1 Aggregate |
| 8 | Repository | `aggregate/{Fam}Repository.java`, **`aggregate/{Fam}CustomRepository.java`** (the query interface, **7 of 8**) + `aggregate/sagas/repositories/{Fam}CustomRepositorySagas.java` (**7 of 8** — `quiz` has neither) | 1 Aggregate |
| 9 | Service | `service/{Xxx}Service.java` | 3 Coordination |
| 10 | Command handler | `messaging/{Svc}CommandHandler.java` — **the bean is resolved by service name**, and the same name derives row 25's channel | 3 Coordination |
| 11 | Command classes | `commands/{svc}/*.java` *(outside the service package)* | 3 Coordination |
| 12 | Functionalities entry | `coordination/functionalities/{Xxx}Functionalities.java` | 3 Coordination |
| 13 | Sagas workflows | `coordination/sagas/{Op}FunctionalitySagas.java` | 3 Coordination |
| 14 | Controller | `coordination/webapi/{Xxx}Controller.java` | 3 Coordination |
| 15 | Event classes | `events/*.java` *(outside the service package)* | 3 Coordination |
| 16 | Subscription | `notification/subscribe/{Fam}Subscribes{Event}.java` | 3 Coordination |
| 17 | Event handler | `notification/handling/handlers/{Event}EventHandler.java`, plus the abstract base `{Fam}EventHandler.java` beside them — **one per service that has any**, and the `-1` §3.1's gate subtracts | 3 Coordination |
| 18 | Polling dispatch | `notification/handling/{Fam}EventHandling.java` — one `@Scheduled` **per event** | 3 Coordination |
| 19 | Event processing | `coordination/eventProcessing/{Xxx}EventProcessing.process{Event}Event` | 3 Coordination |
| 20 | Application class | `{Svc}ServiceApplication.java` | 4 Deployment |
| 21 | Service routing | `ServiceMapping.java` *(one enum, app-wide)* | 4 Deployment |
| 22 | Compose service + DB | `docker-compose.yml` — `{svc}-service` and `{svc}-db` blocks | 4 Deployment |
| 23 | Maven profile | `pom.xml` — `<id>{svc}-service</id>` | 4 Deployment |
| 24 | Service config | `resources/application-{svc}-service.yaml` — channels, `routes:` | 4 Deployment |
| 25 | Shared config | `resources/application.yaml` — bindings, function definitions | 4 Deployment |
| 26 | Test bean wiring | `test/.../BeanConfigurationSagas.groovy` | 4 Deployment |
| 27 | Groovy tests | `test/groovy/.../*.groovy` | 4 Deployment |
| 28 | Event trigger API | `notification/api/{Fam}EventController.java` — REST `@PostMapping`s that force event handling instead of waiting for row 18's poll | 3 Coordination |

Sections 11, 15 and 21 are **app-wide**, not per service: they live beside `microservices/`, and an
operation that renames or dissolves a service reaches into them. Section 28 exists in **one** service
(`tournament`), so its naming convention is inferred from a single instance rather than established —
and its routes count toward the `routes` baseline every operation checks.

**Not every service has every section, and the absences are load-bearing.** `<app>-composition.md` §1
(*Which optional artefacts each service actually has*) is the per-application table: in quizzes,
sections 16–19 exist in **5 of 8** services, section 13 in 7, section 8's two custom repositories in 7,
and section 28 in 1. Deleting a file that is not there is harmless; **creating one because the table
implied it should exist is not**, and reporting a section as handled when the service never had it hides
whatever was actually skipped.

### The three names a placeholder can stand for

`{Xxx}` is not one name. Three independent sources fill these paths, they coincide in six of the eight
services, and the two that differ are the ones every operation trips over:

| Placeholder | What it is | What it names |
|---|---|---|
| **`{Svc}`** | the package directory, capitalised | the command handler and the application class (rows 10, 20) — and, lower-cased, the compose blocks, the Maven profile, `application-{svc}-service.yaml`, the `ServiceMapping` value and the command channel (rows 22–25) |
| **`{Xxx}`** | the aggregate root class | the root, `Saga{Xxx}`, the service, `Functionalities`, the controller, `EventProcessing` (rows 1, 5, 9, 12, 14, 19) |
| **`{Fam}`** | the member-family prefix — **discovered, never derived** | DTO, factory, repositories, saga states, subscriptions, `EventHandling`, the abstract handler base (rows 4, 6, 7, 8, 16, 17, 18) |

| Service | `{Svc}` | `{Xxx}` | `{Fam}` |
|---|---|---|---|
| `answer` | **Answer** | QuizAnswer | QuizAnswer |
| `execution` | Execution | Execution | **CourseExecution** |
| the other six | = `{Xxx}` | = | = |

So `AnswerCommandHandler` holds the `QuizAnswer` aggregate's arms, and `CourseExecutionSagaState`
holds `Execution`'s. An operation that derives a file name from the root instead of looking it up
greps for a file that does not exist — and a check whose expected result is empty reads that as a pass.

---

## §2 — What each operation does

`+` create · `→` move / re-home · `−` delete · `↻` re-point · `—` untouched

| # | Section | MERGE (`A ← B`) | SPLIT (`Aggr → core + sat`) | CREATE_REPLICA | REMOVE_REPLICA |
|---|---------|-----------------|------------------------------|----------------|----------------|
| 1 | Aggregate root | `−` B's root; it becomes a member of A | `+` satellite root, in its **own** `microservices/{sat}/` package | — | — |
| 2 | Member entities | `→` all of B's into A | `→` partition across the cut, **across packages** | — | — |
| 3 | Replica classes | `−` the one that ends up inside `M`, or the copied field it carried · `↻` the **declarations of third parties**, re-pointed to the surviving root — the rule is per name, not per class (merge layer 1.7) | `→` re-home or duplicate | `+` the class, `@Replica("Root")` | `−` the class |
| 4 | DTO | keep field names **and the ids they carry**; a field whose source was a deleted replica changes source, not name (moved by merge 1.2, named by 2.1) | `→` the core composes the satellite's DTO, or loses the field and logs it (split layer 2.7) | `+` fields, if they belong in the REST response | `−` field (a logged behaviour change) |
| 5 | Sagas variant | `−` `Saga{B}` | `+` `Saga{Sat}` | `↻` construction path | `↻` drop the parameter |
| 6 | Saga states | `−` `{B}SagaState` if its states are unchecked · `→` its constants into `{A}SagaState` if checked | `+` satellite's ᵃ | — | — |
| 7 | Factory | `−` B's | `+` satellite's | `↻` construction path | `↻` drop the parameter |
| 8 | Repository | `−` B's | `+` satellite's | — | — |
| 9 | Service | `→` fold into `{A}Service`, `−` B's | `+` `{Sat}Service` — the satellite loads its own root (R1/R2) | — | — |
| 10 | Command handler | `→` fold arms into `{A}CommandHandler`, `−` B's | `+` satellite's, in `microservices/{sat}/messaging/` | — | — |
| 11 | Command classes | `−` if its only sender collapsed · `→` `commands/{A}/` otherwise | `+` `commands/{sat}/` — every core→satellite read and write the cut turned remote | `−` the dispatches the replica makes unnecessary | `+` restore the query the replica replaced |
| 12 | Functionalities entry | `→` fold into `{A}Functionalities` | `+` `{Sat}Functionalities` | — | — |
| 13 | Sagas workflows | **collapse** steps now local, `↻` steps from elsewhere | `→` split steps that straddled the cut | `−` the steps the replica makes unnecessary | `+` restore the remote-read steps |
| 14 | Controller | `→` move; `−` if an empty stub | `+` `{Sat}Controller`; a stub is legitimate (`CourseController`) | — | — |
| 15 | Event classes | `−` if publisher and every consumer end up inside `M` | — | reuse an existing one, **never create** | — |
| 16 | Subscription | `−` if its publisher is now inside `M`, else `→` | `→` with the entity it guards | `+` one | `−` if it only refreshed the replica |
| 17 | Event handler | same as 16 | same as 16 | `+` one | `−` |
| 18 | Polling dispatch | same as 16 | same as 16 | `+` one `@Scheduled` | `−` ᵇ |
| 19 | Event processing | same as 16 | same as 16 | `+` one `process…Event` | `−` |
| 20 | Application class | `−` B's | `+` `{Sat}ServiceApplication` — `@Profile` + the three scan roots | — | — |
| 21 | Service routing | `−` `{B}` constant, `↻` every call site | `+` `{SAT}` constant; the satellite's commands carry it | — | — |
| 22 | Compose service + DB | `−` both `{svcB}` blocks; `{svcB}db` destroyed, no data migrated | `+` both `{sat}` blocks; `{sat}db` starts empty, no data migrated | — | — |
| 23 | Maven profile | `−` B's | `+` the satellite's | — | — |
| 24 | Service config | `−` B's file; **merge its `routes:` into A's** with a distinct id | `+` the satellite's file; **carve its `routes:` out of the core's**, with an `order` below it | — | — |
| 25 | Shared config | `−` B's event-subscriber binding; **keep** its command-channel entries | `+` the satellite's event-subscriber binding and its command-channel entries | — | — |
| 26 | Test bean wiring | `−` B's beans, `↻` A's `Service` bean signature | `+` satellite's beans | `+` register the handler | `−` the registrations |
| 27 | Groovy tests | `↻` imports and wiring | `↻` | `+` assert the refresh fires | `↻` |
| 28 | Event trigger API | `→` with `<svcB>`'s notification package (3.6), keeping its `@PostMapping` paths | `+` only if the satellite consumes events | — | — |

**Rows 20–25 are the topology rows**, and both boundary operations own them: MERGE deletes a
deployment unit, SPLIT creates one. Either operation that stops at layer 3 leaves a codebase that
compiles and a deployment that does not run — a merge keeps starting a service whose main class is
gone, and a split leaves a second aggregate root inside a process that never registers it, so
`resolveServiceName` addresses a service nobody deployed.

That symmetry is not a convention. Each microservice owns exactly one aggregate type, so a boundary
that appears has to appear as a deployment unit; a satellite left inside the core's process shares its
transaction, its database and its `@EntityScan` root with the aggregate it was cut away from, and
every per-service measure reports the split as having changed nothing.

ᵃ Every satellite gets its states enum. Without one it cannot declare the `forbiddenStates` that
[`../architecture.md`](../architecture.md) R4 requires, and an aggregate with its own service is
lockable by definition — a step that reaches it reaches it remotely.

ᵇ The compiler catches this one, because the `@Scheduled` method constructs the handler class being
deleted. It is still listed: fixing a build break by deleting the line that broke is how the other
three pieces get left behind.

---

## §3 — Coupled sets

The sets whose members must change **together**. Each is a place where leaving one piece behind
compiles cleanly and fails at runtime in silence — which is why each has a gate rather than a note.

### 3.1 The event quad

| Piece | Path |
|---|---|
| Subscription | `notification/subscribe/{Tgt}Subscribes{Event}.java`, registered in `getEventSubscriptions()` |
| Handler | `notification/handling/handlers/{Event}EventHandler.java` |
| Polling dispatch | one `@Scheduled` in `notification/handling/{Tgt}EventHandling.java` |
| Processing | `coordination/eventProcessing/{Tgt}EventProcessing.process{Event}Event` |

**Gate.** Per service, the four counts must agree — counting only *concrete* handlers, since each
service with any has one abstract `{Tgt}EventHandler` base:

```bash
ls notification/subscribe/*.java | wc -l
ls notification/handling/handlers/*.java | wc -l          # minus 1 for the abstract base
grep -c "@Scheduled" notification/handling/*EventHandling.java
grep -c "public void process" coordination/eventProcessing/*.java
```

**This gate is not hypothetical.** Run against the application today it finds `answer` at
**5 / 6 / 6 / 6**: `DeleteUserEvent` has a handler, a `@Scheduled` and a `process` method and **no
subscription**, so `QuizAnswer` never receives it. Three pieces of four is enough to look wired and
not enough to work.

### 3.2 The command quad

| Piece | Path |
|---|---|
| Command class | `commands/{svc}/{Op}Command.java` |
| Dispatch arm | `case {Op}Command cmd -> handle{Op}(cmd);` in `{Svc}CommandHandler` |
| Handler method | `handle{Op}` in the same class |
| Service method | the `@Transactional` method it delegates to |

Routing is **by service name, not by type**: `LocalCommandService` resolves
`command.getServiceName() + "CommandHandler"`. A command addressed to a service with no `case` for it
reaches that service's `default` arm, which logs a warning and returns `null` — the saga step then
does nothing and the workflow completes without error.

**Gate.** Every `new {Op}Command(unitOfWork, ServiceMapping.X…)` must have a `case` in
`{x}CommandHandler`. The application has two live violations today, both in
`AnswerQuestionFunctionalitySagas`, which addresses `ServiceMapping.QUIZ` for two commands only
`AnswerCommandHandler` handles.

### 3.3 The aggregate set

Base class · `Saga{Xxx}` · factory · repository — **but verify the count, do not assume it.** Seven of
the eight aggregates have a custom sagas repository; `quiz` has no
`aggregate/sagas/repositories/` at all. An operation that "moves all four" for `quiz` invents a file.

The **base class is the source of truth**: it declares the entities, `verifyInvariants()` and
`getEventSubscriptions()`. The variant adds only saga state.

### 3.4 The step triple

| Piece | Where |
|---|---|
| Construction | `new SagaStep("name", () -> { … }, deps)` |
| Registration | `workflow.addStep(name)` |
| Dependency edges | the `Arrays.asList(...)` argument of every step that follows it |

A step constructed and never registered compiles and never runs. A step removed from `addStep` but
left in another step's dependency list is a dangling reference.

**Order is not declaration order.** `SagaWorkflow.planOrder()` topologically sorts the dependency
graph and takes ready steps in `HashMap` order, so two steps with no edge between them run in no
fixed order. Adding or removing an edge can therefore make two previously ordered steps concurrent —
which is a behaviour change even though nothing moved.

### 3.5 The consistency annotations

`@Replica`, `@InterInvariant` and `@ServiceGuard` are the only link static analysis has between a
class or method and what it means. They are `RetentionPolicy.SOURCE` — nothing at runtime depends on
them, so an operation that forgets one breaks no test and every subsequent measurement.

| Annotation | On | Must be updated when | Owned by |
|---|---|---|---|
| `@Replica("Root")` | the replica class | the copy is created, re-homed, or stops being a copy — and when the root it names stops being a root, which is what a merge does | merge 1.7, split 1.5 |
| `@InterInvariant("RULE")` | the `getEventSubscriptions()` delegate | a rule moves, is added, or becomes intra-aggregate | merge 1.7(e), split 1.5 |
| `@ServiceGuard("RULE")` | the service method that enforces it | the guard moves between services | merge 1.7(e) + 3.1, split 1.5 |

The values are **aggregate root simple names**, and the analysis resolves each one to a service. A name
that resolves to nothing is **dropped silently** (`StaticAnalysisCollector.replicaOwners`), which takes
that class's `@InterInvariant` attribution with it; and `@Replica` is what produces the **O1 signal**
that decides which merges are proposed next. A stale declaration does not fail, it misinforms.

Each rule name must appear **identically** in the aggregate's header block and in the annotation.

### 3.6 The lock pair

| Piece | Where |
|---|---|
| Lock | `sagaCommand.setSemanticLock({X}SagaState.…)` on the step that reads or writes `X` |
| Release | a compensation sending `GenericSagaState.NOT_IN_SAGA` for the same id |
| Check | a `{X}SagaState` constant in some `setForbiddenStates(...)` list, or a `…ForSaga` query on `X`'s repository |

The lock is registered on the command's **root id** through `findNonDeletedSagaAggregate`, so it only
works while that id is an aggregate. A lock whose state no check ever reads is a marker: removing it
changes no behaviour. A lock whose state is checked is a concurrency guard, and every operation that
moves or dissolves `X` must carry lock, release and check together.

---

## §4 — Count anchors

As of this writing, for `applications/quizzes`. These are **reading anchors, not a contract**: they
exist so an agent notices when a number does not add up, not to pin the numbers.

| Section | Count | Note |
|---|---|---|
| Services / processes / databases | 8 / 8 / 8 | one database per aggregate |
| Deployment-unit count, read seven ways | 8 | `*ServiceApplication` files, compose `-service` blocks, compose `-db` blocks, pom `-service` profiles, `application-*-service.yaml` files, `ServiceMapping` constants, `*EventSubscriber-in-0` bindings. **A merge moves all seven to 7 and a split to 9, together**; any one of them out of step is the operation half-applied |
| Aggregate roots | 8 | |
| `Saga*` variants · states · factories | 8 · 8 · 8 | |
| Custom sagas repositories | **7** | `quiz` has none |
| Services · command handlers · `Functionalities` · controllers | 8 each | |
| Sagas workflows | 68 | `course` owns none — its whole API is commands other sagas send |
| Saga steps | 136 | |
| Command classes | 78 | names are **not** unique across services |
| Event classes | 12 | |
| Subscriptions | **19** | should equal the next three |
| Concrete event handlers | **20** | 25 files − 5 abstract bases |
| `@Scheduled` dispatches · `process…Event` methods | **20** · **20** | |
| `notification/api/` | **1** | `tournament` only — three `@PostMapping`s that force handling |

The 19 / 20 / 20 / 20 mismatch is §3.1's gate firing. It is a real defect in the application, kept
here as the worked example.

---

## See also

- [`../architecture.md`](../architecture.md) — the canonical package layout, and restrictions **R1–R8**.
  **R4** in particular: a saga step that mutates must declare `forbiddenStates`.
- [`sagas.md`](sagas.md) — how semantic locks are acquired and checked.
- [`commands.md`](commands.md) — `ServiceMapping` and command routing.
- [`events.md`](events.md) — the publish/subscribe pipeline.
- [`rule-enforcement-patterns.md`](rule-enforcement-patterns.md) — P1 intra, P2 inter, P3 service guard.
- [`aggregate-merge.md`](aggregate-merge.md) — identity and the boundary rule behind layer 2.
- **Whether an operation is possible at all** is not here — this file says what each one *touches*.
  The feasibility map lives beside the application, in `<app>-composition.md`: §3 the ownership graph
  and §4 the per-pair merge verdict, §6 the per-aggregate split feasibility. Each skill's classifier
  re-derives it and stays the authority where the two disagree.
