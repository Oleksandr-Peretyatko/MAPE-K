# Behavioral Preservation in the Refactor Tool

This document defines what must remain unchanged after any refactoring operation applied by the refactor tool, how the test gate enforces those guarantees, and what a developer must verify before and after implementing a new structural change.

---

## 1. What "Behavioral Preservation" Means

A refactoring is behaviorally safe when every user-observable interaction with the system produces the same outcome before and after the structural change. Concretely, the following contracts are frozen:

| Contract type | What is frozen |
|---|---|
| **REST API** | HTTP method, URL path, path/query parameter names, request/response body schema, HTTP status codes |
| **Error codes** | Every string constant in `QuizzesErrorMessage` — message text must not change; a code removed or renamed breaks clients |
| **SAGA compensation** | Every `forbiddenStates` set must still block conflicting concurrent operations after the refactor |
| **Event subscriptions** | Every publisher/consumer pair for each domain event type must still exist; no new polling gap can be introduced |
| **Intra-invariants** | Every `verifyInvariants()` check in every aggregate must still fire and throw on the same broken state |

---

## 2. Six-Layer Consistency Model

The quizzes application enforces consistency through six independent layers. A refactoring that touches an aggregate must not weaken any layer that was previously active.

| Layer | Mechanism | Where implemented |
|---|---|---|
| 1 — Intra-invariant | `verifyInvariants()` called inside `UnitOfWork.commit()` | `Aggregate.verifyInvariants()` override in each aggregate |
| 2 — Operation precondition | Guard at the top of the functionality method | e.g., `if (tournament.isCancelled()) throw …` in `TournamentFunctionalities` |
| 3 — Service-layer guard | DB read + throw before any mutation begins | Service method reading the repo and throwing `QuizzesException` |
| 4 — Input validation | DTO field check | Typically in the command or DTO constructor |
| 5 — Cross-aggregate state guard | Read a second aggregate and assert a condition | e.g., check that the target user is a student before enrolling |
| 6 — Inter-invariant (eventual) | Domain event subscription + handler that adjusts the consumer aggregate | `EventProcessing` chain, subscriber in `…/events/` |

When a refactoring moves code between services or aggregates, every layer that guarded the moved behavior must move with it and must still be reachable via the new code path.

---

## 3. Concurrency Protocol

All operations must pass the sagas test suite after any structural change.

### Sagas — semantic locking

- Implemented via `forbiddenStates` on each aggregate.
- A SAGA step that touches aggregate A must declare A's forbidden state so that a concurrent operation on A is blocked until the saga completes or compensates.
- **Compensation coverage rule**: every saga step must have a corresponding compensating action that undoes its mutation if a later step fails.
- **Test profile**: `test-sagas` — Spock tests under `src/test/groovy/…/sagas/`.

---

## 4. Per-Operation Preservation Checklist

For each operation the checklist lists (a) what structural contracts are at risk and (b) the minimum test evidence required.

### MERGE_AGGREGATES

Two aggregates are merged into one. All fields and invariants must be present in the merged aggregate.

- [ ] `verifyInvariants()` contains all checks from both source aggregates.
- [ ] `forbiddenStates` is the union of both sources' forbidden states.
- **Required sagas tests**: any saga test that uses either source aggregate.

### SPLIT_AGGREGATE

One aggregate is split into two. Each piece must independently satisfy its own invariants.

- [ ] Each new aggregate's `verifyInvariants()` covers the invariants that apply to its fields.
- [ ] Every saga step that now mutates the new aggregate declares its forbidden states.
- **Required sagas tests**: any saga test that uses the source aggregate.

### MOVE_ENTITY

A value object or embedded entity is relocated to a different aggregate. The new owner must enforce all constraints previously enforced by the old owner.

- [ ] Layer 1 invariant that checked the entity is now in the new owner's `verifyInvariants()`.
- [ ] Layer 6 event that propagated the entity's changes still fires and is still subscribed.
- **Required sagas tests**: any saga test that reads or mutates the moved entity.

### CREATE_REPLICA

A read replica of a field from aggregate A is stored in aggregate B. The replica must stay in sync via a domain event.

- [ ] The publisher aggregate still emits the event when the source field changes.
- [ ] The consumer aggregate's event handler updates the replica.
- [ ] Layer 6 polling gap is not increased by the structural change.
- **Required sagas tests**: `AddParticipantAndUpdateStudentNameTest` (or equivalent update-propagation test).

### REMOVE_REPLICA

A replica field is deleted. The consumer aggregate must no longer depend on it.

- [ ] No `verifyInvariants()` check references the removed replica field.
- [ ] No functionality method reads the removed field.
- [ ] The domain event subscription for the removed replica is deleted (no stale handler).
- **Required sagas tests**: any test that previously verified replica consistency.

### MERGE_INVARIANT

Two separate invariant checks on the same aggregate are combined into one. The merged check must reject every input that either source check would have rejected.

- [ ] Write a truth-table test: for each input class rejected by either source, the merged check also rejects it.
- [ ] `INVARIANT_BREAK` error message is preserved (code constant unchanged).
- **Required sagas tests**: any test asserting `INVARIANT_BREAK` on either of the merged conditions.

### SPLIT_INVARIANT

One invariant check is split into two. Each part must still be reachable and independently tested.

- [ ] Both new checks are in `verifyInvariants()` and run unconditionally.
- [ ] Existing tests that exercised the original check still pass against both new checks.
- **Required sagas tests**: invariant-break tests for the original condition.

### REFACTOR_INVARIANT

An invariant check is rewritten (e.g., changed threshold or logic). The new check must cover the same or a wider rejection set.

