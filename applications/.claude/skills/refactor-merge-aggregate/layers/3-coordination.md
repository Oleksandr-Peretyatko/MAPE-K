# Layer 3 — Coordination

Fold `<svcB>`'s service, command handler, workflows, controller and event wiring into `<svcA>`, and
**collapse the steps that no longer cross a boundary**. This is the layer that produces the latency
benefit the operation was recommended for. Layers 1 and 2 without it leave a domain that merged and a
coordination flow that did not — a `MergeServices` in disguise.

> **The sequence of business steps is preserved; only the mechanism changes.** A step that read
> `AggrB` still reads it — from `M`, in-process, in the same `UnitOfWork`. Never construct
> `Saga<AggrB>`: layer 1 deleted it.

> See `../.claude/docs/concepts/refactoring-surface.md` for the section inventory this layer works from.

---

## 3.1 — `service/`

Fold `<AggrB>Service` into `<AggrA>Service`: move every `@Transactional` method and the dependencies
it was constructed with, then delete the class. The **bodies were already rewritten by layer 2** —
this step only relocates them and merges the constructor.

`<AggrA>Service`'s constructor gains whatever `<AggrB>Service` took and `<AggrA>Service` did not
(`aggregateIdGeneratorService` is usually shared already). Layer 4 re-points the matching
`@Bean` method in both `BeanConfiguration*.groovy`, whose signatures follow the constructor.

## 3.2 — `messaging/`

Fold every arm of `<SvcB>CommandHandler.handleDomainCommand` and the private methods they delegate
to into `<SvcA>CommandHandler`, then delete the class.

**The surviving bean must be named `<svcA>CommandHandler`.** Both `StreamCommandGateway` (in-process
path) and `StreamCommandService` (broker path) resolve the handler as
`command.getServiceName() + "CommandHandler"`, so a differently named bean silently disables
in-process dispatch and every command takes the broker (composition doc §2.3).

`getAggregateTypeName()` keeps returning `<AggrA>`'s name. Deleting `<SvcB>CommandHandler` is safe
only because layer 1 deleted `Saga<AggrB>`: with it gone, `resolveServiceName` stops producing
`<svcB>` and nothing looks the old bean up.

## 3.3 — `coordination/`

Move `sagas/`, `eventProcessing/`, `functionalities/` and `webapi/` from `<svcB>` into `<svcA>`,
deleting empty stubs rather than moving them — `CourseController` is an empty `@RestController` and
should not survive a merge into `course`.

`<AggrB>Functionalities` folds into `<AggrA>Functionalities`: one method per use case, each creating
a `SagaUnitOfWork` and running its `*FunctionalitySagas`. Do not create a second `Functionalities`
bean in `<svcA>`.

Then apply the step rule below to every workflow — both the ones that moved and the ones already in
`<svcA>`.

## 3.4 — The step rule

For every `SagaStep` whose command targeted `<svcB>`, the action depends on **where the functionality
lives after 3.3**:

### (a) The functionality is now in `<svcA>` — collapse the step

The target is in the same process and the same `UnitOfWork`:

1. replace `commandGateway.send(...)` with a direct call to the `<AggrA>Service` method the command's
   handler arm delegated to — 3.1 folded it in, and it is a bean in the same context;
2. delete that step's `registerCompensation(...)` — inside a single `UnitOfWork` there is nothing to
   compensate;
3. settle the semantic lock it took, by SKILL.md's reading 6 for the aggregate it locked:
   **unchecked** → delete it; **checked** → keep it **in-process**, calling
   `sagaUnitOfWorkService.verifySagaState(rootId, forbiddenStates)` and
   `sagaUnitOfWorkService.registerSagaState(rootId, state, unitOfWork)` at the top of the inlined call,
   where `rootId` is `M`'s id and `state` a `<AggrA>SagaState` constant (layer 1.4);
4. remove it from `workflow.addStep(...)` **and** from the dependency lists of the steps that
   followed it (`new ArrayList<>(Arrays.asList(getCourseStep, …))`);
5. if the command is left with no sender at all, it goes on 3.5's delete list.

