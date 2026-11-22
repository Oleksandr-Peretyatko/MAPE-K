# Quizzes — Code Composition

Companion to [`quizzes-domain-model.md`](quizzes-domain-model.md) (entities, relationships, rules)
and [`quizzes-aggregate-grouping.md`](quizzes-aggregate-grouping.md) (partition, snapshots, events).
Those two describe the **domain**. This one describes the **code**: which artefacts exist per
aggregate, which structural facts constrain any refactoring of them, which aggregate owns which
(§3–§4, what a merge may name), and where each aggregate can be cut (§6, what a split may name).

It is the reference the `refactor-*` skills read before mutating anything.

---

## §1 — Artefact catalogue

Every aggregate `X` served by service `svc` occupies the same fixed file set. `Y` in the tables below
is any other aggregate.

### Java, under `microservices/<svc>/`

| Artefact | Path | Always present | Notes |
|---|---|---|---|
| Root (abstract) | `aggregate/<X>.java` | yes | `extends Aggregate`; declares `verifyInvariants()` and `getEventSubscriptions()` |
| DTO | `aggregate/<X>Dto.java` | yes | the wire form; what commands return |
| Factory | `aggregate/<X>Factory.java` | yes | abstract; `create<X>`, `create<X>FromExisting`, `create<X>Dto` |
| Repository | `aggregate/<X>Repository.java` | yes | Spring Data over the concrete variant |
| Custom repository | `aggregate/<X>CustomRepository.java` | yes | interface; one impl per transactional model |
| Member entities | `aggregate/<X><Y>.java`, `aggregate/<other>.java` | varies | plain `@Entity`, **not** `Aggregate` — see §2.5 |
| Sagas variant | `aggregate/sagas/Saga<X>.java` | yes | `implements SagaAggregate` |
| Saga state | `aggregate/sagas/states/<X>SagaState.java` | yes | the semantic locks this aggregate offers |
| Sagas factory / repo | `aggregate/sagas/{factories,repositories}/` | yes | |
| Service | `service/<X>Service.java` | yes | `@Transactional(SERIALIZABLE)`; every method takes a `UnitOfWork` |
| Functionalities | `coordination/functionalities/<X>Functionalities.java` | yes | one method per use case, each running its `*FunctionalitySagas` |
| Saga workflows | `coordination/sagas/*FunctionalitySagas.java` | see matrix | `SagaStep` + `registerCompensation` |
| Controller | `coordination/webapi/<X>Controller.java` | yes | may be an empty stub (`CourseController`) |
| Event processing | `coordination/eventProcessing/` | see matrix | polling chain |
| Command handler | `messaging/<X>CommandHandler.java` | yes | **bean must be named `<svc>CommandHandler`** — §2.3 |
| Notification | `notification/{subscribe,handling,handling/handlers}/` | see matrix | only for aggregates that consume events |
| App class | `<X>ServiceApplication.java` | yes | `@Profile("<svc>-service")` + the three scan roots |

### Java, shared

| Artefact | Path |
|---|---|
| Commands | `commands/<svc>/*Command.java` |
| Events | `events/*Event.java` (one flat package for all aggregates) |
| Service routing | `ServiceMapping.java` — one constant per service |
| Errors | `microservices/exception/QuizzesErrorMessage.java` |

### Non-Java

| Artefact | File | Per-service content |
|---|---|---|
| Container + database | `docker-compose.yml` | a `<svc>-service` block and a `<svc>-db` block |
| Build profile | `pom.xml` | a `<svc>-service` `<profile>` |
| Service config | `src/main/resources/application-<svc>-service.yaml` | `spring.application.name`, datasource, `cloud.function.definition`, stream bindings, `server.port`, `grpc.server.port`, **`routes:` predicates** |
| Shared config | `src/main/resources/application.yaml` | `<svc>EventSubscriber-in-0` binding, an entry in `quizzes.function-definition.events`, `<svc>-command-channel` producer + `<svc>ServiceCommandChannel-in-0` consumer bindings |
| Test wiring | `src/test/groovy/.../BeanConfigurationSagas.groovy` | 62 `@Bean` methods grouped **by kind, not by aggregate** — Functionalities, EventProcessing, CustomRepository, Factory, Service, EventHandling, CommandHandler are each their own region. Removing an aggregate means editing 4–6 separate places, not one block. |

### Which optional artefacts each service actually has

