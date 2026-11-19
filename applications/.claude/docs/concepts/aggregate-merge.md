# Aggregate Merge — Identity and Structure

What happens to an aggregate when `MergeAggregates` absorbs it into another. Companion to
[`aggregate.md`](aggregate.md), which defines `aggregateId` as *"logical identity — stable across
versions"*; this document defines what becomes of that identity once the aggregate stops being one.

Transversal: the rules hold for any pair, in any application built on the simulator.

---

## §1 Identity before and after

`MergeAggregates(A, B -> M)` demotes `B` from an aggregate root to a **member entity** of `M`. Only
roots extend `Aggregate`, so everything `Aggregate` provides is lost:

| Field | As an aggregate root | As a member entity of `M` |
|-------|---------------------|---------------------------|
| `id` | JPA row PK, new per version | JPA row PK, new per version **of `M`** |
| `aggregateId` | logical identity, stable across versions | **gone** — the *value* survives as `<entity>Id` |
| `version` | own version from `VersionService` | **gone** — only `M` is versioned |
| `state` | `ACTIVE`/`INACTIVE`/`DELETED`, filtered by `findLastAggregateVersion` | **gone** — see §3 |
| `prev` | pointer to the previous version | **gone** — `M` carries the chain |
| `aggregateType` | simple class name | **gone** |

**The rule: the former `aggregateId` value is preserved, renamed `<entity>Id`.**

```java
// before — question/aggregate/Question.java
public abstract class Question extends Aggregate { … }        // identity: inherited aggregateId

// after — course/aggregate/Question.java
@Entity
public class Question {
    @Id @GeneratedValue private Integer id;   // row PK, new per version of Course
    private Integer questionId;               // entity identity, the old aggregateId value
    @ManyToOne private Course course;         // back-reference to the root
    …
}
```

It is an **entity identity**: unique within the aggregate boundary, and — because new members keep
drawing from `AggregateIdGeneratorService` — unique within the merged database too, so it cannot
collide with `M`'s own aggregate ids.

Naming it `<entity>AggregateId` inside `M` would be false: there is no aggregate behind it any more.

## §2 The boundary rule

Inside `M` the field is `<entity>Id`. **At the boundary the wire name does not change.**

| Where | Name | Why |
|-------|------|-----|
| `M`'s member entity, `M`'s repository queries, `M`'s service internals | `<entity>Id` | it is an entity identity |
| commands, DTOs, REST path variables, other services' replicas | `<entity>AggregateId` | not ours to rename |

Two reasons the boundary keeps its name. Other services' replicas
(`QuizQuestion.questionAggregateId`) and command parameters are *their* fields — the merge does not
touch them. And a REST path variable is externally visible: renaming it would be a behaviour change,
which a refactoring must not make.

The mapping `<entity>AggregateId` (wire) → `<entity>Id` (domain) happens in `<M>Service`, at the point
where the owning root is resolved.

## §3 Consequences

**Loading.** `unitOfWorkService.aggregateLoadAndRegisterRead(<entity>Id, uow)` no longer resolves —
after the merge no row carries that value as an `aggregateId`, and the query
(`AggregateRepository.findLastAggregateVersion`) searches `aggregateId` only. It returns empty and the
caller throws `AGGREGATE_NOT_FOUND`, at runtime, with nothing failing at compile time. Every entry
point becomes: resolve the owning root by member id, load it by *its* aggregateId, navigate.

```java
Integer courseAggregateId = courseCustomRepository.findCourseIdByQuestionId(questionId)
        .orElseThrow(() -> new QuizzesException(QUESTION_NOT_FOUND, questionId));
Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
Question question = course.getQuestion(questionId);
```

**Mutation.** Register `M`, never the member — a member is not an `Aggregate`. Copy `M` first via
`create<M>FromExisting`, exactly as the old `<B>Service` copied `B`.

**Copy-on-write.** `M`'s copy constructor must **deep-copy** the absorbed members. Omitting this makes
the new version share member objects with the old one, so mutating the new version rewrites history.
Nothing throws. The idiom already exists —
`Topic(Topic other) { setTopicCourse(new TopicCourse(other.getTopicCourse())); }`.

**Versioning.** Members have no version of their own. External replicas holding `<entity>Version` now
track `M`'s version, and `M`'s version bumps on *any* member change — so subscribers wake more often
than before. Correct, but noisier, and worth recording because the Monitor phase will see the traffic.

**Deletion.** A member has no `state`, so `remove()` has no meaning. Soft-delete becomes either removal
from `M`'s collection (`cascade = ALL` deletes the row) or a member-level flag when an external
consumer still subscribes to the delete event. Either way `M` is the aggregate whose version advances,
which is what makes a collapsed counter invariant fire correctly on delete.

