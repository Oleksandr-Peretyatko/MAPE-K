# Layer 1 — Aggregate

Turn `AggrB` from a root into a member of `M`. This layer is about *structure*: which classes move,
which are deleted, and where the invariants end up. **Identity, loading and versioning are layer 2** —
do not solve them here.

One sub-step per folder of `microservices/<svcB>/aggregate/`. A folder that does not exist is logged
as `n/a`, never skipped silently.

> See `../.claude/docs/concepts/refactoring-surface.md` §1 for the full section inventory.

---

## 1.1 — Read both roots

`<AggrA>.java` and `<AggrB>.java` in their `aggregate/` packages. The **base class is the source of
truth**, not the `Saga*` variant: it declares the entities, `verifyInvariants()` and
`getEventSubscriptions()`; the variant adds only saga state.

Enumerate, and write the list into the log:

- `AggrB`'s member entity classes and their nested children
- `AggrB`'s `invariant…()` methods and everything `verifyInvariants()` reads
- `AggrB`'s DTO and what it carries
- every `@Replica` declaration on either side, and every one **elsewhere** that names `AggrA` or
  `AggrB` — 1.7 classifies them, and the ones in third services are not visible from these two packages

The header comment block on each root (`INTRA-INVARIANTS:` / `INTER-INVARIANTS:`) names the rules by
their `QuizzesErrorMessage` constant. It is a reliable index, but verify each against the code — it
is a comment, not a compiler-checked declaration.

## 1.2 — Move `AggrB`'s entities into `<AggrA>`'s package

Move `AggrB`'s member entity classes, its DTO and their nested children into
`microservices/<svcA>/aggregate/`, update the package declarations and every import.

`AggrB`'s own member entities keep their shape unchanged — they were already plain `@Entity` classes
hanging off a root, and they still are; only the root above them changes.

## 1.3 — Convert `AggrB` into a member entity

`AggrB` stops extending `Aggregate`. Its target shape is the one the codebase already uses 22 times
for member entities (composition doc §2.7):

```java
@Entity
public class <AggrB> {
    @Id @GeneratedValue
    private Integer id;              // JPA row PK — new per version of M, NOT the old aggregateId
    private Integer <aggrB>Id;       // entity identity: the old aggregateId *value* — layer 2 owns it
    @ManyToOne
    private <AggrA> <aggrA>;         // back-reference to the root
    // …AggrB's own fields, unchanged
    // …AggrB's own member entities, unchanged
}
```

**The field is `<aggrB>Id`, not `<aggrB>AggregateId`.** Once `AggrB` stops extending `Aggregate` there
is no aggregate behind that value: it is an entity identity, unique within `M`'s boundary. Naming it
`…AggregateId` inside `M` states something false, and every later reader inherits the confusion. The
*wire* name is a separate question — commands, DTOs and REST paths keep theirs; see layer 2's boundary
rule and `../.claude/docs/concepts/aggregate-merge.md` §1–§2.

Remove from it: `extends Aggregate`, the `super(aggregateId)` call,
`setAggregateType(getClass().getSimpleName())`, the `@Override` on `verifyInvariants()` and
`getEventSubscriptions()`, and `remove()`. Everything `Aggregate` supplied goes with it — `version`,
`state`, `prev`, `aggregateType` — and only the `aggregateId` *value* survives, as `<aggrB>Id`. What
replaces each is layer 2's subject.

On `M`'s root, add the owning side, matching the cardinality the classifier found:

```java
@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "<aggrA>")
private Set<<AggrB>> <aggrB>s = new HashSet<>();
```

`Question` is the model to copy — it owns `QuestionCourse` (`@OneToOne`), a `Set<QuestionTopic>` and
a `List<Option>` in exactly this style.

Add the same field to `Saga<AggrA>` **only if** it declares its own copy constructor over entity
state; normally it delegates to `super(other)` and needs no change. Check, do not assume.

## 1.4 — Delete `AggrB`'s root machinery

Under dissolution these have no remaining referent:

```
aggregate/sagas/Saga<AggrB>.java
aggregate/sagas/factories/Sagas<FamB>Factory.java
aggregate/sagas/repositories/<FamB>CustomRepositorySagas.java   ← verify: not every aggregate has one
aggregate/<FamB>Repository.java
aggregate/<FamB>CustomRepository.java
aggregate/<FamB>Factory.java
<SvcB>ServiceApplication.java                                   ← the class that made this package a process
```

