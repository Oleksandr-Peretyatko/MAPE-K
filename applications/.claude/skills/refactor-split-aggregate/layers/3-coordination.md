# Layer 3 — Coordination

Give the satellite a way to be reached, and split the workflow steps that crossed the new line. **The
sequence of business steps is preserved; the number of steps rises.** That rise is the cost the
operation trades for a smaller boundary — count it, do not hide it.

---

## 3.1 — Dispatch: the satellite is a service, and is addressed as one

Both paths — domain commands and saga commit/abort — now reach a service that exists. This layer
makes them reach it; layer 4 creates it. Wire the names here and the unit there, and the pair is what
makes a command arrive.

`StreamCommandGateway.send` dispatches in-process only when **both** hold
(`simulator/…/messaging/stream/StreamCommandGateway.java:38-49`):

```java
String handlerBeanName = command.getServiceName() + "CommandHandler";
if (service.equals(appName) && applicationContext.containsBean(handlerBeanName)) { … }
```

Otherwise the destination is `service + "-command-channel"`. So the same rule decides both paths, and
the satellite's service name is what it decides on.

### (a) Domain commands — they carry the satellite's own mapping

Give every command addressed to the satellite `ServiceMapping.<SAT>` — the constant layer 4.1 adds.
From the core's process the send then crosses the broker, which is the honest cost of the new
boundary; from the satellite's own process it dispatches locally, because the name matches and
`<sat>CommandHandler` is in the context.

**Never `ServiceMapping.<SVC>` for a satellite command.** It would be handled by the core's service,
which no longer owns that state and cannot load it (R1) — the command would either throw or, worse,
mutate through a stale path.

### (b) Saga commit and abort — the name is the whole mechanism

These ignore `ServiceMapping`. `SagaUnitOfWorkService.commit()` (109-113) and
`sendAbortCommandsForStep()` (138-142) take the target from `resolveServiceName(aggregateType)`, which
strips `Saga` and lower-cases the first letter: `SagaTournamentParticipation` → `tournamentParticipation`.

That string is compared against the satellite service's `spring.application.name`. Make them equal —
*Naming the satellite* in `SKILL.md` — and every commit of a satellite locked inside its own process
is local. Make them differ and the commit is published to `<resolved>-command-channel`, which nobody
declared and nobody consumes: it blocks for `commandTimeoutSeconds` and throws.

**Four edits, all four or none**, and note that three of them are now the satellite's own files:

| # | Edit | File |
|---|---|---|
| 1 | producer binding `<sat>-command-channel: { destination: <sat>-command-channel }` | `application.yaml`, `on-profile: stream`, *Command producers* |
| 2 | consumer binding `<sat>ServiceCommandChannel-in-0: { destination: <sat>-command-channel, group: <sat>-service-group }` | same file, *Command consumers* |
| 3 | `<sat>ServiceCommandChannel;` in `spring.cloud.function.definition` | `application.yaml` **and** the satellite's own `application-<sat-infra>-service.yaml` (layer 4.5) |
| 4 | class `<Satellite>CommandHandler extends CommandHandler`, `@Component` — default bean name `<sat>CommandHandler` | `microservices/<sat>/messaging/`; its test bean is layer 4.10 |

Edit 4 is what both inbound paths look up by name (`StreamCommandService:68`, `LocalCommandService:71`).
Without it the command dies with *"Failed to find command handler for service"*.

**This handler is not a thin shim.** It carries the satellite's domain arms —
the `case` per command layer 2.2 and 3.2 created — because they are the satellite's API now.
`SagaCommandHandler`, the `@Profile("sagas")` decorator, still intercepts commit and abort by
`aggregateId` in front of it.

### (c) The satellite that is never saga-locked

`addToAggregatesInSaga` runs only when a step sets a saga state (`SagaUnitOfWorkService:90-94`). A
satellite **read but never mutated inside a saga** never enters `aggregatesInSaga`, so `commit()`
sends nothing for it and (b) never fires. It still needs its service, its handler and its channels —
its reads are commands — but the commit path costs nothing. Check it before pricing the cut.

### Verify

```bash
MS=src/main/java/org/anonymous/msrefactor/quizzes
# (a) every satellite command carries the satellite's mapping — and none carries the core's
grep -rn "ServiceMapping\." $MS/commands/<sat> --include=*.java | grep -v "<SAT>"      # empty
# (b) edits 1-4
grep -n "<sat>-command-channel\|<sat>ServiceCommandChannel"   src/main/resources/application.yaml src/main/resources/application-<sat-infra>-service.yaml
grep -rn "<sat>CommandHandler" src --include=*.groovy --include=*.java
# the name that decides everything: these two must print the same string
grep -n "spring:" -A 2 src/main/resources/application-<sat-infra>-service.yaml | grep "name:"
echo "<Satellite>" | sed 's/^\(.\)/\L/'
```

