# Invariant Shapes and Mutations

> **What this is for.** `rule-enforcement-patterns.md` answers *«which of the four patterns should
> this rule use?»* and gives the implementation recipe for each. This document answers two questions
> it does not cover:
>
> 1. **What shape does the rule have?** — the recurring structural forms an invariant takes in this
>    codebase, extracted from every invariant that exists today.
> 2. **What happens to it when the aggregate boundary moves?** — the conversion rules between
>    patterns, which is what `SPLIT_AGGREGATE` and `MERGE_AGGREGATES` actually do to invariants.
>
> Read `rule-enforcement-patterns.md` for P1…P4 semantics and code recipes. This document assumes
> that vocabulary and does not repeat it.

---

## 1. What exists today

The inventory below is the evidence everything else in this document is derived from. It is worth
reading before trusting any generalisation.

### 1.1 Named intra-invariants (P1) — 16

| Aggregate | Invariants |
|---|---|
| `Tournament` | `StartTimeBeforeEndTime`, `UniqueParticipant`, `ParticipantsEnrolledBeforeStarTime`, `AnswerBeforeStart`, `MustHaveOneTopic`, `NumberOfQuestionsPositive`, `NumberOfQuestionsAtMost30`, `DeleteWhenNoParticipants`, `CreatorParticipantConsistency`, `CreatorIsNotAnonymous`, `FinalAfterStart`, `CancelledFieldsAreFinal` |
| `Quiz` | `DateOrdering`, `FieldsFinalAfterAvailableDate`, `InClassRequiresConclusionDate`, `QuizQuestionSequenceConsecutive` |

### 1.2 Intra-invariants that do **not** follow the naming convention

**The `invariant*` prefix is a convention of two aggregates, not a rule of the codebase.** Four more
aggregates enforce rules inside `verifyInvariants()` without it:

| Aggregate | How it is written | Consequence |
|---|---|---|
| `Execution` | delegates to `removedNoStudents()` | not matched by any `invariant*` scan |
| `User` | delegates to `deletedState()` | idem |
| `Course` | inline `if` on `courseExecutionCount` / `courseQuestionCount` | no method to name at all |
| `Question` | inline `for` loop over `questionTopics` | idem |
| `QuizAnswer`, `Topic` | `verifyInvariants()` is empty | nothing to find |

> **Gate for any tool that inventories invariants.** Scanning for `\binvariant\w+\s*\(` finds 16 of
> them and misses at least four real rules. Read the **body of `verifyInvariants()`** and follow what
> it calls; the method is the choke point, the naming is not.

### 1.3 Inter-invariants (P2) — 13 declarations, 9 distinct names

Declared as `private void interInvariant«Name»(Set<EventSubscription>)` and assembled in
`getEventSubscriptions()`. Three names recur across aggregates —
`interInvariantCourseExecutionExists` appears in `Tournament`, `QuizAnswer` and `Quiz`; `TopicsExist`
and `QuizExists` in two each — so a deduplicated count gives 9 and an incorrect picture. **The unit
is the declaration, not the name:** each one guards a different aggregate's replica and carries its
own subscription set.

| Aggregate | Inter-invariants |
|---|---|
| `Tournament` | `CourseExecutionExists`, `CreatorExists`, `ParticipantExists`, `QuizAnswersExist`, `TopicsExist`, `QuizExists` |
| `QuizAnswer` | `CourseExecutionExists`, `QuizExists`, `StudentExists` |
| `Quiz` | `CourseExecutionExists`, `QuestionsExist` |
| `Execution` | `UsersExist` |
| `Question` | `TopicsExist` |

### 1.4 Service guards (P3)

Not named, so they must be recognised by shape. Both documented sub-cases occur:

| Sub-case | Real instance |
|---|---|
| own-table uniqueness | `quizAnswerRepository.existsByQuizIdAndStudentId(...)` before creating a `QuizAnswer` |
| saga-assembled DTO validation | `if (!courseExecutionAggregateId.equals(quizDto.getCourseExecutionAggregateId()))`; `if (!courseExecutionStudent.isActive())` |

---

## 2. The shape catalogue