- [ ] Add a regression test for any input that the old check rejected but the new one might not.
- [ ] `INVARIANT_BREAK` error message constant is unchanged.
- **Required sagas tests**: invariant-break tests targeting the refactored condition.

### TEST_CHANGE

A test file is added or modified without touching production code. The test gate is skipped for this operation because the change is production-safe by construction. However:

- [ ] The new/modified test must compile under the `test-sagas` profile.
- [ ] The test must not introduce `@Ignore` or `.skip` without a dated justification comment.

---

## 5. Test Gate Architecture

The Execute pipeline runs a Docker build with `--target test` before building the runtime image. It must succeed for the pipeline to advance.

```
MUTATING → BUILDING
             ├── docker build --target test      # test gate (test-sagas)
             └── docker build (runtime image, no tests)
DEPLOYING → DEPLOYED
```

**What constitutes pass?** Maven exits 0 and no test reports `FAILED` or `ERROR`.

**Dockerfile test stage** (`applications/quizzes/Dockerfile`):

```dockerfile
FROM base AS test
ARG PROFILE=sagas
RUN mvn clean -Ptest-$PROFILE test
```

`RUN` (not `CMD`) ensures Maven is invoked during `docker build`. The `ARG` default selects the `test-sagas` suite.

**BehavioralPreservationChecker** scans the captured build log after the gate passes, confirms that operation-relevant test classes appeared in the output, and appends a `[COVERAGE]` block to the execute log. A missing test class does not fail the pipeline but is flagged as a warning.

---

## 6. Frozen Contract Catalog

### REST Endpoints (must not change method, path, or parameter names)

| Service | Method | Path |
|---|---|---|
| User | POST | `/users/create` |
| User | GET | `/users/{userAggregateId}` |
| User | POST | `/users/{userAggregateId}/activate` |
| User | POST | `/users/{userAggregateId}/deactivate` |
| User | POST | `/users/{userAggregateId}/delete` |
| User | GET | `/users/students` |
| User | GET | `/users/teachers` |
| Execution | POST | `/executions/create` |
| Execution | GET | `/executions/{executionAggregateId}` |
| Execution | GET | `/executions` |
| Execution | POST | `/executions/{executionAggregateId}/delete` |
| Execution | POST | `/executions/{executionAggregateId}/students/add` |
| Execution | GET | `/users/{userAggregateId}/executions` |
| Execution | POST | `/executions/{executionAggregateId}/students/remove` |
| Execution | POST | `/executions/{executionAggregateId}/anonymize` |
| Execution | POST | `/executions/{executionAggregateId}/students/{userAggregateId}/update/name` |
| Tournament | POST | `/executions/{executionId}/tournaments/create` |
| Tournament | POST | `/tournaments/update` |
| Tournament | POST | `/tournaments/{tournamentAggregateId}/join` |
| Tournament | POST | `/tournaments/{tournamentAggregateId}/leave` |
| Tournament | GET | `/tournaments/{tournamentAggregateId}` |
| Tournament | POST | `/tournaments/{tournamentAggregateId}/solveQuiz` |
| Tournament | GET | `/executions/{executionAggregateId}/tournaments` |
| Tournament | GET | `/executions/{executionAggregateId}/tournaments/opened` |
| Tournament | GET | `/executions/{executionAggregateId}/tournaments/closed` |
| Tournament | POST | `/tournaments/{tournamentAggregate}/remove` |

### Error Message Constants (must not be renamed or removed)

All constants in `QuizzesErrorMessage` are frozen. Key constants that appear in test assertions:

- `INVARIANT_BREAK` — tested in all intra-invariant break tests
- `CANNOT_ADD_PARTICIPANT` — tested in saga enrollment tests
- `AGGREGATE_BEING_USED_IN_OTHER_SAGA` — concurrency saga tests

### Domain Event Types (publisher → consumer must remain wired)

| Event | Publisher | Consumer |
|---|---|---|
| `AnonymizeStudentEvent` | Execution | User, Tournament |
| `CreateQuestionEvent` | Question | Tournament (topic check) |
| `DeleteCourseExecutionEvent` | Execution | Tournament |
| `DeleteQuestionEvent` | Question | Tournament |
| `DeleteTopicEvent` | Topic | Tournament |
| `DeleteUserEvent` | User | Execution, Tournament |
| `DisenrollStudentFromCourseExecutionEvent` | Execution | Tournament |
| `InvalidateQuizEvent` | Quiz | Tournament |
| `QuizAnswerQuestionAnswerEvent` | QuizAnswer | Tournament |
| `UpdateQuestionEvent` | Question | Tournament |
| `UpdateStudentNameEvent` | User | Execution, Tournament |
| `UpdateTopicEvent` | Topic | Tournament |

---

## 7. How to Add a New Behavioral Test

### Base class

All Spock tests extend `QuizzesSpockTest`:

```
applications/quizzes/src/test/groovy/.../quizzes/QuizzesSpockTest.groovy
```

It provides domain helpers: `createCourseExecution()`, `createUser()`, `createTopic()`, `createQuestion()`, `createTournament()`.

### Bean configuration

| Profile | Config class |
|---|---|
| `test-sagas` | `BeanConfigurationSagas.groovy` |

Each test file must declare a static inner `@TestConfiguration` that imports the bean config.

### Test directories

```
src/test/groovy/.../sagas/           # Sagas protocol tests
```

### Concurrency test patterns

**Sagas — pause at a saga step and resume:**
```groovy
executeUntilStep(workflow, stepName)
// assert intermediate state
resumeWorkflow(workflow)
// assert final state
```

### Naming convention

Test classes follow `<Subject><Scenario>Test.groovy`. Keep test method names in sentence form: `'cannot add participant after tournament has started - ENROLL_UNTIL_START_TIME invariant'`.
