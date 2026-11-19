# Case 1 — Clean partition

P1, P2 and P3 all satisfied: the entity partition is total and disjoint, every invariant reads one
side only, and the satellite can stand as an aggregate in its own right.

This is the case the operation is designed for, and it is the rarest. Most real aggregates have at
least one straddling invariant — see case 2 — so reaching case 1 is itself worth stating in the log
with the evidence that produced it.

## What "clean" buys you

No invariant has to change meaning. Every rule that held before the split still holds after it, in
the same place, checked the same way — synchronously, inside one aggregate's `verifyInvariants()`.
The split costs coordination between the two aggregates, and nothing else.

That is the whole difference from case 2, and it is why the two cases are separate documents rather
than a branch in one action list.

## Run the layers, in this order

The order is not arbitrary; each step depends on the one before it.

| # | Layer | Why it cannot move |
|---|---|---|
| 1 | 1.2 — scaffold the satellite in its own `microservices/<sat>/` tree | nothing can be moved into a class that does not exist, and the tree is what the new process will scan |
| 2 | 1.3 — move satellite entities and their nested children | invariants cannot be re-homed before the fields they read are |
| 3 | 1.4 — place invariants | the cross-aggregate reference must not exist yet, or an invariant may be written to navigate it |
| 4 | 1.6 — add the cross-aggregate id references, both ways | after invariants, so no invariant can accidentally read across |
| 5 | 2 — identity, loading, copy constructors, event publisher ids | the satellite must be loadable before anything dispatches to it |
| 6 | 3.1 — dispatch | needs `Saga<Satellite>` to know the service name it resolves to |
| 7 | 3.2–3.6 | need the final shape of both roots |
| 8 | 4 — deployment | the unit is created last, when there is something to deploy; skipping it leaves commits addressed to a service that does not exist |

The **cross-aggregate reference is an id, never a Java reference** (`quizzes-composition.md` §2.4:
ids are unique within a service). `<Core>` holds `<satellite>AggregateId`; `<Satellite>` holds
`<core>AggregateId`. A `@OneToOne` between two roots would make them one aggregate again with extra
steps.

## What is created, and why

| Created | Because |
|---|---|
| `<Satellite>.java` | the new root |
| `Saga<Satellite>.java` | the saga variant; the only transactional model this operation modifies |
| `<Satellite>Factory` + `Sagas<Satellite>Factory` | the interface and its sagas implementation |
| `<Satellite>Repository` | aggregates are loaded by repository, not navigated to |
| `<Satellite>Dto` | the satellite is externally addressable, so it needs a projection — and it is what every command the core sends returns |
| `<Satellite>CommandHandler` | layer 3.1(b) — what both inbound paths look up by name, and where the satellite's domain arms live |
| `<Satellite>Service`, `<Satellite>Functionalities`, `<Satellite>Controller` | layer 3.6 — the satellite is the only thing that may load its own root (R1/R2) |
| `<Satellite>SagaState` | layer 1.2 — an aggregate reachable only remotely must declare `forbiddenStates` (R4) |
| `<Satellite>ServiceApplication`, the compose blocks, the pom profile, `application-<sat-infra>-service.yaml` | layer 4 — one aggregate, one service |

**`<Aggr>` is not deleted and not renamed.** It becomes `<Core>` in this document's language, but on
disk it keeps its class name, its `aggregateId`, its package and its REST paths. Renaming it would
break every external reference for no domain reason.

## Common failure, and how it is caught

1. **The service name does not match what the commit protocol resolves.** The characteristic failure
   of this operation, and invisible in a passing test run: the tests use the `local` profile, whose
   gateway never compares against `spring.application.name`. `resolveServiceName` turns
   `Saga<Satellite>` into `lowerFirst(<Satellite>)`, and that string must be the satellite's
   `spring.application.name` — otherwise every commit is published to a channel nobody consumes and
   blocks for the gateway timeout. Layer 3.1(b) has the rule, layer 4.11 has the runtime proof. Run
   them before reporting, not after.
2. **An invariant left behind that now reads nothing.** Moving entities without moving the invariant
   that reads them leaves a method dereferencing an empty collection — it returns `true` vacuously
   and the rule is silently gone. After layer 1.4:
   ```bash
   grep -n "invariant" src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>/aggregate/<Aggr>.java
   ```
   Every surviving invariant on `<Core>` must read at least one field `<Core>` still owns.
3. **Layers 1–3 applied and layer 4 skipped.** The codebase compiles, the tests pass, and the
   deployment has one aggregate root nobody deployed: `ServiceMapping.<SAT>` points at no container,
   the satellite's rows have no database, and the split is reported as done. Layer 4.11's count
   identity — seven readings of the deployment-unit count, all of which must have risen by one — is
   what catches it.
4. **`Result: CHANGED` with entities unaccounted for.** The contract guarantees
   `core ∪ satellite == entities(<Aggr>)`. If the counts in `Impact:` do not add up to the original
   entity count, an entity was dropped. Do not report `Result: CHANGED`.