Every P1 in §1.1 falls into one of eleven shapes. The shape is what determines **where the rule can
live after a boundary moves**, so it is the classification that matters for mutation.

Notation: `«Root»` is the aggregate root, `«M»` a member entity, `«C»` a collection of members.

| # | Shape | Reads | Example |
|---|---|---|---|
| **S1** | Root scalar comparison | root scalars only | `StartTimeBeforeEndTime`, `DateOrdering` |
| **S2** | Root scalar domain constraint | root scalars only | `NumberOfQuestionsPositive`, `NumberOfQuestionsAtMost30` |
| **S3** | Conditional root field | root scalars only | `InClassRequiresConclusionDate` |
| **S4** | Collection cardinality | one `«C»` | `MustHaveOneTopic` |
| **S5** | Collection uniqueness | one `«C»` | `UniqueParticipant` |
| **S6** | Universal quantification | one `«C»` **+ often a root scalar** | `ParticipantsEnrolledBeforeStarTime`, `QuizQuestionSequenceConsecutive` |
| **S7** | Deep traversal | `«C»` → member of member | `AnswerBeforeStart` |
| **S8** | Cross-member consistency | two `«M»`/`«C»` | `CreatorParticipantConsistency` |
| **S9** | Temporal immutability | root scalars + `«C»` + **`getPrev()`** | `FinalAfterStart`, `CancelledFieldsAreFinal`, `FieldsFinalAfterAvailableDate` |
| **S10** | State-conditional | root `state` + `«C»` | `DeleteWhenNoParticipants` |
| **S11** | Member value constraint | one `«M»` | `CreatorIsNotAnonymous` |

### 2.1 The two shapes that need special handling

**S6 is the trap.** It looks like a collection rule, so it looks like it moves with the collection —
but it usually also reads a root scalar, and the root never moves. `ParticipantsEnrolledBeforeStarTime`
reads `p.getEnrollTime()` **and** `this.startTime`. Always list the root scalars separately from the
members; that column is what decides whether the rule straddles a cut.

**S9 cannot span a boundary at all.** `getPrev()` returns the previous version of **this** aggregate.
After a split, each side has its own version chain, so a single S9 invariant cannot compare fields
that now live on two different chains. It is not expensive to split — it is **impossible**. The only
resolution is to duplicate it: each side gets an S9 guarding its own fields.

S9 is also the most copy-pasted shape in the codebase — `Tournament.FinalAfterStart` and
`Quiz.FieldsFinalAfterAvailableDate` are structurally identical. Its skeleton:

```java
private boolean invariant«Name»() {
    «Root» prev = («Root») getPrev();
    if (prev != null && prev.get«Trigger»() != null && this.lastModifiedTime != null
            && this.lastModifiedTime.isAfter(prev.get«Trigger»())) {
        return Objects.equals(this.«scalarA», prev.get«ScalarA»())
            && …
            && this.«C».stream().map(«M»::get«Id»).collect(toSet())
                   .equals(prev.get«C»().stream().map(«M»::get«Id»).collect(toSet()));
    }
    return true;
}
```

The `lastModifiedTime` field exists precisely so this shape can be checked post-hoc inside
`verifyInvariants()` without calling `DateHandler.now()` there.

---

## 3. Anatomy of an inter-invariant

A P2 has no single method that evaluates it. It is three parts in three places:

| Part | Where | What it declares |
|---|---|---|
| **Subscription** | `interInvariant«Name»(Set<EventSubscription>)`, assembled by `getEventSubscriptions()` | which events could break the rule |
| **Anchor** | the `super(...)` call of the subscription class | *whose* changes to listen for: `(publisherAggregateId, publisherVersion)` — always the **publishing** aggregate |
| **Reaction** | `«Agg»EventProcessing.process«Event»Event` → functionality → service | what to do when it does break |

Cardinality follows the replica it guards:

```java
// single-valued member → one subscription per event type
private void interInvariantCourseExecutionExists(Set<EventSubscription> subs) {
    subs.add(new QuizSubscribesDeleteCourseExecution(this.getQuizCourseExecution()));
}

// collection → one subscription per element, per event type
private void interInvariantQuestionsExist(Set<EventSubscription> subs) {
    for (QuizQuestion qq : this.quizQuestions) {
        subs.add(new QuizSubscribesUpdateQuestion(qq));
        subs.add(new QuizSubscribesDeleteQuestion(qq));
    }
}
```