**Verify the list before acting on it.** Seven of the eight aggregates have a custom sagas
repository; `quiz` has no `aggregate/sagas/repositories/` at all
(`../.claude/docs/concepts/refactoring-surface.md` §3.3). Deleting a file that is not there is
harmless; *creating* one because the list said so is not.

**Three prefixes, and only one of them is `<AggrB>`.** `Saga<AggrB>` takes the root's name, the six
files above it take the **member-family** prefix, and the application class takes the **service** name
— SKILL.md's *Resolving names* has the table and the two services where they differ. `ls` the two
directories and read the prefixes off the files: for `execution` this list is `SagasCourseExecutionFactory`,
`CourseExecutionRepository`… beside `SagaExecution`, and for `answer` the last line is
`AnswerServiceApplication.java`, not `QuizAnswerServiceApplication.java`.

**The application class is deleted here, not in layer 4.** Section 20 is a layer-4 section and layer
4.8 is what *verifies* it, but the file lives inside `microservices/<svcB>/` and this is the layer that
empties that package. Leaving it behind compiles, starts nothing new, and keeps every per-service
measure counting a deployment unit that has no aggregate — 4.11's seven readings would report 8 where
they must report 7.

**`<AggrB>SagaState` depends on SKILL.md's reading 6.**

| `AggrB`'s lock states | The enum |
|---|---|
| unchecked | delete it with the files above; layer 3.9 removes every reference |
| checked | move its constants into `<AggrA>SagaState` under the same names, then delete the empty enum; layers 3.7 and 3.9 re-point the references |

Before moving, check for a name clash. In quizzes the only clashes are `READ_COURSE` (Course and
CourseExecution) and `READ_STUDENT` (CourseExecution and User), and none of the mergeable pairs hits
one; if one exists, prefix the moved constant with `<AGGB>_`. `SagaStateConverter` persists
`FQCN:NAME`, so rows written before the move would not read back — acceptable only because every
service runs `ddl-auto: create`. Say so in the log.

Deleting `Saga<AggrB>` is what stops `resolveServiceName` producing `<svcB>` (composition doc §2.2).
It is the precondition for layer 4's `ServiceMapping` edit, and the two must both happen or neither.

**Absorb `<AggrB>CustomRepository`'s queries into `<AggrA>CustomRepository`**, re-expressed as
navigations from the root rather than lookups of a root. Layer 2 specifies how each one is rewritten;
here, only make sure none is dropped — copy the interface methods across first, then let layer 2
rewrite the bodies.

**Fold `<AggrB>Factory` into `<AggrA>Factory`.** `create<AggrB>` becomes a method that constructs a
member and attaches it to a loaded root; `create<AggrB>Dto` stays as-is, since the DTO shape is
unchanged. `create<AggrB>FromExisting` disappears: only roots are versioned, and members are copied
by `M`'s copy constructor.

## 1.5 — Place the invariants

Every invariant of both aggregates survives. Where it goes depends on what it ranges over — the
second "further reading" in SKILL.md classified them:

| `AggrB`'s invariant ranges over | Goes to | Note |
|---|---|---|
| only `AggrB`'s own state | a private `verify<AggrB>(<AggrB> b)` called in a loop from `M.verifyInvariants()` | mechanically unchanged; only the receiver moves |
| `AggrA ∪ AggrB` | a direct check in `M.verifyInvariants()` | **the payoff** — was eventually consistent, now synchronous. Name it individually in the log. |
| a third aggregate | stays an inter-invariant | its `EventSubscription` moves to `M.getEventSubscriptions()`; layer 3 re-points the subscribed id |

`AggrA`'s existing invariants are preserved verbatim unless 1.6 changes what they read.

`M.verifyInvariants()` runs on **every** commit of `M`, and now covers both entity sets. An invariant
that was O(1) over one root can become O(n) over a collection. That is correct and expected; note it
in the log if the collection is unbounded.

## 1.6 — Collapse remote counters

A field on `AggrA` that exists only to avoid reading `AggrB` — kept current by an
`Update…CountCommand` sent as a saga step — is not domain state once the two are merged. It is a
cache of something `M` can now count.

Replace the field with the query, and put the command on layer 3's delete list:

```java
// before
private int courseQuestionCount = 0;
if (courseExecutionCount == 0 && courseQuestionCount > 0) { throw … }

// after
if (courseExecutionCount == 0 && !questions.isEmpty()) { throw … }
```