**Tests verify only edit 4.** They run the `local` profile, whose `LocalCommandGateway` never compares
against `spring.application.name`; edits 1–3 are `stream` YAML no test loads. **A green
`-Ptest-sagas` with the YAML unwired deploys a satellite whose every commit hangs.** The deploy-time
proof is layer 4.11.

| Verdict | Meaning | Emit |
|---|---|---|
| `domain=broker` | (a) holds and the send crosses the boundary — the operation's cost | — |
| `domain=in-process` | a satellite command carries `ServiceMapping.<SVC>` | `WARNING: satellite domain commands are handled by <svc> — <n> command(s) carry ServiceMapping.<SVC>, which is the core's service and cannot load <Satellite> (R1); re-point them to ServiceMapping.<SAT>` |
| `sagaCommit=in-process` | (b) wired and the name matches — the healthy case | — |
| `sagaCommit=broker` | the name does not match `lowerFirst(<Satellite>)` | `WARNING: <Satellite>'s commits cross the broker to reach their own process — spring.application.name is '<name>' but resolveServiceName produces '<sat>'; rename the service, not the class` |
| `sagaCommit=unwired` | one of edits 1–4 missing | `WARNING: saga commit for <Satellite> is unwired — <missing piece>; every commit of a UnitOfWork holding a <Satellite> will block for the gateway timeout and then throw` |
| `sagaCommit=n/a` | (c) — never saga-locked | — |

## 3.2 — The step rule

For every `SagaStep` whose body changed state now on both sides:

1. split it into two steps, one per root, each loading and registering only its own root (layer 2.3);
2. the second depends on the first, so their relative order does not change;
3. each gets its own `registerCompensation(...)` if the original had one, compensating only its root;
4. every step that depended on the original now depends on **the second**.

**The ordering rule.** `SagaWorkflow.planOrder()` topologically sorts the dependency lists and takes
ready steps in `HashMap` order, so a step whose list does not name its predecessor runs at no fixed
point. Re-read every list after splitting: no two steps may become concurrent that were ordered before.

A step that only **reads** the satellite takes no semantic lock on it — that is what keeps (c) free. A
read functionality composing the core's DTO (layer 2.7) is exactly that step: it gains a dependency edge
on the step that loaded the core, and nothing else.

Worked example, `Tournament` with participants in the satellite: `AddParticipantFunctionalitySagas`
loaded one root to check the tournament's dates and add the participant. The date check stays on the
core; the add moves to a second step on the satellite, depending on the first.

## 3.3 — Forbidden states

A step that mutates an aggregate must declare the states of concurrent operations that conflict with it
([`architecture.md` R4](../../../docs/architecture.md)).

```bash
grep -rn "setForbiddenStates" src --include=*.java
```

For each list on a step 3.2 split: the step that now locks `<Satellite>` names `<Satellite>SagaState`
constants (layer 1.2 creates the enum); the step still locking `<Aggr>` keeps `<Aggr>SagaState`.
**Never drop an entry to make a list compile** — it removes a concurrency guard, and no test covers
the interleaving it prevented. Log every entry re-pointed or added, by name.

**A functionality re-homed whole carries its guards with it — re-anchored, not re-spelled.** A
forbidden-states list is enforced against the **root aggregate id of the command it wraps**
(`SagaCommandHandler.verifySagaState`), so a functionality that moves to the satellite's side and now
commands only the satellite is checking the wrong aggregate even if the list is copied verbatim: the
semantic lock it conflicted with (`<Aggr>SagaState.IN_UPDATE_<AGGR>`, set by a core-locking saga) still
lives on the **core**, which the moved functionality no longer loads. Dropping the `SagaCommand`
wrapper altogether is the same defect one step earlier. Either way the conflict detection is severed
silently: both sides compile, and only a concurrency test that pauses the core-locking saga mid-flight
catches it (v19's split of `Tournament` lost exactly this — the satellite's anonymize functionality
shed `setForbiddenStates([IN_UPDATE_TOURNAMENT])` and 3 gate tests expecting
`AGGREGATE_BEING_USED_IN_OTHER_SAGA` got no exception).

For every functionality the split re-homes, diff its pre-split body for `setForbiddenStates`:

- states owned by entities that **moved** re-point to `<Satellite>SagaState` on the satellite-locking
  step;
- states still set on the **core** keep guarding it: add a guard step *before* the first mutation that
  resolves the core's aggregate id (through the satellite's `<aggr>AggregateId` back-reference if the
  functionality only has the satellite's id) and sends a `SagaCommand`-wrapped read against
  `ServiceMapping.<AGGR>` carrying those forbidden states.