> **A P2 with three of its four parts is a rule that fires and does nothing.** See
> [`events.md`](events.md) for the delivery chain — the `@Scheduled` poller, the subscription match,
> and the version clock that stops an event from being reprocessed.

---

## 4. Creating an invariant

### 4.1 Decision procedure

Answer in order; the first match wins.

```
1. Can the command be built so it fails naturally when the rule is violated?
   (e.g. fetch-by-compound-key that throws when absent)
      → P4. Write no check. Record why.

2. Does the rule read only state inside one aggregate?
      → P1. Continue to §4.2 for the shape.

3. Does it read another aggregate?
   3a. Is the authoritative data in this aggregate's own table?
         → P3, own-table read before mutation.
   3b. Must enforcement be synchronous and strong?
         → P3, with a saga step assembling the other side as a DTO first.
   3c. Otherwise
         → P2. Build all three parts of §3.
```

### 4.2 Writing a P1

1. **Name the reads before writing the body.** Produce two lists: root scalars, and member
   entities/collections. This is the input to every mutation decision later, and the reason S6 is
   misclassified so often.
2. **Pick the shape** from §2 and follow its skeleton.
3. **Add the call to `verifyInvariants()`.** A method named `invariant«Name»` that nothing calls is
   dead code that reads as a guarantee.
4. **Decide the state scope.** Most rules apply only to `ACTIVE`; deletion rules apply to all states.
   The established layout:

```java
public void verifyInvariants() {
    if (!invariant«DeletionRule»()) {                       // applies to all states
        throw new «App»Exception(INVARIANT_BREAK, getAggregateId());
    }
    if (getState() == AggregateState.ACTIVE) {
        if (!(invariant«A»() && invariant«B»() && …)) {
            throw new «App»Exception(INVARIANT_BREAK, getAggregateId());
        }
    }
}
```

5. **Document the rule in a comment above the method**, in the formal notation used by its
   neighbours. **Do not trust those comments as the source of truth** — see §6.

---

## 5. Mutations

This is what a boundary-moving refactoring does to a rule. Each row is a sub-operation the enclosing
operation has to perform.

### 5.1 `SPLIT_AGGREGATE` — a rule that straddles the cut

Let *S* be the satellite entity set. A P1 **straddles** when it reads something in *S* **and**
something outside *S* — where «outside» includes every root scalar, because the root stays with the
core.

| Reads across the cut | Target pattern | Build | Guarantee after |
|---|---|---|---|
| a **stable scalar** the other side owns | **stays P1** | replicate that scalar onto the reading side + the event that keeps it current | strong for the reader's own writes; eventual for the owner's |
| a **collection** the other side owns | **P2** | the three parts of §3 | eventual |
| only that both sides agree (S8) | **P2 reconciliation** | becomes the inter-invariant's repair rule, not a separate check | eventual |
| state that exists only during a transition | **moves to the saga step** | a step in the functionality; the rule leaves the aggregate | strong |
| anything, and synchronous strength is required | **P3** | a saga step per violating operation + the check | strong, but only on enumerated operations |
| **S9 (`getPrev()`)** | **duplicate** | one S9 on each side, each guarding its own fields | strong on both sides |

**Prefer the first row whenever the scalar is stable.** One replicated scalar can resolve several
straddling invariants at once — on `Tournament`, replicating `startTime` resolves both
`ParticipantsEnrolledBeforeStarTime` and `AnswerBeforeStart`.

