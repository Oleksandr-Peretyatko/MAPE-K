# Layer 2 — Root split

Layer 1 gave the satellite its classes. This layer makes it **work as a root**: its identity, how it
is found from the ids the rest of the system already holds, how two roots are versioned, and what the
`UnitOfWork` registers.

This is where splits go wrong silently — the mirror of `refactor-merge-aggregate` layer 2. Everything
here compiles either way; what fails is a lookup returning empty, a subscriber that stops matching, or
a moved collection shared between two versions of the satellite.

---

## 2.1 — Identity: a new id, while the old one keeps addressing the core

`<Satellite>` draws its `aggregateId` from `aggregateIdGeneratorService.getNewAggregateId()` at
creation, like any root. The entities that moved had no id of their own: every caller reached them
through `<aggr>AggregateId`.

**Those callers are not rewritten.** The boundary rule:

| Where | Id |
|---|---|
| commands, DTOs, REST path variables, other services' replicas | `<aggr>AggregateId`, unchanged — not ours to rename, and a REST path is externally visible |
| `<svc>`'s service layer, once it needs the satellite | `<satellite>AggregateId`, resolved from the core's id (2.2) |

## 2.2 — Loading: the satellite resolves itself, and the core asks it

The satellite has its own service, its own persistence unit and its own database, so the core's
service **cannot** load it. `architecture.md` R1 and R2 are explicit: a service loads only its own
aggregate type and injects only its own repositories. What used to be a field access becomes a
command.

```java
// inside <Satellite>Service — the satellite resolves its own root from the id the caller carries
Integer <satellite>AggregateId = <satellite>CustomRepository.find<Satellite>IdBy<Aggr>Id(<aggr>AggregateId)
        .orElseThrow(() -> new SimulatorException(AGGREGATE_NOT_FOUND, <aggr>AggregateId));
<Satellite> satellite = (<Satellite>) unitOfWorkService.aggregateLoadAndRegisterRead(<satellite>AggregateId, unitOfWork);
```

```java
// inside <Aggr>'s workflow — the core reaches the satellite the only way it can
SagaSyncStep get<Satellite>Step = new SagaSyncStep("get<Satellite>Step", () -> {
    Find<Satellite>By<Aggr>IdCommand cmd = new Find<Satellite>By<Aggr>IdCommand(
            unitOfWork, ServiceMapping.<SAT>.getServiceName(), <aggr>AggregateId);
    <Satellite>Dto dto = (<Satellite>Dto) commandGateway.send(cmd);
    …
});
```

Four parts, each mandatory:

1. **`Optional<Integer> find<Satellite>IdBy<Aggr>Id(Integer)`** on `<Satellite>CustomRepository`,
   implemented in its sagas variant — the satellite holds `<aggr>AggregateId` (1.6), so the query
   belongs to it and **runs inside its own service**.
2. **`commands/<sat>/Find<Satellite>By<Aggr>IdCommand`**, with its arm in `<Satellite>CommandHandler`
   (layer 3.1) — this is how the core reaches the query. Every other read the core used to do inline
   becomes a command the same way; layer 3.2 turns each one into a step.
3. **The satellite is what gets loaded and registered** for a satellite change, so its own
   `UnitOfWork` sees it, the semantic lock is taken on it (layer 3.3), and its version advances.
4. **Not-found raises what loading the core would have raised** for that id —
   `AGGREGATE_NOT_FOUND` — so callers and tests see no change in behaviour, only in latency.

A path that reads both sides no longer loads two roots in one `UnitOfWork`: it loads its own root and
sends a command for the other. That is the operation's central cost, and the reason a cut that leaves
a hot read path crossing the boundary is a bad cut rather than an expensive one.

## 2.3 — Mutation: copy the root you change, register that root

Copy-on-write is per root: `Aggregate(Aggregate other)` sets `id = null` and `prev = other`, and
`registerChanged` runs `verifyInvariants()` and assigns the version.