| Service | `sagas/` | `eventProcessing/` | `notification/` | port | grpc |
|---|---|---|---|---|---|
| answer | yes | yes | yes | 8082 | 9092 |
| course | **no** | **no** | **no** | 8089 | 9099 |
| execution | yes | yes | yes | 8083 | 9093 |
| question | yes | yes | yes | 8084 | 9094 |
| quiz | yes | yes | yes | 8085 | 9095 |
| topic | yes | no | no | 8086 | 9096 |
| tournament | yes | yes | yes | 8087 | 9097 |
| user | yes | no | no | 8088 | 9098 |

`course` is the outlier: no workflows, no events consumed, an **empty** `CourseController`, and **no
`routes:` block** — nothing reaches Course over HTTP. It is driven purely by commands from
`execution` and `question`. That makes it the cheapest absorber in the application: merging into it
adds no route conflicts and removes no external entry point.

### Gateway routes

| Service | `routes:` Path predicates |
|---|---|
| answer | `/quizzes/*/answer` |
| course | *(none)* |
| execution | `/users/*/executions`, `/executions/create`, `/executions/**` |
| question | `/questions/**`, `/courses/*/questions/**` |
| quiz | `/quizzes/**`, `/executions/{executionId}` |
| topic | `/courses/*/topics/**`, `/courses/*/create`, `/topics/**` |
| tournament | `/tournaments/**`, `/executions/*/tournaments/**`, `/tournament/**` |
| user | `/users/**` |

Routing is pull-based: each service serves its own `/routes` and the gateway polls. Every entry
needs a **unique `id`**, and all eight files use `id: ${spring.application.name}-service` — so two
route blocks merged into one file collapse into a single id unless renamed.

---

## §2 — Structural facts that constrain refactoring

Each is verified against the source; the citation is the fact, this prose is the summary.

### 2.1 — All eight services are the same jar

They differ only by `START_CLASS` and Spring profile (`docker-compose.yml`). Isolation is entirely a
matter of component scanning: `scanBasePackages`, `@EnableJpaRepositories` and `@EntityScan` are all
rooted at `microservices.<svc>`
([`TopicServiceApplication.java:13-27`](src/main/java/org/anonymous/msrefactor/quizzes/microservices/topic/TopicServiceApplication.java)).

**Consequence for merges:** moving a package under `<svcA>`'s tree is *sufficient* to co-host it —
the beans and entities load, the container starts, the tests pass. That is why a merge that stops
after moving files looks successful and is not one. Co-hosting is a `MergeServices`; a
`MergeAggregates` additionally dissolves the second root.

### 2.2 — Service names are derived from the aggregate class, not from `ServiceMapping`

The saga commit protocol addresses services through `resolveServiceName`, which strips `Saga`
from the class's simple name and lower-cases the first letter
([`SagaUnitOfWorkService.java:207-215`](../../simulator/src/main/java/org/anonymous/msrefactor/ms/transaction/sagas/unitOfWork/SagaUnitOfWorkService.java)).
`ServiceMapping` is never consulted there — it routes *domain* commands only.

**Two names do not follow the convention.** The service name is the **package** name, not the class
name lower-cased:

| Aggregate class | Package / service | `resolveServiceName` yields |
|---|---|---|
| `QuizAnswer` | `answer` | `quizAnswer` — **does not match** `spring.application.name: answer` |
| `Execution` | `execution` | `execution` ✔ (though its members are all named `CourseExecution*`) |

`resolveServiceName` logs `Potential bad service name resolution` whenever the result still contains
an upper-case character, which is exactly the `SagaQuizAnswer` case. **Derive `<svc>` from the
package path, never from `<Aggr>.toLowerCase()`.**

### 2.3 — Command dispatch is in-process only under two conditions

`StreamCommandGateway.send` dispatches locally when `command.getServiceName()` equals
`spring.application.name` **and** a bean named `command.getServiceName() + "CommandHandler"` exists.
Otherwise the command goes over RabbitMQ. A merged handler left under the dissolved aggregate's name
is never found, and every command silently takes the broker path.

### 2.4 — Aggregate identity is per-database

`aggregateId` comes from a table sequence in the service's own database
([`AggregateIdGeneratorService`](../../simulator/src/main/java/org/anonymous/msrefactor/ms/aggregate/AggregateIdGeneratorService.java)).
Ids are unique **within a service**, not globally — `topicAggregateId = 3` and
`courseAggregateId = 3` coexist today.

