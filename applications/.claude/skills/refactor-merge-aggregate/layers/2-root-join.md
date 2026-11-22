# Layer 2 — Root join

Layer 1 made `AggrB` a member class. This layer makes it **work**: how it is identified, how it is
found, how it is versioned, and what gets registered with the `UnitOfWork`.

This is where merges go wrong silently. Everything here compiles either way; what fails is a lookup
returning empty, a subscriber that stops updating, or a member shared between two versions of `M`.

---

## 2.1 — Identity: the value survives, the concept does not

Full model in `../.claude/docs/concepts/aggregate-merge.md` §1–§2. In short: `AggrB`'s `aggregateId` was an
`Aggregate` field, and `AggrB` is no longer an aggregate. The **value** is preserved as an entity
identity on the member:

```java
private Integer <aggrB>Id;      // NOT <aggrB>AggregateId — there is no aggregate behind it now
```

**Preserving the value is the default, and for an N:1 merge it is the only correct choice.** Many
members share one root, so `M`'s `aggregateId` cannot address an individual `AggrB`. Re-pointing
external references at `M`'s id would make every member but one unreachable.

### The boundary rule

The rename stops at `M`'s edge:

| Where | Name | Why |
|---|---|---|
| the member entity, `<AggrA>CustomRepository` queries, `<AggrA>Service` internals | `<aggrB>Id` | it is an entity identity |
| commands, DTOs, REST path variables, other services' replicas | `<aggrB>AggregateId` | not ours to rename |
| a DTO field whose source was a replica 1.7 deleted | unchanged | the **name stays and the source moves**: `TopicDto.courseId` was `topic.getTopicCourse().getCourseAggregateId()` and becomes the back-reference. A DTO field that disappears is a silent client break |

Other services' replicas (`QuizQuestion.questionAggregateId`) and command parameters are *their*
fields — this merge does not touch them. And a REST path variable is externally visible, so renaming
it would be a behaviour change. Map wire → domain in `<AggrA>Service`, where the owning root is
resolved.

What preserving the value buys — all of it behaviour that must not change:

| Keeps working | Example |
|---|---|
| third-party replicas | `TournamentTopic.topicAggregateId`, `QuizQuestion.questionAggregateId` |
| commands from other services | `GetTopicByIdCommand`, `FindQuestionsByTopicIdsCommand` |
| REST paths | `/topics/{topicAggregateId}`, `/questions/{aggregateId}` |
| **event delivery** | see 2.5 — this is the one that fails silently |

New members still draw their identifier from `aggregateIdGeneratorService.getNewAggregateId()`. The
merged service has one sequence, so member identifiers and `M`'s aggregate ids remain mutually
unique (composition doc §2.4).

Dropping the identifier is permitted **only** when SKILL.md's first "further reading" found zero
third-party references *and* the merge is 1:1 (case 3). Record which you did in the log line.

**Count what is left referencing a member entity.** Other aggregates now reach a non-root entity by
id — a deliberate compromise (`aggregate-merge.md` §4), resolved later by `RemoveReplica` or by
re-pointing the consumer. Report the number in the `Impact:` line so the architect can decide.

## 2.2 — Loading: resolve the owner, then navigate

`unitOfWorkService.aggregateLoadAndRegisterRead(<b>AggregateId, unitOfWork)` no longer resolves: no
row in the merged tables carries that value as an `aggregateId`, and the query
(`AggregateRepository.findLastAggregateVersion`) searches `aggregateId` only. It returns empty and
the caller throws `AGGREGATE_NOT_FOUND` — at runtime, not at compile time.

**One rewrite, applied to every entry point that took a `<b>AggregateId`:**

```java
// before — in <AggrB>Service
Topic topic = (Topic) unitOfWorkService.aggregateLoadAndRegisterRead(topicAggregateId, unitOfWork);

// after — in <AggrA>Service
// The parameter keeps the wire name; inside M it is an entity id (2.1 boundary rule).
Integer topicId = topicAggregateId;
Integer courseAggregateId = courseCustomRepository.findCourseIdByTopicId(topicId)
        .orElseThrow(() -> new QuizzesException(TOPIC_NOT_FOUND, topicId));
Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
Topic topic = course.getTopic(topicId);
```