- A satellite change copies and registers **`<Satellite>`**, through
  `<Satellite>Factory.create<Satellite>FromExisting(existing)`. Never mutate a loaded version in place.
- A write that touched both sides is now **two units of work in two services**, each registering its
  own root and running only its own `verifyInvariants()` — the straddling rules case 2 converted are
  precisely what no longer runs in either. The saga step that used to do both is split by layer 3.2,
  and its compensation is what makes the pair recoverable.

## 2.4 — Copy constructors: the satellite deep-copies, the core lets go

```java
public <Satellite>(<Satellite> other) {
    super(other);
    …
    set<Entities>(other.get<Entities>().stream().map(<Entity>::new).collect(Collectors.toSet()));
}
```

Move the deep-copy line for each moved collection **out of** `<Aggr>(<Aggr> other)` and **into**
`<Satellite>(<Satellite> other)`. The entities' own copy constructors already exist; wire them in, and
make `Sagas<Satellite>Factory.create<Satellite>FromExisting` call this constructor.

**Omitting this is the operation's most damaging silent mistake** — the same one merge layer 2.4
names. A new satellite version holding the old version's entity objects rewrites history on every
mutation, and `prev` stops meaning anything. Nothing throws.

## 2.5 — Events about satellite data keep the core's id as publisher

`EventSubscription.subscribesEvent` matches `subscribedAggregateId` against
`event.getPublisherAggregateId()`. Every external subscriber to an event about satellite data
subscribed with **`<aggr>AggregateId`**. Publishing with the satellite's own id silently stops all of
them.

The event constructor takes the publisher id explicitly, and `registerEvent` sets only the version. So
the satellite constructs those events with the core's id, which it holds (1.6):

```java
unitOfWorkService.registerEvent(new <Event>(satellite.get<Aggr>AggregateId(), …), unitOfWork);
```

Do **not** "fix" it to `satellite.getAggregateId()`.

**Versions no longer come from one counter.** Each service has its own `versionService`, so after the
split the two roots advance independently and a consumer cannot order an event about the satellite
against one about the core by version alone. Subscriptions compare a version against the publisher
they subscribed to, so each one stays correct on its own; what is lost is the cross-root ordering the
single counter used to give for free. Say so in the log if any consumer reads both.

## 2.6 — Deletion

`<Aggr>.remove()` used to delete everything. Now each root has its own `remove()` and state, and
`findLastAggregateVersion` filters on `ACTIVE` per root — **deleting the core no longer deletes the
satellite.**

| If the domain | Then |
|---|---|
| lets the satellite outlive the core | nothing to add |
| deletes the satellite with the core | a new interaction — layer 3.2 adds it as a step |
| gates the core's deletion on satellite state (`invariantDeleteWhenNoParticipants`) | a straddling rule — case 2 converts it |

Name the behaviour chosen in the log. An active satellite silently pointing at a deleted core is the
failure.

## 2.7 — The DTO across the cut

`<Aggr>Dto` carried the whole aggregate. The cut takes fields out of it, and since a DTO is what the
controller returns, that is the one change in this operation an **external client can see**.

**Default: compose.** The field stays on `<Aggr>Dto` with type `<Satellite>Dto`. The codebase already
works this way — `TournamentDto` holds `UserDto creator`, `Set<UserDto> participants`,
`CourseExecutionDto courseExecution`, `Set<TopicDto> topics` and `QuizDto quiz`, none of which are
`Tournament`'s own entities. A split continues that shape instead of inventing one.

**And the core's service is not what fills it.** No `*Service.java` in the application holds a
`CommandGateway` — services are `@Transactional` and local, which is `architecture.md` R1/R2 again. The
read functionality does it, and one already exists in that exact shape:
`FindTournamentFunctionalitySagas` is a `WorkflowFunctionality` whose single step sends
`GetTournamentByIdCommand` and keeps the DTO. The split gives it a second step, sending 2.2's
`Find<Satellite>By<Aggr>IdCommand`, and composes:

```java
SagaStep get<Satellite>Step = new SagaStep("get<Satellite>Step", () -> {
    Find<Satellite>By<Aggr>IdCommand cmd = new Find<Satellite>By<Aggr>IdCommand(
            unitOfWork, ServiceMapping.<SAT>.getServiceName(), <aggr>AggregateId);
    this.<aggr>Dto.set<Satellite>((<Satellite>Dto) commandGateway.send(cmd));
}, new ArrayList<>(Arrays.asList(find<Aggr>Step)));          // the edge is not optional — §3.4
```

The command is the one 2.2 already created; this is a second consumer of it, not a second command.
Building the response now costs one remote read — the same cost Step 0 counts under *Read the core does
inline*, and the reason a hot read path across the cut is a bad cut.

**The alternative, and why only it must be logged.** Dropping the field from `<Aggr>Dto` and letting the
client call the satellite's own controller is legitimate — it is what the satellite's `Controller` and
`Functionalities` (layer 3.6) are for. But the two failures are not symmetric: a getter that disappears
breaks the build, and someone fixes it; a JSON field that disappears breaks a client silently. So
`drop` carries a mandatory `WARNING:` naming the route and the field, and `compose` does not.

**Names do not change**, neither the fields nor the ids they carry — 2.1's boundary rule holds here too.
`<Satellite>Dto` keeps `<aggr>AggregateId` pointing home (1.2), so a client that had the core's id can
still reach either side.

**The external readers are already listed.** Step 0's sweep greps `<Aggr>Dto`; every hit outside the two
packages compiles against whichever shape was chosen or is re-pointed. None of them is discovered by
accident.

## 2.8 — Verify

```bash
MS=src/main/java/org/anonymous/msrefactor/quizzes/microservices
M=$MS/<svc>; S=$MS/<sat>

# the lookup exists in the satellite's interface and its sagas implementation — and nowhere else
grep -rl "find<Satellite>IdBy<Aggr>Id" $MS --include=*.java                                 # 2 hits, both under <sat>/
# the core never loads the satellite — must print nothing (R1/R2)
grep -rn "<Satellite>\|<satellite>CustomRepository" $M/service --include=*.java
# the command the core uses instead exists, with its handler arm
ls src/main/java/org/anonymous/msrefactor/quizzes/commands/<sat>/Find<Satellite>By<Aggr>IdCommand.java
grep -n "Find<Satellite>By<Aggr>IdCommand" $S/messaging/<Satellite>CommandHandler.java
# the satellite's copy constructor deep-copies every moved collection — inspect
grep -n "public <Satellite>(<Satellite> other)" -A 15 $S/aggregate/<Satellite>.java
# the core's copy constructor names no moved collection — must print nothing, per moved getter
grep -n "public <Aggr>(<Aggr> other)" -A 20 $M/aggregate/<Aggr>.java | grep "get<Entities>"
# events about satellite data still publish the core's id — inspect each hit
grep -rn "registerEvent(new " $M $S --include=*.java
# the DTO: the core no longer builds the moved fields itself, and the satellite's DTO exists
grep -n "class <Aggr>Dto" -A 30 $M/aggregate/<Aggr>Dto.java | grep "<MovedEntity>"   # compose: type <Satellite>Dto · drop: nothing
ls $S/aggregate/<Satellite>Dto.java
# whoever composes it is a functionality, never the service (R1/R2)
grep -rn "CommandGateway" $M/service $S/service --include=*.java                     # empty
```

---

## Log line

```
LAYER 2 (root split): <Satellite> resolved from <aggr>AggregateId inside its own service; <n> core read(s) turned into commands (<names>); copy constructors — satellite deep-copies <n> collection(s), core dropped <n>; <n> event(s) keep the core's id as publisher; deletion: <independent | cascades via 3.2 | gated by a case-2 rule>; dto — <compose|drop> (<n> field(s)), composed in <Find<Aggr>FunctionalitySagas | n/a>, <n> external reader(s)
```