`Aggregate` is mapped `TABLE_PER_CLASS`, and `AggregateRepository.findLastAggregateVersion` resolves
an `aggregateId` polymorphically across **every** aggregate table in the persistence unit.

**Consequence for merges:** after a merge all aggregates in the surviving service draw from one
sequence, so new ids stay unique. Rows that already exist in the two separate databases cannot be
reconciled — which is why a merge **never migrates data**. The dissolved service's database is
abandoned, acceptable only because every service runs `ddl-auto: create` and the Monitor phase
regenerates state with a load test.

### 2.5 — Aggregates are copy-on-write, and members are deep-copied

`Aggregate(Aggregate other)` sets `id = null` and `prev = other`, so every mutation writes a new row
and links it to its predecessor. Every member entity must be copied in the root's copy constructor —
the established idiom is `Topic(Topic other) { setTopicCourse(new TopicCourse(other.getTopicCourse())); }`
([`Topic.java:36-39`](src/main/java/org/anonymous/msrefactor/quizzes/microservices/topic/aggregate/Topic.java)).
A member that is not deep-copied is shared between two versions, and mutating the new version
retroactively alters the old one. This fails silently.

### 2.6 — Event delivery is keyed on the publisher's aggregate id

`EventSubscription.subscribesEvent` matches on event type, `subscribedAggregateId ==
event.getPublisherAggregateId()`, and `subscribedVersion < event.getPublisherAggregateVersion()`
([`EventSubscription.java:30-33`](../../simulator/src/main/java/org/anonymous/msrefactor/ms/aggregate/EventSubscription.java)).

**Consequence for merges:** when an aggregate is absorbed, its events are published by the surviving
root and carry *its* id. Every external subscription built with the absorbed aggregate's id stops
matching — no error, no log line, the consumer simply never updates again.

### 2.7 — Member entities are the existing idiom for "belongs to"

Twenty-two non-`Aggregate` `@Entity` classes already live inside aggregate roots. Most are
**replicas**: a cached copy of another aggregate's fields plus its `<x>AggregateId` and `<x>Version`,
refreshed by events (`quizzes-aggregate-grouping.md` §2). Structurally, an absorbed aggregate becomes
exactly this shape — a surrogate `@Id`, a `@ManyToOne` back-reference to the root, owned by
`@OneToMany(cascade = ALL, mappedBy = …)` — with one difference: it holds the **authoritative**
state, not a snapshot, and needs no event to stay current.

---

## §3 — The ownership graph

An aggregate `A` **owns** `B` when every `B` belongs to exactly one `A` for its whole life. Ownership
is what makes `MergeAggregates(A, B)` possible: `B`'s entities can hang off a single `A` root.

It is decided by three independent signals, all greppable:

| | Signal | How to check |
|---|---|---|
| **O1** | `B`'s root holds a **single-valued** replica of `A` | a `@OneToOne` member on `<AggrB>.java` whose class holds `private Integer <a>AggregateId;` — see below |
| **O2** | `B` is created in `A`'s scope | `B`'s creation `@PostMapping` path contains `{<a>AggregateId}`, or `create<B>` takes `<a>AggregateId` as its first argument |
| **O3** | A parent-scoped collection query exists | `commands/<svcB>/Find<AggrB>sBy<AggrA>IdCommand` or `<AggrB>Service.find<AggrB>sBy<AggrA>Id` |

**O1 must be read from the root's field declaration, not from a class name.** Two traps:

- *The class name does not encode the owner.* `QuizAnswer`'s link to Quiz is `AnsweredQuiz`;
  `Tournament`'s and `Quiz`'s links to Execution are `TournamentCourseExecution` and
  `QuizCourseExecution` — the root class is `Execution`, but its member family is named
  `CourseExecution*`.
- *The cardinality lives on the owning side.* `QuestionTopic` exists and holds a single
  `topicAggregateId`, which looks like ownership — but `Question` declares
  `@OneToMany Set<QuestionTopic>`. Reading the member class alone cannot distinguish the two; reading
  `Question.java` can.

So: **`@OneToOne` on `<AggrB>.java` → candidate; `@OneToMany`/`Set`/`List` → not a candidate.**