**The step whose only product was a replica disappears instead of collapsing.** Where layer 1.7(b)
deleted the copy, the remote read that filled it has nothing left to fill: there is no call to inline,
so points 1 and *Where the inline call lands* do not apply — points 2, 3 and 4 do, and the step goes
whole. `CreateTopicFunctionalitySagas:40-47` is the shape to recognise:

```java
SagaStep getCourseStep = new SagaStep("getCourseStep", () -> {
    GetCourseByIdCommand cmd = new GetCourseByIdCommand(unitOfWork, ServiceMapping.COURSE…, courseAggregateId);
    SagaCommand sagaCommand = new SagaCommand(cmd);
    sagaCommand.setSemanticLock(CourseSagaState.READ_COURSE);   // settled by point 3 / 3.9
    this.courseDto = (CourseDto) commandGateway.send(sagaCommand);
    TopicCourse course = new TopicCourse(courseDto);             // the only product
    this.setCourse(course);
});
```

Four things go with it, and only the first two break compilation: the functionality's
`private TopicCourse course` field with its getter and setter, the `this.<b>Dto` field if nothing else
reads it, the step's own `registerCompensation`, and the edge in `createTopicStep`'s
`Arrays.asList(getCourseStep)`. A step left in another step's dependency list after `addStep` stopped
registering it is a dangling reference (`refactoring-surface.md` §3.4). The command's last argument goes
too — 3.5 owns that.

**Check every product before deleting the step, not just the copy.** Under `course ← question`,
`CreateQuestionFunctionalitySagas`'s `getCourseStep` produces *two* things: the `QuestionCourse` that
1.7 deletes and a `courseDto` that its compensation and `updateCourseQuestionCountStep` still read. The
step goes whole only because 1.6 collapsed that counter and took the second reader with it. Where a
second product survives, the step collapses by points 1–4 instead, and only the copy's construction is
removed.

**A checked lock the deleted step held does not vanish with it.** Point 3 still applies: its
`verifySagaState` / `registerSagaState` pair moves to the top of the step that took over the mutation —
the one that used to depend on it. Deleting the step is not permission to skip the guard, and nothing
fails when it is skipped.

This is where the merge is actually paid for: the round-trip, its commit and its compensation all leave
together.

**Point 3 is not bookkeeping.** The semantic lock is what puts the target into
`unitOfWork.aggregatesInSaga`, and every aggregate in that set costs one `CommitSagaCommand` — and on
rollback one `AbortSagaCommand` — addressed through `resolveServiceName` at commit time. A dropped lock
removes that round-trip; a lock kept in-process keeps it, but addressed to `<svcA>` it now dispatches
locally instead of crossing the broker. Either way the lock's commit stops being a broker hop.

Keeping a checked lock is not optional. Collapsing the step also removes the `SagaCommand` wrapper
whose handler ran the `forbiddenStates` check and registered the lock, so without the two explicit
calls the concurrency guard disappears silently and no test covers the interleaving it prevented.

### Where the inline call lands

Points 1 and 4 together delete the step and keep its call, so the call needs a new home. It is not a
free choice — the dependency edge the step had is the only thing that ordered it:

| The collapsed step was | The direct call goes |
|---|---|
| a dependency of exactly one step | at the **top of that step's body**, before anything else in it — that is where the edge guaranteed the value was already there |
| a dependency of several | at the top of the **first of them in declaration order**; the others read it from the functionality's field, exactly as they read the `send`'s result today |
| a dependency of **none** | **nowhere — do not delete the step.** Keep it, drop its compensation, settle its lock per point 3, and put the direct call in its own body |

The third row is the one that is easy to get wrong. A step nothing depends on still runs where the
workflow put it; deleting it and hoisting its call into some other step moves the work earlier or
later with respect to steps that were never ordered against it.

**The ordering rule.** Collapsing a step must not leave two steps concurrent that were ordered
before. Removing a step from the `Arrays.asList(...)` of its dependents can drop their last
dependency, which moves them to the front of the graph:
`SagaWorkflow.planOrder()` topologically sorts those lists and takes ready steps in `HashMap` order,
so a step with no remaining edge runs at no fixed point. After editing the dependency lists, re-read
them: any step that lost its **last** dependency must inherit the collapsed step's own dependencies
rather than becoming free.