**Events.** `EventSubscription.subscribesEvent` matches on `event.getPublisherAggregateId()`, and event
constructors take the id explicitly (`new UpdateTopicEvent(topicId, name)`) — `registerEvent` sets only
the version. So `M` keeps publishing with the **member's** id and every external subscription keeps
matching, with no consumer-side change. Do not "fix" these to pass `M.getAggregateId()`.

## §4 The trade-off this creates

After a merge, other aggregates reference a **non-root entity** by id. In DDD you reference an
aggregate through its root, so this is a deliberate compromise: it is what preserves behaviour without
rewriting every consumer, and for an N:1 merge it is the only option — many members share one root id,
so the root cannot address an individual member.

It is a documented state, not an accident. The clean-up is a separate catalogue step: `REMOVE_REPLICA`
on a replica that has become redundant, or re-pointing a consumer at `M`'s root where the domain allows.
A merge should record how many consumers are left in this state so the architect can decide whether to
follow up.

## §5 Ownership is a forest

Ownership — "every B belongs to exactly one A for its whole life" — is read from the single-valued
`@OneToOne` parent link on each root (see [`quizzes-composition.md`](../../../quizzes/quizzes-composition.md) §3
for how it is detected). In the quizzes application it forms a forest:

```
Course
├── Topic
├── Question
└── Execution
    ├── Quiz
    │   └── QuizAnswer
    └── Tournament

User        (isolated: owns nothing, owned by nothing)
```

The shape matters: **any two aggregates in the same tree have a lowest common ancestor**, which is what
makes §6 case 4 decomposable rather than refusable.

The edges are **O1 links, not ownership verdicts**: `Execution` hangs under `Course` because it holds
`CourseExecutionCourse`, although the three-signal rule finds that ownership ambiguous (1/3). The
forest is structure, and the chain walked over it is `ownerChain(X) = [X, parent(X), …]` — starting
with `X` itself. When the lowest common ancestor turns out to be one of the two operands, there is no
chain to decompose into; see §6.

## §6 The five cases

`MergeAggregates(A, B)` and `MergeAggregates(B, A)` are the **same operation on the same pair**. The
argument order expresses a preference for which root survives, not a constraint.

| # | Situation | What it means | Outcome |
|---|-----------|---------------|---------|
| **1** | A owns B | every B belongs to exactly one A for its whole life | merge; B becomes a member entity of A |
| **2** | B owns A | the arguments were named the other way round | swap; the owner survives, and the log says so |
| **3** | 1:1 | one concept split across two roots | merge; the **first argument** survives, since neither owns the other |
| **4** | siblings / cousins | no direct ownership, but a common owner exists | **decompose** into a chain of ownership merges up to the lowest common ancestor — unless that ancestor is an operand (`course + execution`): ownership is then *ambiguous*, recorded as a non-blocking restriction with no chain |
| **5** | no relation at all | nothing binds an instance of one to an instance of the other | **refuse** — putting them in one process is a *service* merge and changes no domain boundary |

### Case 4 in practice

| pair | lowest common ancestor | chain |
|------|------------------------|-------|
| question + topic | Course | `course←topic`, then `course←question` |
| tournament + answer | Execution | `quiz←answer`, `execution←quiz`, `execution←tournament` |
| quiz + question | Course | `execution←quiz`, `course←execution` *(ambiguous: 1/3)*, `course←question` |

**What the architect is agreeing to:** merging siblings pulls in their common owner. `question + topic`
cannot yield an aggregate containing only Question and Topic — the only thing binding a Question
instance to a Topic instance is the Course they both belong to. If that is unacceptable, the merge is
not expressible, and that is a property of the domain rather than a limit of the catalogue.

### Why case 5 is a real refusal

`User` owns nothing, is owned by nothing, and is reached only through role-named replicas
(`creatorAggregateId`, `studentAggregateId`, `participantAggregateId`). With no cardinality binding an
instance of one aggregate to an instance of the other, "one aggregate containing both" has no
structural meaning — you would be co-hosting two roots in one process, which is `MergeServices`, a
different operation. This is a domain fact, not a missing operation in the catalogue.

---

## See also

- [`aggregate.md`](aggregate.md) — the base class, variants, factories, repositories
- [`quizzes-composition.md`](../../../quizzes/quizzes-composition.md) — the artefact
  catalogue, the ownership-detection signals, and per-pair verdicts
- `../skills/refactor-merge-aggregate/` — the executable protocol: the classifier,
  one file per case, one per layer