**O1 is necessary but not sufficient, and it is frequently ambiguous.** `QuizAnswer` has three
`@OneToOne` members and therefore three candidate owners; `Tournament` has two. The rule: **O1 plus
at least one of O2/O3.** When more than one candidate qualifies, the owner is the one with more
signals; a genuine tie is *ambiguous ownership* and not mergeable.

### Single-valued members of each root

| Root | `@OneToOne` members | Candidate owners |
|---|---|---|
| Course | *(none)* | — root |
| User | *(none)* | — root |
| Topic | `TopicCourse` | Course |
| Question | `QuestionCourse` | Course |
| Execution | `CourseExecutionCourse` | Course |
| Quiz | `QuizCourseExecution` | Execution |
| Tournament | `TournamentCreator`, `TournamentCourseExecution` | User, Execution |
| QuizAnswer | `AnswerCourseExecution`, `AnswerStudent`, `AnsweredQuiz` | Execution, User, Quiz |

`Question`'s `Set<QuestionTopic>`, `Tournament`'s `Set<TournamentTopic>`/`Set<TournamentParticipant>`,
`Quiz`'s `Set<QuizQuestion>` and `Execution`'s `Set<CourseExecutionStudent>` are collection replicas
and are **not** ownership candidates.

### Verdicts

| Child `B` | Candidate owner | O1 | O2 | O3 | Owner? |
|---|---|:--:|:--:|:--:|---|
| Topic | Course | `TopicCourse` | `POST /courses/{courseAggregateId}/create` | `FindTopicsByCourseIdCommand` | **yes** (3/3) |
| Question | Course | `QuestionCourse` | `POST /courses/{courseAggregateId}/questions/create` | `FindQuestionsByCourseAggregateIdCommand` | **yes** (3/3) |
| Tournament | Execution | `TournamentCourseExecution` | `POST /executions/{executionId}/tournaments/create` | `GetTournamentsByCourseExecutionIdCommand` | **yes** (3/3) |
| Tournament | User | `TournamentCreator` | — | — | no (1/3) |
| QuizAnswer | Quiz | `AnsweredQuiz` | `POST /quizzes/{quizAggregateId}/answer` | `GetQuizAnswerByQuizIdAndUserIdCommand` | **yes** (3/3) |
| QuizAnswer | User | `AnswerStudent` | — | `GetQuizAnswerByQuizIdAndUserIdCommand` (compound) | no (2/3) |
| QuizAnswer | Execution | `AnswerCourseExecution` | — | — | no (1/3) |
| Quiz | Execution | `QuizCourseExecution` | `POST /executions/{courseExecutionId}` | — | **yes** (2/3) |
| Execution | Course | `CourseExecutionCourse` | `POST /executions/create` — Course comes from the body | `GetCourseExecutionsByUserIdCommand` is by **User** | no (1/3) |
| Course | — | — | created as a side effect of `CreateCourseExecution` | — | **no owner** (root) |
| User | — | — | `POST /users/create` | — | **no owner** (root) |

`QuizAnswer` is the case that justifies the multi-signal rule: three candidates, resolved to Quiz
(3/3) over User (2/3) and Execution (1/3).

```
Course ──owns──► Topic
Course ──owns──► Question
Execution ──owns──► Tournament
Execution ──owns──► Quiz
Quiz ──owns──► QuizAnswer

referenced by id, not owned:
  Topic     ← Question, Tournament
  User      ← Execution, QuizAnswer, Tournament
  Execution ← Quiz, QuizAnswer, Tournament
  Question  ← Quiz, QuizAnswer
  Course    ← Topic, Question, Execution
```

Being referenced by id from a third aggregate does **not** block a merge — it only decides that the
absorbed entity must keep its old identifier as a member field so those references keep resolving.
It is the *absence of an owner* that blocks.

---

## §4 — Per-pair merge verdict

Ordered pairs `A ← B` (`A` absorbs `B`, `B` dissolves). Everything not listed as feasible has no
ownership path in either direction.

### Feasible