A whole workflow can collapse: if every step targeted `<svcB>` and the functionality is now local,
`<AggrB>Functionalities.create<AggrB>` becomes a single `<AggrA>Service` call with no `SagaWorkflow`
at all. Under `course ← topic`, `CreateTopicFunctionalitySagas`' `getCourseStep` + `createTopicStep`
is exactly this.

> **Collapse at the call site, not by rerouting.** `StreamCommandGateway.send` dispatches in-process
> only when `command.getServiceName().equals(spring.application.name)` **and** a bean named
> `command.getServiceName() + "CommandHandler"` exists. Do not try to satisfy that by editing
> `ServiceMapping` — layer 4 re-points it as part of dissolution, and the point of this step is to
> remove the send entirely, not to make it take a shorter path. A step that still calls
> `commandGateway.send` has not been collapsed.

Worked example, `course ← question`: `CreateQuestionFunctionalitySagas` loses `getCourseStep` (and
its compensation) and `updateCourseQuestionCountStep`, leaving `createQuestionStep` dependent on
`getTopicsStep` alone; the course read and the count update become direct `CourseService` calls at the
top of `createQuestionStep`'s body, which is the step that depended on both.

### (b) The functionality lives in another service — re-point, do not collapse

`execution`, `topic`, `quiz`, `tournament`, `answer`, `user` calling in from outside: the call is
still remote. Change only the target to `<svcA>` (layer 4.1 does this globally through
`ServiceMapping`), and leave the step and its compensation in place. **Its semantic lock is not left
as it is** when it locks `AggrB` by `AggrB`'s id — that lookup fails after dissolution. Apply 3.9.

Leaving every step as a `commandGateway.send` is the failure this layer exists to prevent:
`<svcA>-service` ends up sending commands **to itself through RabbitMQ**. The aggregates merge, the
coordination does not, and the operation delivers none of the latency it was recommended for.

## 3.5 — `commands/<svcB>/`

Classify each command class individually:

| Case | Action |
|------|--------|
| its only sender was a `<svcA>` functionality collapsed in 3.4 | **delete** the class |
| it maintained a counter that layer 1.6 collapsed | **delete** the class and its handler arm |
| it filled a replica that layer 1.7 deleted, and nothing else read its result | **delete** the class and its handler arm — the step went with it in 3.4(a) |
| it **carried** a replica 1.7 deleted as a parameter | **keep the class, drop the parameter**, and drop it in the arm, the handler method and the service method it delegates to — the command quad, `refactoring-surface.md` §3.2. `CreateTopicCommand(unitOfWork, TOPIC, topicDto, this.getCourse())` loses its fourth argument |
| it still has senders in other services | **move** to `commands/<svcA>/`, keep its arm in `<SvcA>CommandHandler` |

A moved command keeps its parameters, **including the wire name `<aggrB>AggregateId`**. That is layer
2's boundary rule: the value is preserved, and inside `M` it is an entity id (`<aggrB>Id`), but the
parameter other services send is not ours to rename. `<SvcA>CommandHandler`'s arm maps wire → domain
when it calls `<AggrA>Service`. Do not rewrite a command's signature to take `M`'s aggregate id — many
members share one root, so it could not address an individual one.

## 3.6 — `events/` and `notification/`

Move `<svcB>`'s `notification/subscribe/`, `handling/`, `handling/handlers/` and `api/` into
`<svcA>`, and fold `<FamB>EventHandling` into `<FamA>EventHandling`.

**`notification/api/` is a section of its own** (`refactoring-surface.md` §1, row 28): a
`<Fam>EventController` whose `@PostMapping`s force event handling instead of waiting for the poll. One
service has one today, so the usual finding is that `<svcB>` has none — check, and say so. Where it
exists, its paths move with it and must survive the `routes` baseline of 4.11, and `<svcA>` cannot
simply gain a second controller with the same paths.

