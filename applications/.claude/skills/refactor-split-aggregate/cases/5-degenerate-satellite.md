# Case 5 — Degenerate satellite

P1 satisfied, P5 **not**: the entities partition cleanly, but what the cut produces is not an
aggregate worth deploying. Two shapes reach this verdict.

| Shape | What it means |
|---|---|
| **(a) the satellite is a pure replica set** | every entity in `--satellite` carries `@Replica`, **and** stripping `<x>AggregateId`, `<x>Version`, `state` and every field a synchronising event writes leaves nothing behind |
| **(b) the cut leaves a side with no member entity** | `<Aggr>` has a single composition unit, so the only possible satellite is that unit and the core keeps nothing but its scalars |

## Why this is not a refusal, and not a blocking one either

The cut is legal. It would compile, the tests would pass, and the result would be a deployment unit
whose entire state is a cache of another service's state: its own container, its own port, its own
database, its own event subscriber, and not one field anybody writes to it directly.

That thing already exists and is cheaper. A read-only copy of another aggregate's state, kept current
by an event, is what `CREATE_REPLICA` produces — with no service, no database and no route. And the
opposite move, collapsing such a copy back into its owner, is `REMOVE_REPLICA`. Proposing a split here
is proposing the most expensive way to get what one of the other two operations already gives.

So the verdict is `NO_CHANGE` with a **non-blocking** `REFACTOR_RESTRICTION` and a named alternative.
Non-blocking because the shape is a property of *today's* entity set: give the satellite one
authoritative field — one thing the domain writes there and nowhere else — and the same cut becomes
case 1 or 2.

## The trap this signal must dodge

**`@Replica` does not prove a lack of authority. Read the field list, not the annotation.**

Three live counter-examples, each a replica that owns real state:

| Class | Annotation | Field nobody else writes |
|---|---|---|
| `QuizQuestion` | `@Replica("Question")` | `sequence` — `invariantQuizQuestionSequenceConsecutive` enforces it and no event refreshes it |
| `QuestionAnswer` | `@Replica("Question")` | `optionSequenceChoice`, `timeTaken`, `optionKey`, `correct` |
| `TournamentParticipant` | `@Replica("User")` | `enrollTime`, plus a nested answer carrying its own counters |

Classifying any of those as a pure replica would refuse the three splits this application actually
admits. This is the same failure mode as deciding P2 from an invariant's name.

## Action

1. **Show the field list, not the annotation**, for every satellite entity:

   ```bash
   M=src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>
   grep -n "private " $M/aggregate/<SatelliteEntity>.java
   grep -rn "<SatelliteEntity>" $M/coordination/eventProcessing/    # which fields an event writes
   ```

   A field that appears in no event-processing path and in no `@Replica` mirror is authoritative, and
   one such field is enough to leave this case.

2. **Emit the `GATE:` lines**, naming the shape:

   ```
   GATE: P1 satisfied — core ∪ satellite covers all <n> entities of <Aggr>, disjointly
   GATE: P5 not satisfied — every field of <SatelliteEntity> is written by <Event> (<file:line>);
   after removing the replicated fields the satellite declares no state of its own
   GATE: <Aggr> partitions into <Core> + <Satellite> (1/5) — case 5
   ```

   For shape (b), the second line reads instead: `GATE: P5 not satisfied — <Aggr> has one composition
   unit (<Unit>), so the cut leaves <Core> with no member entity`.

3. **Emit the restriction, non-blocking, with the alternative named:**

   ```
   <!-- REFACTOR_RESTRICTION -->
   operation: SPLIT_AGGREGATE
   targetService: <svc>
   secondaryTarget:
   blocking: false
   reason: <one sentence saying that the satellite would hold only replicated state, and which operation gives the same result without a deployment unit>
   <!-- END_REFACTOR_RESTRICTION -->
   ```

   `blocking: false` is honoured by the pipeline: the candidate keeps its rank and carries `reason` as
   a warning badge instead of being vetoed. That is the right weight — the verdict is about today's
   fields, which a later refactoring can change.

4. **Name the cheaper operation** in the `Summary:`, as a proposal for Analyze rather than an action
   taken here: `remove-replica <svc> <SatelliteEntity> --source <owner>` when the copy is redundant,
   or the merge that removes the boundary the replica exists to cross.

5. Emit **no** `Topology:`, no `Impact:`, and change no file other than `CLAUDE.md`.

## Worked example — `topic`

`Topic` declares one member, `TopicCourse`, which is `@Replica("Course")` and carries exactly
`courseAggregateId` and `courseVersion`. Both shapes hold at once: the satellite would be a pure
replica, and the core would keep only `name`. `Topic.verifyInvariants()` and
`Topic.getEventSubscriptions()` are both empty, so there is not one rule to place on either side.

The alternative is not a smaller split. It is `remove-replica topic TopicCourse --source course`, or
the merge `course ← topic`, which the ownership map scores 3/3.

`execution` is the same verdict with two units instead of one: `CourseExecutionCourse` and
`CourseExecutionStudent` are both pure replicas, so any cut of it produces a cache with a database.

## Common failure, and how it is caught

1. **Reading the annotation instead of the fields**, refusing a real split. Caught by step 1: the
   evidence in the `GATE:` line is a field list, and a `GATE:` line with no field list is not evidence.
2. **Emitting `blocking: true`.** It would veto `SPLIT_AGGREGATE` on this aggregate permanently
   because of a property that one new field changes. The block above is the only shape allowed here.
3. **Splitting anyway "to show progress".** The result measures as a new service with a new database
   and no new behaviour, and Knowledge learns that splitting costs a deployment unit and buys nothing.