| Invocation | Why | What it buys |
|---|---|---|
| `merge-aggregate course topic` | Course owns Topic (3/3) | removes `topic-service`; `CreateTopic`'s `getCourseStep` goes whole — `TopicCourse` was its only product, and it is **deleted** (pointer + version only, no refresher), so `QuestionTopic` and `TournamentTopic` re-point to `@Replica("Course")` |
| `merge-aggregate course question` | Course owns Question (3/3) | `CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT` becomes intra-aggregate — `courseQuestionCount` turns into a collection size and `UpdateCourseQuestionCountCommand` disappears; `CreateQuestion` loses **two** steps |
| `merge-aggregate execution tournament` | Execution owns Tournament (3/3) | `TournamentCourseExecution` is **deleted** (pointer + version only). The participants keep copying `User`, which this merge does not touch |
| `merge-aggregate execution quiz` | Execution owns Quiz (2/3 — O1+O2) | `QuizCourseExecution` is **deleted** (pointer + version only); `TournamentQuiz` re-points to `@Replica("Execution")`, joining `TournamentCourseExecution` as a second O1 witness |
| `merge-aggregate quiz answer` | Quiz owns QuizAnswer (3/3) | `AnsweredQuiz` **survives**: it copies `{Quiz, Question}`, so only `"Quiz"` drops and it becomes `@Replica("Question")`. `TournamentParticipantQuizAnswer` re-points to `@Replica("Quiz")`. Note `<svcB>` is `answer`, not `quizAnswer` (§2.2), and the application class is `AnswerServiceApplication` |

What happens to each replica is **not** «keep it and warn»: `refactor-merge-aggregate` layer 1.7
decides per name — a copy that ends up inside the merged aggregate loses that name and, when the class
is left with nothing but an id and a version, the class goes; a copy held by a third service is
re-pointed to the surviving root. `@Replica`'s own contract requires it, and `replicaEntities` feeds the
O1 signal that decides the *next* merge.

### Inverted — same pair, other direction

`topic course`, `question course`, `tournament execution`, `quiz execution`, `answer quiz`. The
ownership path exists but runs the other way; the operation swaps the operands and proceeds.

### Reachable through a chain (case 4 — sibling merge)

Neither aggregate owns the other, but both descend from a common owner, so the merge decomposes into a
sequence of ownership merges up to the lowest common ancestor. See
[`aggregate-merge.md`](../../docs/concepts/aggregate-merge.md) §5–§6.

| Invocation | Lowest common ancestor | Chain |
|---|---|---|
| `question topic` / `topic question` | Course | `course<-topic`, `course<-question` |
| `tournament answer` / `answer tournament` | Execution | `quiz<-answer`, `execution<-quiz`, `execution<-tournament` |
| `quiz question` | Course | `execution<-quiz`, `course<-execution (ambiguous: 1/3)`, `course<-question` |

The merged aggregate is the common owner, not a pair-only aggregate: the only thing binding a Question
instance to a Topic instance is the Course they both belong to.

`course execution` is **not** a sibling merge although its owner chains meet: the LCA is `course`, an
operand, so the chain would be the pair itself. Execution satisfies only O1 against Course (created
from a `CourseExecutionDto` body, queried by user, referenced by `quiz`, `answer` and `tournament`), so
ownership is ambiguous rather than absent — a non-blocking restriction, not a prerequisite
(`cases/4-sibling-merge.md`, *Degenerate chain*).

### Not mergeable (case 5 — no relation)

| Invocation | Why |
|---|---|
| anything × `user` | User is a root that owns nothing and is owned by nothing; it is reached only through role-named replicas (`creatorAggregateId`, `studentAggregateId`, `participantAggregateId`). No cardinality binds an instance of it to an instance of anything else, so no aggregate could contain both. |

This is the only true refusal in the application — every other pair sits in the tree rooted at Course
and therefore decomposes.

---

## §5 — Consistency rule locations

Where the rules in `quizzes-domain-model.md` §3 actually live, since a merge moves some of them from
one layer to another. Full taxonomy in
[`docs/concepts/rule-enforcement-patterns.md`](../../docs/concepts/rule-enforcement-patterns.md).

| Layer | Mechanism | Example |
|---|---|---|
| Intra-invariant | `<X>.verifyInvariants()`, synchronous | `Course.verifyInvariants()` → `CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT` |
| Service guard | a read + `throw` before any mutation in `<X>Service` | duplicate checks |
| Saga step ordering | `SagaStep` dependencies + semantic locks | `CreateQuestion`: course → topics → create |
| Remote counter | an `Update…CountCommand` sent as a saga step | `courseQuestionCount`, `courseExecutionCount` |
| Inter-invariant | `getEventSubscriptions()` + `notification/handling/handlers/` | `Question` subscribes `UpdateTopicEvent` |