The error, its `QuizzesErrorMessage` constant and the condition's meaning are unchanged — only the
source of the number is. Do **not** collapse a counter whose source aggregate is *not* part of this
merge: under `course ← question`, `courseQuestionCount` collapses and `courseExecutionCount` does
not.

Recognising one: an `int`/`long` field on the root, with a public setter, mutated only by a command
handler arm, and never set in the constructor.

## 1.7 — Replicas and consistency annotations

1.6 collapsed a *field* that existed only to avoid reading `AggrB`. This subsection does the same for
the *classes*: a replica is a copy of another aggregate's state, and the merge is precisely the event
that makes that other aggregate local. `Replica.java`'s own contract says so — "one that dissolves the
boundary — a merge that makes the copied aggregate a local member — removes it, because the class stops
being a replica".

This is not bookkeeping. `@Replica` is the only link the analysis has between a class and what it
copies, and the map it produces (`replicaEntities`) feeds the **O1 signal** that decides which merges
are proposed next. A stale declaration keeps pricing a replication path that no longer exists; a
declaration naming a root that stopped being a root resolves to nothing and is **dropped silently**,
taking the `@InterInvariant` attribution of that class with it.

### (a) The rule is per name, not per class

`@Replica` carries a list of roots (`@Replica({"Quiz", "Question"})`). A merge touches the names that
name `AggrA` or `AggrB`, and leaves the rest alone:

| The name in the list | Where the class lives | Action |
|---|---|---|
| `AggrB` or `AggrA` | inside `M` | **remove the name** — that state is now directly reachable. If the list empties, the annotation goes with it and (b) decides the class |
| `AggrB` | in a third service | **re-point it to `AggrA`**, the root that survives. If `AggrA` is already in the list the two collapse into one: `@Replica({"Topic","Course"})` → `@Replica("Course")` |
| any other root | anywhere | untouched |

The **class name never changes** — `TournamentTopic` stays `TournamentTopic`. Layer 2.1 already rules
that names other services read are not ours to rename.

**Position in the list is meaning, so re-pointing keeps it.** The annotation is documented "primary
first", and `QuestionTopic` carries a topic and, denormalised, the course — in that order.
`AnsweredQuiz` under `course ← question` becomes `@Replica({"Quiz", "Course"})`, not
`{"Course", "Quiz"}`: the class still copies a quiz first. Reordering the list says the class changed
what it is about, which it did not.

### (b) What happens to a class that stopped being a replica — three tests, in order

| Test | Verdict |
|---|---|
| Does it carry only `<owner>AggregateId` + `<owner>Version`? | **Delete the class.** There is nothing to copy: the id is the `@ManyToOne` back-reference 1.3 adds to the member, and the version is `M`'s — a member entity has none of its own |
| Was any copied field refreshed — a `process…Event`, or a setter called from the service? | **Delete the field and navigate.** It tracked the live value, and inside `M` the live value is on the member. 1.6's template, applied to a field of a class |
| Was it refreshed by nobody **and** is there a reader that means "as it was then"? | **Keep the field** on the member, without the annotation, and name in the log the reader that justifies it |

The third test **demands the reader**. "Nobody refreshes it" is not licence to keep it: an unrefreshed
copy of what is now local is a latent bug, not a snapshot. `QuestionCourse.courseName` has no reader
anywhere in the tree — it is deleted, not preserved as denormalisation.

Deleting a class is not confined to this layer. Its call sites are layer 2's and 3's, and they are
listed where they belong: the DTO's source in 2.1, the navigation in 2.2, the deep-copy list in 2.4,
and — the one that pays for the merge — the saga step whose only product was the copy, in 3.4(a).

### (c) The `state` field of a third party's replica

`QuestionTopic` and `TournamentTopic` copy `Aggregate.AggregateState` from the root they watch. After
the merge `AggrB` **has no state of its own** — 1.3 takes `version`, `state` and `prev` away — so that
mirror can never be refreshed again and "was it deleted" freezes on whatever it last held. Deletion
detection moves to the member-level flag **layer 2.7** already defines; the delete event still fires
with the retained identifier. Write the link down; a frozen mirror breaks no test.

### (d) Re-pointing moves the ownership forest — log it

O1 is "the child's root holds a single-valued member declared a replica of the owner", and it is the
gate on the *next* merge. Re-pointing a name to `AggrA` can therefore hand `AggrA` an O1 link it did
not have. If the re-pointed class is **single-valued** on its root, `AggrA` joins that child's rival
list; and if it also passes O2 or O3 with the same number of signals as the incumbent, a verdict that
was `OWNED` becomes `AMBIGUOUS` and the next iteration blocks a merge that was feasible.