**The P3 row hides a cost.** `verifyInvariants()` is a single choke point that runs on every commit.
A P3 guard runs only where it is placed, so converting P1 → P3 means enumerating **every** operation
that can violate the rule — often in both directions. For `ParticipantsEnrolledBeforeStarTime` that
is *add a participant* (satellite, needs the core's scalar) **and** *change the start time* (core,
needs the satellite's collection). The second one reads a collection, which is the expensive row of
this same table. Converting to P3 is a change of guarantee, from *always* to *wherever I remembered*.

### 5.2 `MERGE_AGGREGATES` — a rule that no longer crosses anything

| Situation | Target | Build | Guarantee after |
|---|---|---|---|
| both sides of the P2 land in the merged aggregate | **P1** | move the check into `verifyInvariants()`; delete subscription, event handler and reaction | eventual → **strong** |
| one side is a third aggregate | **stays P2** | re-point the subscription's anchor to the merged aggregate | unchanged |
| the replicated field is now local state | field deleted | remove the replica class and its sync chain (`REMOVE_REPLICA`) | — |

The first row is the only mutation in this document that strictly **strengthens** a guarantee. It is
also the one most often left half-done: deleting the subscription but keeping the replica, or
converting the check but leaving the event publisher alive.

### 5.3 Invariants that do not move

| Shape | Why |
|---|---|
| S1, S2, S3 | read root scalars only; the root does not move |
| S4, S5, S11 | read exactly one member or collection; they follow it, unchanged |

A cut whose straddling set is empty requires **no invariant work at all** — every rule either stays
or is relocated verbatim. This is the cheapest possible split and the one to look for first.

---

## 6. Traps

Ordered by how quietly they fail.

| Trap | What happens | How to avoid it |
|---|---|---|
| **Trusting the header comment** | `CancelledFieldsAreFinal`'s comment lists `tournamentTopics`; the body also reads `tournamentParticipants`. A partition built from the comment misclassifies it. | Read the body. Always. |
| **Scanning for `invariant*`** | Misses `removedNoStudents()`, `deletedState()` and every inline check (§1.2). | Start from `verifyInvariants()` and follow the calls. |
| **Matching invariants to entities by name** | `invariantUniqueParticipant` does not contain the string `TournamentParticipant`. Name-substring matching finds **zero** of the real relationships. | Parse the body: field names via the field→type map, plus type names from method references like `«M»::get«X»`. |
| **Forgetting the root scalar in S6** | The rule looks like it moves with its collection; it straddles. | Keep root scalars in a separate list from members. |
| **Splitting an S9** | Impossible, not expensive: `getPrev()` is per-aggregate. | Duplicate it, one per side. |
| **A P2 with three parts** | Compiles, subscribes, and never repairs anything. | Subscription, anchor, **and** reaction — or it is not a rule. |
| **Writing `invariant«X»()` without calling it** | Reads as a guarantee, enforces nothing. | It must appear inside `verifyInvariants()`. |

---

## 7. Machine-readable summary

For a skill that needs the classification without the prose.

```yaml
enforcement_patterns: [P1_intra, P2_inter, P3_guard, P4_by_construction]

shapes:
  S1_root_scalar_comparison:   {reads: [root], moves: with_root}
  S2_root_scalar_domain:       {reads: [root], moves: with_root}
  S3_conditional_root_field:   {reads: [root], moves: with_root}
  S4_collection_cardinality:   {reads: [collection], moves: with_collection}
  S5_collection_uniqueness:    {reads: [collection], moves: with_collection}
  S6_universal_quantification: {reads: [collection, root?], moves: with_collection_if_no_root_read}
  S7_deep_traversal:           {reads: [collection, nested_member], moves: only_if_traversal_intact}
  S8_cross_member_consistency: {reads: [member, member], moves: only_if_both_move}
  S9_temporal_immutability:    {reads: [root, collection, prev], moves: never_duplicate_instead}
  S10_state_conditional:       {reads: [root_state, collection], moves: straddles}
  S11_member_value_constraint: {reads: [member], moves: with_member}

straddles(S, invariant) := invariant.reads ∩ S ≠ ∅
                        ∧ (invariant.reads \ S ≠ ∅ ∨ invariant.reads_root_scalar)

split_resolution:
  stable_scalar_across_cut: replicate_scalar     # stays P1 — prefer
  collection_across_cut:    to_P2
  mutual_consistency:       to_P2_reconciliation
  transition_state:         to_saga_step
  needs_synchronous:        to_P3                # cost = number of violating operations
  shape_S9:                 duplicate_per_side

merge_resolution:
  both_sides_merged: to_P1                       # strengthens
  third_aggregate:   stays_P2_repoint_anchor
```