The remote-counter row is the one a merge deletes outright: it exists **only** because the two
aggregates are apart. When they merge it becomes a collection size, and that conversion is the
operation's consistency payoff.

---

## §6 — Per-aggregate split feasibility

The mirror of §4. That section says which **pairs** a merge may name; this one says which **cuts** a
split may make, and what each one would produce. `SplitAggregate` gives the satellite its own service,
so a feasible cut is one that yields a second aggregate worth deploying — the classifier's five
signals are in `refactor-split-aggregate/SKILL.md`.

Ports below are the first free ones (≥ 8090, grpc = port + 1010) at the time of writing; layer 4.2
re-checks before claiming one.

**The refusals here are seeded, not only written.** The four aggregates that cannot be split —
`course`, `user`, `topic`, `execution` — and the merges that name `user` (§4) exist as rows in
`refactor_constraint`, loaded by `refactor-tool/data/constraints-quizzes.sql` with
`./deploy.sh --seed-basic` or `./deploy-rt.sh --seed-basic`. Analyze therefore blocks those
candidates from the first iteration instead of learning it by failing one. If a verdict below
changes, the row changes with it, or the document and the database start disagreeing.

### Feasible

| Aggregate | Core keeps | Satellite | Name / service / port | Case |
|---|---|---|---|---|
| **tournament** | `TournamentCreator`, `TournamentCourseExecution`, `TournamentQuiz`, `TournamentTopic` + scalars | `TournamentParticipant` + its nested `TournamentParticipantQuizAnswer` | `TournamentParticipation` / `tournamentParticipation` / 8090 · 9100 | **2**, and **6** first (four routes move) |
| **quiz** | `QuizCourseExecution` + scalars | `QuizQuestion` | `QuizContent` / `quizContent` / 8091 · 9101 | **2** |

**tournament — the reference cut.** The satellite is authoritative: `TournamentParticipant` carries
`enrollTime` and its nested answer carries `answered`, `numberOfAnswered` and `numberOfCorrect`, none
of which an event writes. It has a creation path (`AddParticipant`, `POST /tournaments/{id}/join`), a
removal path (`LeaveTournament`) and its own read (`FindParticipant`).

- **Invariants.** 7 stay on the core; `invariantUniqueParticipant` moves to the satellite; 5 straddle.
  `invariantParticipantsEnrolledBeforeStarTime` and `invariantAnswerBeforeStart` both compare against
  the core's `startTime` — replicate that scalar and they stay intra and strong.
  `invariantDeleteWhenNoParticipants` and `invariantCreatorParticipantConsistency` read collections on
  both sides and become inter-invariants. `invariantCancelledFieldsAreFinal` reads both collections
  **and** calls `getPrev()`: it is duplicated on both roots, because two version chains have no single
  `prev`.
- **Inter-invariants.** `ParticipantExists` and `QuizAnswersExist` move to the satellite with all four
  of their pieces; `CourseExecutionExists`, `CreatorExists`, `TopicsExist` and `QuizExists` stay.
  `AnonymizeStudent`, `DisenrollStudent` and `UpdateStudentName` are subscribed by *both* the creator
  and the participant branches of `getEventSubscriptions()`, so both roots subscribe after the cut.
- **Saga steps that split.** `AddParticipant` and `AddParticipantAsync`, `LeaveTournament`,
  `SolveQuiz` and `SolveQuizAsync`, `UpdateParticipantAnswer`, `UpdateUserName`,
  `AnonymizeUserTournament`.
- **Third parties to re-point: none.** No aggregate holds a `@Replica("Tournament")`, no `*AggregateId`
  of a tournament is stored anywhere, and no service sends tournament a command. It is the only leaf
  in the application, which is what makes it the cheapest cut externally and the most expensive
  internally (23 workflows, 63 files).

**quiz.** `QuizQuestion` is `@Replica("Question")` and still authoritative: `sequence` is enforced by
`invariantQuizQuestionSequenceConsecutive` and no event refreshes it. Of the four intra rules, two
stay on the core, that one moves, and `invariantFieldsFinalAfterAvailableDate` straddles **and** calls
`getPrev()` → duplicated. `QuestionsExist` moves to the satellite with its quad;
`CourseExecutionExists` stays. Steps that split: `CreateQuiz`, `RemoveQuizQuestion`,
`UpdateQuestionInQuiz`, `UpdateQuiz`. Two local facts: `quiz` has **no** `QuizCustomRepository` and no
sagas repository, so the satellite creates the first ones in this service; and `QuizOption` is an
unreferenced `@Embeddable` that moves nowhere. Re-point: `answer` and `tournament`, which hold
`quizAggregateId`.