The check is differential: `grep -c "setForbiddenStates" src --include=*.java` post ≥ pre, and every
list present pre-split is accounted for by name in the log — kept, re-pointed, or re-anchored on a
guard step.

## 3.4 — Events: the quad, moved and created

Consuming one event is four pieces (`../.claude/docs/concepts/refactoring-surface.md` §3.1):

| # | Piece | Path |
|---|---|---|
| 1 | Subscription | `notification/subscribe/<Aggr>Subscribes<Event>.java`, registered in `getEventSubscriptions()` |
| 2 | Handler | `notification/handling/handlers/<Event>EventHandler.java` |
| 3 | Polling | one `@Scheduled` method in `notification/handling/<Aggr>EventHandling.java` |
| 4 | Processing | `process<Event>Event` in `coordination/eventProcessing/` |

| The subscription guards | Action |
|---|---|
| core state | unchanged |
| satellite state | all four follow it — registered from `<Satellite>.getEventSubscriptions()`, processed against the satellite loaded per 2.2 |
| a rule case 2 converted to an inter-invariant | **create** all four, plus the reaction when the check fails |
| a scalar case 2 replicated onto the satellite | **create** all four for the core's update event — a replica with no refresh path is correct exactly once |

**The quad gate is differential**, against Step 0.5's `quad`:

```bash
M=src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>
ls $M/notification/subscribe/*.java 2>/dev/null | wc -l
h=$(ls $M/notification/handling/handlers/*.java 2>/dev/null | wc -l); echo $(( h > 0 ? h - 1 : 0 ))
cat $M/notification/handling/*EventHandling.java 2>/dev/null | grep -c '@Scheduled'
cat $M/coordination/eventProcessing/*.java 2>/dev/null | grep -c 'public void process'
```

Each figure rises by exactly the consumptions this layer created. A piece off by one is an orphan —
handler registered, poller scheduled, event never delivered — and no test fails. A mismatch already in
the baseline (`answer` is 5 / 6 / 6 / 6 today) is carried forward and logged `WARNING: pre-existing quad
mismatch in <svc>`; only a difference this run introduced stops `Result: CHANGED`.

## 3.5 — External references

Re-point each Step 0 hit at whichever root now owns what it reads. Callers keep sending
`<aggr>AggregateId` (2.1); what changes is which service answers for it, and — because the moved
classes changed package (1.3) — every import that named them.

A hit that reads satellite state from another service is not re-pointed to a repository: it is
re-pointed to a command addressed to `ServiceMapping.<SAT>`, like every other cross-boundary read.

## 3.6 — The satellite's own surface

The satellite is an aggregate with a service around it, so it needs the artefacts every aggregate in
this application has. Create them here, in `microservices/<sat>/`:

| Create | Why |
|---|---|
| `service/<Satellite>Service.java` | `@Transactional(SERIALIZABLE)`, every method taking a `UnitOfWork`. It is the only place that loads `<Satellite>` (R1/R2), and layer 2.2's lookup lives behind it |
| `coordination/functionalities/<Satellite>Functionalities.java` | one method per use case the satellite now owns; each runs its `*FunctionalitySagas` |
| `coordination/sagas/*FunctionalitySagas.java` | the workflows 3.2 split off, plus one per satellite entry point the core used to serve inline |
| `coordination/webapi/<Satellite>Controller.java` | the endpoints case 6 decided to move. **An empty stub is legitimate** — `CourseController` is one — when every path stays on the core and reaches the satellite by command. It is also what the `drop` variant of layer 2.7 requires: a client that lost a field from `<Aggr>Dto` has to have somewhere to read it |
| `messaging/<Satellite>CommandHandler.java` | created by 3.1(b) edit 4; its arms are the satellite's API |
| `notification/` and `coordination/eventProcessing/` | only if a subscription moved here in 3.4. `course`, `topic` and `user` — three of the eight — show a service can legitimately have none |
| `notification/api/{Satellite}EventController.java` | only if the core has one **and** a subscription moved here: it is the REST trigger that forces handling instead of waiting for the poll, and the satellite's own poll needs the same. One service has one today (`tournament`), so a stub is not expected — say in the log that the core's was checked |

What does **not** get created: a second `ServiceMapping` for the core, a second event subscriber for
the core, or any copy of the core's classes. The satellite is a new aggregate, not a fork.

---

## Log line

```
LAYER 3 (coordination): dispatch domain=<broker|in-process>, sagaCommit=<in-process|broker|unwired|n/a>; steps split: <n> (<names>); commands created: <n> (<names>); forbidden-state entries re-pointed: <n>, added: <n>; quad — moved <n>, created <n>, gate <ok | pre-existing mismatch>; satellite surface — service, functionalities, <n> workflow(s), controller <created|stub>, notification <created|none>
```
