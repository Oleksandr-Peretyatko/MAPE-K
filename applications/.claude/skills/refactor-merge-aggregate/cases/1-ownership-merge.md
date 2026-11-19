# Case 1 — Ownership merge

`AggrA` owns `AggrB`. This is the executable case: `AggrB` dissolves into `M` and `<svcB>` ceases to
exist as a deployment unit.

```
svcA = <AggrA>'s package directory      the SURVIVING service,  e.g. course
svcB = <AggrB>'s package directory      the DISSOLVED service,  e.g. question
M    = AggrA's root, now owning AggrB's entities
```

`<svcA>` keeps its name, port, database, Spring profile and `spring.application.name`. **Nothing is
renamed** — there is no combined `svcA+svcB` name, because `StreamCommandGateway.send` dispatches
in-process only when `service.equals(spring.application.name)`, and renaming would push every
in-process command back through RabbitMQ.

## What dissolution means

`AggrB` stops being an aggregate root. These are deleted, not moved:

| Deleted | Because |
|---|---|
| `Saga<AggrB>` | `M` has its own variant |
| `<AggrB>Repository`, `<AggrB>CustomRepository` + the sagas impl | `M`'s repository is the only entry point |
| `<AggrB>Factory` + the sagas factory | `M`'s factory constructs members |
| `<AggrB>SagaState` | nothing takes a semantic lock on a member — its constants are dropped or moved to `<AggrA>SagaState` first (layer 1.4, SKILL.md reading 6) |
| `<AggrB>Service`, `<SvcB>CommandHandler`, `<SvcB>ServiceApplication` | folded into `<AggrA>`'s |

Not every aggregate has every row: `quiz` has no custom sagas repository. Check before deleting, and
log an absent file as `n/a` rather than treating the list as a promise.

Dissolution is also what makes the rest safe: `resolveServiceName` derives the target service from
the aggregate class's simple name (composition doc §2.2). With `Saga<AggrB>` gone, the saga commit
protocol stops producing `<svcB>` — which is what lets layer 3 re-point `ServiceMapping` without
stranding the commit/abort round-trips. Those two edits are a pair.

**`AggrB` itself is not deleted.** It survives as a member `@Entity` of `M`, holding its own state
and its own identifier. That is layer 2's subject and the part that distinguishes this operation
from deleting an aggregate.

## Run the layers

In order, each file read fully before acting:

1. `../layers/1-aggregate.md` — entities, invariants, variants, repositories, factories
2. `../layers/2-root-join.md` — identity, entry points, versioning, `UnitOfWork` registration
3. `../layers/3-coordination.md` — functionalities, saga steps, commands, events, controllers
4. `../layers/4-deployment.md` — `ServiceMapping`, compose, yamls, routes, app class, DB, tests

Do not reorder them. Layer 2 rewrites the service bodies that layer 3's collapsed steps call; layer 3
deletes the commands whose channels layer 4 then removes. Running layer 4 early leaves a container
that starts against code that does not compile.

Each layer ends with its `LAYER n:` log line. Emit all four.

## Worked example — `course ← question`

The pair the Analyze phase proposes most often, and the one with the clearest payoff.

| | Before | After |
|---|---|---|
| Roots | `Course`, `Question` | `Course` only; `Question` is a member |
| `courseQuestionCount` | an `int` on `Course`, kept current by `UpdateCourseQuestionCountCommand` sent as a saga step | `questions.size()` |
| `CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT` | reads a counter that a remote command maintains | a real intra-aggregate check over the collection |
| `CreateQuestionFunctionalitySagas` | `getCourseStep` → `getTopicsStep` → `createQuestionStep` → `updateCourseQuestionCountStep` | `getTopicsStep` → `createQuestionStep`. `updateCourseQuestionCountStep` becomes a direct `CourseService` call (1.6); `getCourseStep` is **deleted whole** — both its products are gone, so there is nothing to inline (layer 3.4(a)) |
| `QuestionCourse` | a replica of Course held by Question — `courseAggregateId`, `courseName`, `courseVersion` | **deleted** (layer 1.7(b), first test on the id and version; `courseName` has no reader in the tree). `QuestionService:47`'s filter becomes a navigation, `DeleteQuestionEvent`'s payload (`:92`) comes from `M`'s own id, and `QuestionDto:33`'s `setCourse(...)` is built from the root. `CreateQuestionCommand` loses the argument that carried it |
| `QuestionTopic` | `@Replica({"Topic", "Course"})` | → `@Replica("Topic")`. `"Course"` is now local; the class stays, because Topic is not part of this merge |
| `QuizQuestion`, `QuestionAnswer` | `@Replica("Question")` in `quiz` and `answer` | → `@Replica("Course")` — re-pointed to the root that survives, class names and ids untouched (layer 1.7(a)) |
| `AnsweredQuiz` | `@Replica({"Quiz", "Question"})`, `@OneToOne` on `QuizAnswer` | → `@Replica({"Quiz", "Course"})`, and being single-valued it hands `Course` an O1 link over `answer`: logged as a new rival, `Quiz` still the owner (layer 1.7(d)) |
| `questionAggregateId` | `Question`'s `Aggregate.aggregateId` | a plain column on the member — `quiz`, `answer` and `tournament` keep resolving it |
| Services | `course-service` (8089), `question-service` (8084) | `course-service` only; `/questions/**` and `/courses/*/questions/**` move to its `routes:` |
| Locks on Question | quiz (`CreateQuiz`) and answer (`AnswerQuestion`) take `READ_QUESTION` on a question id | **dropped** — Question's states are unchecked (layer 3.9) |
| `getCourseStep`'s `READ_COURSE` | a remote lock on Course | **kept in-process** — Course's states are checked (`CourseRepository`). Its step is deleted, so the `verifySagaState` / `registerSagaState` pair moves to the top of `createQuestionStep`, which is what now mutates `M` (layer 3.4(a)) |

`commands collapsed: 2`. The saving is three broker round-trips: the two sends, and the commit of the
Course lock, which now dispatches in-process — see layer 3.4.

## Common failure, and how it is caught

The failure mode this case exists to prevent is stopping after the package move. Because all eight
services are the same jar and isolation is only component scanning (composition doc §2.1), moving
`microservices/<svcB>/` under `<svcA>/` produces a codebase that compiles, a container that starts
and tests that pass — with two aggregate roots still in it. That is a `MergeServices`.

Three checks catch it, all in layer 3 and layer 4:

- `Saga<AggrB>` and `<AggrB>Repository` must not exist anywhere;
- no `<svcA>`-owned functionality may still call `commandGateway.send` with a command that came from
  `commands/<svcB>/`;
- `commands collapsed:` must be greater than 0 whenever `<svcA>` owned a cross-boundary step.

If any fails, do not report `Result: CHANGED`.