### Feasible only if the domain admits the satellite alone — decide before running

| Aggregate | Candidate satellite | What is in doubt |
|---|---|---|
| **answer** | `QuestionAnswer` — authoritative (`optionSequenceChoice`, `timeTaken`, `optionKey`, `correct`) | it has a creation path (`AnswerQuestion`) and a removal path (`RemoveQuestionFromQuizAnswer`), but no id of its own and no read that starts from it. `QuizAnswer.verifyInvariants()` is empty, so there is nothing to partition on the invariant side. Two standing hazards: the core's own service name already breaks the naming rule (§2.2), and `answer`'s event quad is 5/6/6/6 before the split, so the differential gate must carry that mismatch forward |
| **question** | `Option` — the only unit that is not a replica | it is constructed inline inside `CreateQuestion`, no functionality creates it, and nothing addresses it. That is **case 3** with a prerequisite, unless the domain accepts an option that exists without its question |

### Degenerate — case 5, non-blocking restriction

| Aggregate | Why | Cheaper operation |
|---|---|---|
| **topic** | its only unit, `TopicCourse`, is a pure replica (`courseAggregateId`, `courseVersion`); the core would keep only `name`; `verifyInvariants()` and `getEventSubscriptions()` are both empty | `remove-replica topic TopicCourse --source course`, or the merge `course ← topic` (3/3 in §4) |
| **execution** | both units are pure replicas — `CourseExecutionCourse` of Course, `CourseExecutionStudent` of User. `removedNoStudents()` would move to a satellite that only asserts its own cache is empty, and `UsersExist` would make that cache the sole subscriber of the user events | leave it; or the merges `execution ← quiz` / `execution ← tournament` (§4) |

### Not partitionable — case 4, blocking restriction

| Aggregate | Evidence |
|---|---|
| **course** | `course/aggregate/` holds `Course`, `CourseDto`, `CourseFactory`, `CourseRepository`, `CourseCustomRepository` and the enum `CourseType`. One entity, zero composition units. It also has no sagas, no events and no routes |
| **user** | `user/aggregate/` holds `User`, `UserDto`, `UserFactory`, `UserRepository`, `UserCustomRepository` and the enum `Role`. One entity, zero units |

### What a cut costs outside the aggregate

How many other services must be re-pointed — replicas plus inbound commands — if each aggregate is
split: **tournament 0**, answer 1, quiz 2, topic 2, question 3, execution 3, user 3, course 4. It is
the same asymmetry §3 describes from the other side: tournament references six aggregates and is
referenced by none.

### Re-deriving this section

Every verdict above comes from the code, and comes back from it with:

```bash
M=src/main/java/org/anonymous/msrefactor/quizzes/microservices
ls $M/<svc>/aggregate/*.java                             # the file set
grep -l "@Replica" $M/<svc>/aggregate/*.java             # which members are replicas
grep -n "@OneToMany\|@OneToOne" $M/<svc>/aggregate/<Aggr>.java   # the composition units
sed -n '/verifyInvariants/,/^    }/p' $M/<svc>/aggregate/<Aggr>.java
grep -n "@InterInvariant" $M/<svc>/aggregate/<Aggr>.java  # which side each anchor lands on
grep -rn "setSemanticLock\|setForbiddenStates" $M/<svc>/coordination/sagas/
```

A field that no event-processing path writes is authoritative, and one such field is what separates
the *Feasible* rows from the *Degenerate* ones. The case numbers are those of
`refactor-split-aggregate/cases/`; a verdict naming a case file that does not exist is drift, not a
finding.

**Analyze now applies this criterion itself.** `AggregatePartitionAnalyzer` refuses a cut whose
satellite holds nothing but declared replicas, so the *Degenerate* rows no longer reach the candidate
list and the skill's case 5 is the **second** barrier rather than the only one. It approximates the
field test with a proxy — a unit is authoritative when it is not `@Replica` **or** it owns a subtree —
which is what keeps `TournamentParticipant` on the feasible side despite being a copy of `User`. Where
this section and the candidate list disagree, the tree moved: re-derive both.