Three parts, each mandatory:

1. **A member-id → root-id lookup on `<AggrA>CustomRepository`.** Add one method per absorbed member
   type: `Optional<Integer> find<AggrA>IdBy<AggrB>Id(Integer <aggrB>Id)`. Implement it in the sagas
   variant repository (`…RepositorySagas`).
   `CourseCustomRepository.findCourseIdByName` is the existing shape to copy.
2. **The root is what gets loaded and registered**, so the `UnitOfWork` sees `M`, the semantic lock
   is taken on `M`, and versioning behaves.
3. **A `get<AggrB>(id)` accessor on `M`** that filters the collection. Throw the *same*
   `QuizzesErrorMessage` the old lookup threw, so callers and tests see no change in behaviour.

Only when layer 3.9 needs it, a fourth part:

4. **A lookup command.** A remote step that retargets a checked lock to `M` needs `M`'s id *before* it
   sends. When its saga holds no data carrying that id, add `Get<AggrA>IdBy<AggrB>IdCommand` to
   `commands/<svcA>/`, with an arm in `<SvcA>CommandHandler` that returns part 1's
   `find<AggrA>IdBy<AggrB>Id` — sent bare, with no `SagaCommand` wrapper and no lock.

Rewrite these, all of which existed in `<AggrB>Service`:

| Old | New |
|---|---|
| `get<AggrB>ById(id, uow)` | lookup → load root → `get<AggrB>(id)` |
| `find<AggrB>sBy<Parent>Id(parentId, uow)` | load the root by `parentId` → return its collection. **This one gets faster**: it was a `findAll().stream().filter(…)` over every row. When the filter read the replica 1.7 deleted — `TopicService:51` compares `t.getTopicCourse().getCourseAggregateId()` — the navigation replaces the whole scan, not just the field. |
| `update<AggrB>(dto, uow)` | lookup → load root → copy root → mutate the member → register the **root** |
| `delete<AggrB>(id, uow)` | lookup → load root → copy root → remove/flag the member → register the **root** |

`<AggrB>CustomRepository`'s queries, copied across in layer 1.4, are rewritten here as navigations
from the root. None may be dropped.

## 2.3 — Mutation: copy the root, register the root

The versioning model is copy-on-write: `Aggregate(Aggregate other)` sets `id = null` and
`prev = other`, and `registerChanged` calls `verifyInvariants()`, assigns a new version and merges.

Two rules, both absolute:

- **Never `registerChanged` a member.** It is not an `Aggregate`; the call will not compile, and the
  workaround of registering the member's root without copying it first is worse — it mutates the
  committed version in place.
- **Copy the root before mutating a member**, via `<AggrA>Factory.create<AggrA>FromExisting(existing)`,
  exactly as the old `<AggrB>Service` did for `AggrB`.

## 2.4 — `M`'s copy constructor must deep-copy the absorbed members

```java
public Course(Course other) {
    super(other);
    …
    setQuestions(other.getQuestions().stream().map(Question::new).collect(Collectors.toSet()));
}
```

`Question(Question other)` and `Topic(Topic other)` already exist as public copy constructors — layer
1 kept them when it demoted the class. Wire them in.

**Omitting this is the single most damaging mistake in the whole operation.** The new version holds
the *same* member objects as the old one, so mutating the new version retroactively rewrites history,
and `prev` chains stop being meaningful. Nothing throws. `Question(Question other)` deep-copying its
`Set<QuestionTopic>` is the idiom to match.

**The list is what layer 1 left, not what the class held before.** A replica 1.7 deleted comes off it,
and the line that copied it goes with the class — `Topic(Topic other)` loses
`setTopicCourse(new TopicCourse(other.getTopicCourse()))`. Copying a deleted class does not compile, so
this one announces itself; the reverse mistake, leaving a member out of the list, does not.

## 2.5 — Events keep the member's identifier

`EventSubscription.subscribesEvent` matches `subscribedAggregateId` against
`event.getPublisherAggregateId()` (composition doc §2.6). Naively letting `M` publish with its own id
would silently stop every external subscriber.

It does not have to. **The event constructor takes the id explicitly:**

