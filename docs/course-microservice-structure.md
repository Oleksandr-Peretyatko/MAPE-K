# Course Microservice — Structure Reference

## Overview

The Course microservice manages `Course` aggregates: a course has an immutable **name** and **type** (`TECNICO` or `EXTERNAL`), plus mutable counters tracking how many executions and questions it owns.

It is **protocol-agnostic**: the same business logic runs under both **TCC** (causal consistency) and **Sagas** coordination modes. The correct concrete implementations are selected at runtime via Spring profiles.

---

## Directory tree

```
applications/quizzes/.../microservices/course/
├── CourseServiceApplication.java          ← Spring Boot entry point
├── aggregate/
│   ├── Course.java                        ← Abstract domain aggregate
│   ├── CourseDto.java                     ← Serializable DTO
│   ├── CourseType.java                    ← Enum: TECNICO | EXTERNAL
│   ├── CourseFactory.java                 ← Factory interface
│   ├── CourseRepository.java              ← JPA repository (protocol-aware queries)
│   ├── CourseCustomRepository.java        ← Strategy interface for name-lookup
│   ├── CausalCourse.java                  ← TCC specialisation (mergeFields)
│   ├── CausalCourseFactory.java           ← TCC factory  (@Profile("tcc"))
│   ├── CourseCustomRepositoryTCC.java     ← TCC name-lookup  (@Profile("tcc"))
│   ├── SagaCourse.java                    ← Sagas specialisation (sagaState field)
│   ├── CourseSagaState.java               ← Saga state enum: READ_COURSE
│   ├── SagasCourseFactory.java            ← Sagas factory  (@Profile("sagas"))
│   └── CourseCustomRepositorySagas.java   ← Sagas name-lookup  (@Profile("sagas"))
├── commandHandler/
│   └── CourseCommandHandler.java          ← Routes domain commands → service methods
├── coordination/
│   └── CourseFunctionalities.java         ← Orchestration placeholder (currently empty)
└── service/
    ├── CourseService.java                 ← Core business logic
    └── CourseController.java              ← REST controller placeholder (currently empty)
```

---

## Layer-by-layer breakdown

### 1. Entry point — `CourseServiceApplication.java`

- `@SpringBootApplication`, Spring profile `"course-service"`.
- On startup calls `EventService.clearEventsAtApplicationStartUp()` to purge stale domain events.
- Enables JPA entity scanning for course, execution, and simulator core packages; enables scheduling.

---

### 2. Aggregate layer (`aggregate/`)

This is the largest layer and contains both the domain model and its two protocol specialisations.

#### `Course.java` — abstract base aggregate

Extends the simulator's `Aggregate` base class. All persistence and versioning is handled by the framework.

| Field | Type | Mutability |
|---|---|---|
| `name` | `String` | **Final** (COURSE_NAME_FINAL invariant) |
| `type` | `CourseType` | **Final** (COURSE_TYPE_FINAL invariant) |
| `courseQuestionCount` | `int` | Mutable |
| `courseExecutionCount` | `int` | Mutable |

Intra-invariants enforced in `verifyInvariants()`:

- **COURSE_TYPE_FINAL** — type cannot change after creation.
- **COURSE_NAME_FINAL** — name cannot change after creation.
- **CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT** — `courseExecutionCount` must stay > 0 while content exists.

No event subscriptions (`getEventSubscriptions()` returns an empty set — the course does not react to remote domain events).

#### `CourseDto.java`

Serializable transfer object carrying: `aggregateId`, `type`, `name`, `version`, `courseQuestionCount`, `courseExecutionCount`. Constructed directly from a `Course` instance.

#### `CourseType.java`

Enum with two values: `TECNICO` and `EXTERNAL`.

#### `CourseFactory.java` — factory interface

Decouples Course instantiation from business logic. Three methods:

```
createCourse(Integer aggregateId, CourseExecutionDto)   ← new course
createCourseFromExisting(Course)                        ← copy constructor
createCourseDto(Course)                                 ← DTO conversion
```

Profile-specific implementations are injected by Spring at startup.

#### `CourseRepository.java` — JPA repository

`JpaRepository<Course, Integer>` with three protocol-aware custom queries:

| Query method | Used by | Behaviour |
|---|---|---|
| `findCourseIdByNameForTCC` | TCC | Finds active courses by name; no saga state filter |
| `findCourseIdByNameForSaga` | Sagas | Same, but also excludes courses where `sagaState = NOT_IN_SAGA` |
| `findLatestCourse` | Service | Fetches the course with the highest version number |

#### `CourseCustomRepository.java` — strategy interface

Single method: `findCourseIdByName(String courseName)`. Implementations delegate to the correct JPA query above based on the active profile.

---

#### TCC specialisation (`@Profile("tcc")`)

**`CausalCourse.java`**
Extends `Course`, implements `CausalAggregate` from the simulator causal package.
Adds `mergeFields()`: performs an **additive (delta-based) merge** of `courseQuestionCount` and `courseExecutionCount` for causal consistency. When two concurrent updates both increment the count, the merge adds both deltas rather than overwriting.

**`CausalCourseFactory.java`**
Spring `@Service`. Produces `CausalCourse` instances.

**`CourseCustomRepositoryTCC.java`**
Delegates `findCourseIdByName` → `findCourseIdByNameForTCC` (no saga state filtering needed).

---

#### Sagas specialisation (`@Profile("sagas")`)