**Consuming one event is four files, not two.** Deleting or moving a subset leaves a path that looks
wired and is not — see `../.claude/docs/concepts/refactoring-surface.md` §3.1:

| # | Piece | Path |
|---|---|---|
| 1 | Subscription | `notification/subscribe/<Aggr>Subscribes<Event>.java`, registered in `getEventSubscriptions()` |
| 2 | Handler | `notification/handling/handlers/<Event>EventHandler.java` |
| 3 | Polling dispatch | one `@Scheduled` method in `notification/handling/<Aggr>EventHandling.java` |
| 4 | Processing | `process<Event>Event` on `<Aggr>EventProcessing` (moved wholesale by 3.3) |

Then classify each event, acting on **all four** pieces:

| Publisher and consumers | Action |
|---|---|
| publisher **and every** consumer now inside `M` | **delete** the event class and all four pieces of its consumption: the `<Aggr>Subscribes<Event>`, the `*EventHandler`, the `@Scheduled` method in `<AggrA>EventHandling`, and `process<Event>Event` on `<AggrA>EventProcessing`. That synchronisation is intra-aggregate logic from here on — this is the merge working, not a loss. |
| any consumer outside `M` | **keep all four.** `M` publishes it with the **retained member identifier** (layer 2.5), so no consumer-side change is needed. Verify the constructor call still passes `<member>.get<AggrB>AggregateId()` and not `M.getAggregateId()`. |
| it existed **only** to refresh a replica layer 1.7 deleted, on either side of the merge | **delete all four pieces** of that consumption, exactly as for a counter (3.5's table). The copy it refreshed is gone; a `process…Event` whose only statements were setters on a deleted class does not compile, but the `@Scheduled` and the subscription do |

A kept event needs one more check when 1.7(a) re-pointed a consumer's declaration: the event still
delivers, because layer 2.5 retained the identifier, but the consumer's class now declares itself a copy
of `AggrA`. The publisher of its refresh is `<svcA>` from here on — that is the "new source" 3.8 asks you
to list.

`AggrB`'s own subscriptions move into `M.getEventSubscriptions()`. Delete any whose publisher is now
inside `M` — with its other three pieces — and keep the rest, with their `subscribedAggregateId`
unchanged: they point at a *third* aggregate, which the merge did not touch.

**The quad gate is differential.** Compare against Step 0.5's `quad-<svcA>` and `quad-<svcB>`, not
the four counts against each other:

```bash
M=src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svcA>
ls $M/notification/subscribe/*.java | wc -l
h=$(ls $M/notification/handling/handlers/*.java 2>/dev/null | wc -l); echo $(( h > 0 ? h - 1 : 0 ))   # net of the abstract base, when there is one
cat $M/notification/handling/*EventHandling.java | grep -c '@Scheduled'
cat $M/coordination/eventProcessing/*.java | grep -c 'public void process'
```

**Rule:** for each piece, `post = pre(<svcA>) + pre(<svcB>) − consumptions deleted in this layer`. A
piece off by one is an event with that piece orphaned — the handler registered and the poller
scheduled, but the event never delivered, and no test fails. Name the event and the piece, and fix it.

**A mismatch that predates the run does not block.** `answer` is 5 / 6 / 6 / 6 today for
`DeleteUserEvent`, before any merge. If a service's four baseline figures already disagreed, carry the
difference forward unchanged and log `WARNING: pre-existing quad mismatch in <svc> (<a>/<b>/<c>/<d>) —
not introduced by this run`. Only a difference this run introduced stops `Result: CHANGED`.

Then check the event classes against `$B/events`: every name missing from
`ls src/main/java/org/anonymous/msrefactor/quizzes/events/` must be one this layer deleted.

## 3.7 — Forbidden states

A `setForbiddenStates(...)` list can name `<AggrB>SagaState` only if `AggrB`'s states are *checked*
(reading 6), so layer 1.4 has moved those constants into `<AggrA>SagaState` and every such list stops
compiling. **What to put in its place is a domain decision, not a mechanical fix**, and deleting the
entry to restore the build is the wrong answer.

The check runs against the `SagaCommand`'s root id (`verifySagaState(rootAggregateId, …)`), so a list
that now names an `<AggrA>SagaState` constant must also be checked against `M`'s id — 3.9 retargets the
root; a collapsed step makes the call itself (3.4(a) point 3).

```bash
grep -rn "setForbiddenStates" src --include=*.java
```

For each hit that named `<AggrB>SagaState.*`:

- the step **mutates what is now a member of `M`**, so the state that must be forbidden is `M`'s —
  use the constant layer 1.4 moved into `<AggrA>SagaState`;
- if the concurrent operation the entry protected against **no longer exists** — because 3.4(a)
  collapsed the workflow that ran it — the entry is genuinely gone. Say so in the log by name.

This is [`architecture.md` R4](../../../docs/architecture.md): a saga step that mutates an aggregate
must declare the states of concurrent operations that would conflict with it. Dropping an entry
silently removes a concurrency guard, and no test covers the interleaving it prevented.

## 3.8 — Close the inventory

Walk Step 0's list and confirm every entry of every class was handled — not just the ones that broke
compilation. The *Semantic lock on `AggrB`* class is handled by 3.9 and closed by its gate. A mis-pointed replica or DTO compiles cleanly and fails at runtime with inconsistent
data.

**The closing sweep.** Nothing may name `<svcB>` any more:

```bash
grep -rn "microservices\.<svcB>\.\|commands\.<svcB>\.\|ServiceMapping\.<AGGB>" src
```

Must be empty.

**The collapse gate.** Even with `ServiceMapping.<AGGB>` gone, a step can survive by having been
re-pointed to `ServiceMapping.<AGGA>` instead of collapsed — it then sends a command to its own
process through the broker:

```bash
# piped rather than globbed — `**` needs globstar, which is off in a default shell
grep -rn "commandGateway.send" src --include=*.java \
  | grep "/microservices/<svcA>/coordination/"
```

Every remaining hit must target a genuinely different service, or `<svcA>` itself for a command
`<SvcA>CommandHandler` owns. A hit whose command came from `commands/<svcB>/` is a step 3.4(a) was
supposed to collapse. If you find one, **do not report success** — go back to 3.4, collapse it, and
re-run. If one genuinely cannot be collapsed, name it individually in the log with the reason; never
sweep it into a blanket "saga steps were not collapsed".

**The replica gate.** 1.7 decided per name; this is where the decision is confirmed across services,
because the third parties' declarations are the ones no compiler will miss:

```bash
grep -rn "@Replica" src --include=*.java | grep "\"<AggrB>\""   # empty — every name re-pointed or removed
grep -rl "@Replica" src --include=*.java | wc -l                # > 0 — the control for the line above
```

A surviving `"<AggrB>"` resolves to no root, so `replicaOwners` drops it silently and the
`@InterInvariant` attribution of that class goes with it. Nothing fails; the next Analyze phase is
simply wrong.

List any retained replicas together with their new source, and any field 1.7 kept as a snapshot
together with the reader that justified it.

## 3.9 — Semantic locks on the dissolved aggregate

After dissolution `AggrB`'s id is a column, not an aggregate. `SagaCommandHandler` registers and
verifies a lock through `findNonDeletedSagaAggregate(rootAggregateId)`, so every step that still locks
or checks `AggrB` by its own id fails with `AGGREGATE_NOT_FOUND` — at runtime, in whichever service
sends it. Step 0's *Semantic lock on `AggrB`* class is the work list; SKILL.md's reading 6 decides the
action:

| `AggrB`'s lock states | Action | Why |
|---|---|---|
| **unchecked** | **drop** the lock | nothing reads it back, so behaviour is unchanged, and `M` is not locked more coarsely than before |
| **checked** | **retarget** it to `M` | a real guard reads it; dropping it removes a concurrency check no test covers |

### Dropping

At each site delete `setSemanticLock(<AggrB>SagaState.…)`, the release compensation that sends
`GenericSagaState.NOT_IN_SAGA` for the same id, and any `forbidden` entry naming the state. The command
itself stays: layer 4.1 re-points its target to `<svcA>`, where layer 2.2 resolves the member. A
`SagaCommand` left with no lock and no forbidden states is sent as the bare command instead.

Tests that assert `AggrB`'s `sagaState` (for Topic, `CreateTournamentTest:138-139`) assert a state that
no longer exists: remove those assertions and name them in the log.

### Retargeting

1. **The owner's id**, taken in this order:
   - from data the saga already holds — `TopicDto.courseId`, `QuestionDto.getCourse().getAggregateId()`,
     `QuizDto.courseExecutionAggregateId`, `QuizAnswerDto.quizAggregateId`,
     `CourseExecutionDto.courseAggregateId`, `TournamentDto.courseExecution` — adding a dependency on
     the step that provides it (3.4's ordering rule);
   - otherwise from a preceding step that sends layer 2.2's `Get<AggrA>IdBy<AggrB>IdCommand`.
2. **The lock.** Leave the payload alone — handlers read their own id fields, never the root — and
   point only the wrapper at `M`:
   ```java
   SagaCommand sagaCommand = new SagaCommand(get<AggrB>ByIdCommand);  // payload still carries the member id
   sagaCommand.setRootAggregateId(ownerAggregateId);                  // lock and check land on M
   sagaCommand.setSemanticLock(<AggrA>SagaState.<MOVED_CONSTANT>);    // moved by layer 1.4
   ```
   `setRootAggregateId` is public on `Command`. Commit and abort follow the id and aggregate type the
   lock was registered with, so they reach `<svcA>`. Retarget the release compensation the same way.
3. **One lock per owner per step.** Abort replays a step's saved states in insertion order
   (`SagaUnitOfWorkService.sendAbortCommandsForStep`). A loop that locks two members of the same owner
   records `(M, NOT_IN_SAGA)` then `(M, <lock>)`, and the abort leaves `M` locked for good. Lock the
   owner once, before the loop — in `…Async` variants, before the fan-out.
4. **Collapsed steps** keep the lock in-process against `M`'s id, as 3.4(a) point 3 says.

### What retargeting costs — log it

- **A coarser lock.** Locking `M` blocks operations on `M` and on all its members, not only on the
  member that was read. Name the workflows affected.
- **`M` hidden from its own `…ForSaga` queries.** They filter on `NOT_IN_SAGA`, so while the lock is
  held they do not see `M`. Name each one — `CourseRepository`'s lookup by name is an example: a course
  locked this way is invisible to `getAndOrCreateCourseRemote`.

### In quizzes

| Merge | `AggrB` states | Action |
|---|---|---|
| course←topic | unchecked | drop — tournament ×2, question ×3 (each with its release) and topic's own workflows |
| course←question | unchecked | drop — quiz ×1, answer ×1 and question's own workflows |
| execution←quiz | unchecked | drop — answer ×1, tournament ×2; also the `QuizAnswerSagaState` locks tournament registers on the quiz id, which sit on a Quiz row that no QuizAnswer query reads |
| execution←tournament | checked | retarget to Execution — no remote sites; every collapsed tournament step keeps its lock and forbidden states in-process |
| quiz←answer | checked | retarget to Quiz — tournament's sites already lock the quiz id, so only the constants move; answer's own steps keep theirs in-process |

### Gate

```bash
grep -rn "<AggrB>SagaState" src --include=*.java      # empty — deleted or moved by layer 1.4
```

Then read every `SagaCommand` in Step 0's lock class: none may still carry a member id as its root.
Either the lock is gone, or `setRootAggregateId` points at `M`.

---

## Log line

```
LAYER 3 (coordination): <n> step(s) collapsed (<names>), <n> re-pointed, <n> deleted whole as replica-only (<names>); commands deleted: <n>, moved: <n>, <n> stripped of a replica parameter (<names>); events deleted: <n> (all four pieces), retained: <n>; forbidden-state entries re-pointed: <n>, dropped: <names or none>; locks — <AggrB> states <checked|unchecked>, dropped <n>, retargeted <n>, kept in-process <n>; workflows fully collapsed: <names or none>
```