Compute it and log it — do not avoid it. This is how the chain opens one step at a time.

In quizzes, after `course ← question`: `AnsweredQuiz` (`{Quiz, Question}` → `{Quiz, Course}`) is
`@OneToOne` on the `QuizAnswer` root, so `Course` joins `Quiz` as an O1 rival over `answer`. It passes
neither O2 nor O3, so `Quiz` still wins and only the rival list changes.

**Two single-valued classes may end up declaring the same root**, which is a second witness for an edge
rather than a new edge: after `execution ← quiz`, `TournamentQuiz` becomes `@Replica("Execution")`
beside `TournamentCourseExecution`, and `o1Member` returns whichever it reaches first. The verdict is
unchanged; the member named in the log is not, so log both witnesses rather than the one that happened
to win.

### (e) The other two annotations

All three are `RetentionPolicy.SOURCE`: dropping one compiles, passes the tests, and blinds every
measurement afterwards.

| Annotation | Rule in a merge |
|---|---|
| `@InterInvariant` | a rule that became intra leaves the registering method's value list and the root's `INTER-INVARIANTS` header. If the method is left with no rules it goes with its subscription (3.6) |
| `@ServiceGuard` | travels with the `@Transactional` method 3.1 moves. A guard that read `AggrB` over the network and now reads a member **is still a guard** — it does not quietly become an invariant |
| `@Replica` | (a)–(d) |

### (f) Verify

```bash
# no @Replica names AggrB any more, in any service
grep -rn "@Replica" src --include=*.java | grep "\"<AggrB>\""     # empty
# the control for the line above: if this is 0 too, the path is wrong, not the code
grep -rl "@Replica" src --include=*.java | wc -l                  # > 0
```

The closing sweep over the third services' declarations is layer 3.8's, because that is where the
inventory is walked.

## 1.8 — Verify

```bash
# no trace of AggrB as a root
grep -rn "Saga<AggrB>\|<AggrB>Repository\|<AggrB>Factory\|<AggrB>SagaState" src   # empty
# AggrB no longer extends Aggregate — piped, not globbed: `**` needs globstar, which is off by default,
# and an unexpanded glob makes grep fail with empty output that reads as a pass
grep -rn "class <AggrB>" src --include=<AggrB>.java | grep "/microservices/<svcA>/aggregate/"   # exactly 1 hit, without "extends Aggregate"
```

A check whose expected result is *empty* cannot tell "verified" from "the command matched no files".
Wherever this file and layer 2 expect empty, the positive count beside it is the control: if that one
also comes back 0, the path is wrong, not the code.

**Nothing was lost** — against Step 0.5's `errors-aggregate`. Must print nothing; each constant
printed was thrown by an aggregate before and by none now:

```bash
B="${TMPDIR:-/tmp}/refactor-baseline-<svcA>-<svcB>"; M=src/main/java/org/anonymous/msrefactor/quizzes/microservices
grep -rhoE '(QuizzesErrorMessage\.|QuizzesException\(\s*)[A-Z][A-Z0-9_]+' $M/<svcA>/aggregate --include=*.java \
  | grep -oE '[A-Z][A-Z0-9_]+$' | sort -u | comm -23 "$B/errors-aggregate" -
```

Restore the invariant, or name it in the log with the reason it legitimately went away. Service-level
guards are checked at layer 4.11, once 3.1 has moved them.

Compilation will still fail at this point — layers 2 and 3 have not run. That is expected; do not
attempt to fix service or coordination code from here.

---

## Log line

```
LAYER 1 (aggregate): <AggrB> demoted to member of <AggrA>; names resolved — svc <svcA>/<svcB>, root <AggrA>/<AggrB>, family <FamA>/<FamB>; entities moved: <n>; deleted <n> root classes; invariants — <n> member-scoped, <n> now intra-aggregate (<names>), <n> left as inter-invariants; counters collapsed: <names or none>; replicas — <n> deleted (<names>), <n> re-pointed to <AggrA> (<names>), <n> kept as snapshots (<name: reader>); O1 links added by re-pointing: <none | child <- <AggrA>, verdict <before> -> <after>>; annotations — @Replica <n>, @InterInvariant <n>, @ServiceGuard <n>
```