**`SagaCourse.java`**
Extends `Course`, implements `SagaAggregate` from the simulator sagas package.
Adds a `sagaState` field (default: `GenericSagaState.NOT_IN_SAGA`) that tracks whether this aggregate is participating in an active distributed saga.

**`CourseSagaState.java`**
Enum implementing `SagaState` with one value: `READ_COURSE`. Set on a course that is being read inside an active saga to prevent concurrent modification.

**`SagasCourseFactory.java`**
Spring `@Service`. Produces `SagaCourse` instances.

**`CourseCustomRepositorySagas.java`**
Delegates `findCourseIdByName` → `findCourseIdByNameForSaga` (filters out courses already locked inside a saga).

---

### 3. Command handler layer (`commandHandler/`)

#### `CourseCommandHandler.java`

Extends the simulator's `CommandHandler` base class for aggregate type `"Course"`. Receives domain commands dispatched through the `CommandGateway` and routes them to the service:

| Command | Service method |
|---|---|
| `GetCourseByIdCommand` | `getCourseById()` |
| `GetAndOrCreateCourseRemoteCommand` | `getAndOrCreateCourseRemote()` |
| `GetCourseByNameRemoteCommand` | `getCourseByNameRemote()` |
| `CreateCourseRemoteCommand` | `createCourseRemote()` |
| `DeleteCourseCommand` | `deleteCourse()` |
| `UpdateCourseQuestionCountCommand` | `incrementCourseQuestionCount()` or `decrement…()` based on a flag |
| `UpdateCourseExecutionCountCommand` | `incrementCourseExecutionCount()` or `decrement…()` based on a flag |

All command classes live in `org.anonymous.msrefactor.quizzes.command.course`.

---

### 4. Coordination layer (`coordination/`)

#### `CourseFunctionalities.java`

Currently an **empty Spring `@Service` placeholder**. Intended for orchestrating multi-step operations that span multiple aggregates involving a course (analogous to how `TournamentFunctionalities` orchestrates tournament sagas). No methods are implemented yet.

---

### 5. Service layer (`service/`)

#### `CourseService.java` — core business logic

Spring `@Service`. All transactional methods use **SERIALIZABLE** isolation.

Injected dependencies:

| Dependency | Source | Role |
|---|---|---|
| `UnitOfWorkService<UnitOfWork>` | simulator core | Tracks and commits aggregate changes |
| `AggregateIdGeneratorService` | simulator core | Generates unique aggregate IDs |
| `CourseCustomRepository` | strategy | Profile-aware name lookup |
| `CourseFactory` | strategy | Profile-aware aggregate instantiation |

Key operations:

```
getCourseById(id, uow)
  → aggregateLoadAndRegisterRead(id)  →  CourseDto

getAndOrCreateCourseRemote(dto, uow)
  → findCourseIdByName(name); if absent → createCourseRemote()

createCourseRemote(dto, uow)
  → generateAggregateId() → factory.createCourse() → registerChanged()

deleteCourse(id, uow)
  → load → course.remove() (soft delete) → registerChanged()

incrementCourseQuestionCount(id, uow)
  → load → factory.createCourseFromExisting() → count++ → registerChanged()

decrementCourseQuestionCount(id, uow)
  → same pattern, count--

incrementCourseExecutionCount(id, uow)
  → checks pending aggregates first (course may not be persisted yet in same UoW),
     then falls back to DB load → factory.createCourseFromExisting() → count++ → registerChanged()

decrementCourseExecutionCount(id, uow)
  → same, count--
```

**Immutable-update pattern** used everywhere: load existing → create copy via factory → mutate copy → `registerChanged(copy)`. The original object is never mutated in-place.

#### `CourseController.java`

Empty Spring `@RestController` placeholder. No HTTP endpoints are mapped yet. All current access to course operations goes through the `CommandGateway` routing (via `CourseCommandHandler`).

---

## How the two protocols are selected

Spring profiles wire the correct concrete implementations at startup:

| Component | Profile `"tcc"` | Profile `"sagas"` |
|---|---|---|
| Course entity class | `CausalCourse` | `SagaCourse` |
| `CourseFactory` | `CausalCourseFactory` | `SagasCourseFactory` |
| `CourseCustomRepository` | `CourseCustomRepositoryTCC` | `CourseCustomRepositorySagas` |
| JPA name query used | `findCourseIdByNameForTCC` | `findCourseIdByNameForSaga` |

`CourseService` and `CourseCommandHandler` are identical in both modes — only the injected strategies differ.

---

## Data flow — typical "create course" path

```
External caller
  → CommandGateway.send(CreateCourseRemoteCommand)
      → CourseCommandHandler.handleCommand()
          → courseService.createCourseRemote(dto, unitOfWork)
              → aggregateIdGeneratorService.generateAggregateId()
              → courseFactory.createCourse(id, dto)      ← CausalCourse OR SagaCourse
              → unitOfWorkService.registerChanged(course, uow)
          UnitOfWork commit:
              → course.verifyInvariants()
              → courseRepository.save(course)
  → CourseDto returned to caller
```

---

## Invariants summary

| Invariant name | Layer | Where enforced |
|---|---|---|
| `COURSE_TYPE_FINAL` | Layer 1 (intra-aggregate) | `Course.verifyInvariants()` |
| `COURSE_NAME_FINAL` | Layer 1 (intra-aggregate) | `Course.verifyInvariants()` |
| `CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT` | Layer 1 (intra-aggregate) | `Course.verifyInvariants()` |