```java
public UpdateTopicEvent(Integer topicAggregateId, String topicName) { super(topicAggregateId); … }
```

`registerEvent` sets only the *version*, never the publisher id. So `M` keeps constructing the event
with the **retained member identifier**, and every external subscription continues to match with no
change on the consumer side:

```java
// in CourseService, after updating an absorbed Topic — unchanged from TopicService
unitOfWorkService.registerEvent(new UpdateTopicEvent(topic.getTopicAggregateId(), topic.getName()), unitOfWork);
```

Do **not** "fix" these constructor calls to pass `M.getAggregateId()`. Layer 3 decides which events
survive at all; this rule governs the ones that do.

## 2.6 — Versioning of members

Members no longer carry their own `version` — only roots do, and `M`'s version advances on any member
change.

Version numbers come from a per-service counter (`versionService.incrementAndGetVersionNumber()`),
so after the merge a member's events are versioned by `<svcA>`'s counter rather than `<svcB>`'s. No
merge migrates data (composition doc §2.4) and every service runs `ddl-auto: create`, so there is no
in-flight replica holding a stale `<svcB>`-counter version to strand. Do not attempt to reconcile
counters.

**Accepted consequence, and it belongs in the log:** a change to *any* member bumps `M`'s version, so
every subscriber to anything `M` publishes wakes more often than before. Correct, but noisier — and
the Monitor phase will see the extra traffic, so it must be attributable:

```
Impact note: <n> subscriber(s) now observe M's version; member-level changes fan out more widely
```

## 2.7 — Deletion semantics

`AggrB.remove()` set `state = DELETED` on a root, and `findLastAggregateVersion` filters on
`state = 'ACTIVE'`. A member has no state field, so pick per member type:

| If | Then |
|---|---|
| nothing outside `M` observes the deletion | remove from `M`'s collection (`cascade = ALL` deletes the row) |
| an external consumer subscribes to `Delete<AggrB>Event`, or a replica elsewhere holds its id | keep the row and add a member-level flag; `M.get<AggrB>(id)` skips flagged members, and the delete event still fires with the retained identifier |

**The flag is also what replaces a third party's `state` mirror.** `QuestionTopic` and `TournamentTopic`
copy `AggrB`'s `AggregateState` to answer "was it deleted"; after the merge `AggrB` has no state to
copy, so that question is answered by this flag reaching them through `Delete<AggrB>Event` — the second
row is mandatory, not a choice, whenever 1.7(c) found one.

Either way `M` is the aggregate that is copied and registered, and `M.verifyInvariants()` runs after
the removal — which is exactly what makes a collapsed counter invariant (layer 1.6) fire correctly on
delete.

## 2.8 — Verify

```bash
# no load of a member id as if it were an aggregate id
grep -rn "aggregateLoadAndRegisterRead(<aggrB>Id\|aggregateLoad(<aggrB>Id" src                     # empty
# piped, not globbed — see layer 1.8. The positive line is the control for the empty one below it.
# control: the member's entity identity exists inside M
grep -rn "<aggrB>Id" src --include=*.java | grep -c "/microservices/<svcA>/aggregate/"             # >= 1
# and the member must not keep the aggregate-flavoured name inside M:
grep -rn "<aggrB>AggregateId" src --include=*.java | grep "/microservices/<svcA>/aggregate/"        # empty
# the lookup exists in the interface and the sagas implementation
grep -rln "find<AggrA>IdBy<AggrB>Id" src --include=*.java | grep "/microservices/<svcA>/aggregate/" # 2 hits
# the copy constructor deep-copies the absorbed collection
grep -rn "public <AggrA>(<AggrA> other)" -A 15 src --include=<AggrA>.java                          # inspect: every member collection is mapped through its copy constructor
# events still publish the member id
grep -rn "new <Event>(" src | grep -v "<aggrB>Id\|get<AggrB>Id"                                     # inspect each hit
```

---

## Log line

```
LAYER 2 (root join): <AggrB> identity kept as entity id <aggrB>Id (<n> external references still reach a member entity); <n> entry point(s) rewritten to owner-lookup + navigate; <AggrA> copy constructor deep-copies <n> member collection(s); <n> event(s) keep the member identifier
```
