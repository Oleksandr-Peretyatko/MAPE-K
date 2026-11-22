# Case 2 — Straddling invariants

P1, P5, P3 and P4 satisfied, P2 **not**: the satellite is authoritative, real and deployable, and the
entities partition cleanly — but one or more `invariant…()` methods read fields from both sides of the
cut.

The split still runs. What makes this a separate case is that it is **not a lossless refactoring**:
every straddling invariant that survives does so in a weaker form, and that has to be decided
deliberately and recorded, not buried in an action list.

## What actually changes about the rule

Before the split, a straddling invariant is checked synchronously inside one aggregate's
`verifyInvariants()`, in the same unit of work as the write. It **cannot** be violated.

After the split the two sides are separate aggregates with separate versions, so the same rule can
only be enforced on event delivery. Between the write on one side and the event reaching the other,
the rule is violated and nothing detects it. That window is the price of the split.

> A straddling invariant is not "moved". It is downgraded from a guarantee to a convergence
> property, and the aggregate on the receiving side must be able to *do something* when it finds the
> rule broken — compensate, reject, or mark itself inconsistent. An inter-invariant with no reaction
> is a comment.

## Action

1. **List every straddling invariant with the fields that straddle**, from the P2 evidence:

   ```
   GATE: P2 not satisfied — invariantParticipantsEnrolledBeforeStarTime reads p.getEnrollTime()
   (satellite) and this.startTime (core) at Tournament.java:230
   ```

   One `GATE:` line per straddling invariant. This list is what the architect reads to understand
   what the split cost.

2. **Classify each one** — they do not all become inter-invariants:

   | Shape | Resolution |
   |---|---|
   | reads a **scalar** the other side owns | copy the scalar to the reading side as a replicated field, kept current by an event. The invariant stays intra-aggregate and stays strong. **Prefer this whenever the scalar is stable** — but price it honestly: across a service boundary the copy is a replica with a real refresh path, i.e. a `CREATE_REPLICA` performed inside this split, with its own four event pieces |
   | reads a **collection** the other side owns | must become an inter-invariant; there is no cheap copy |
   | reads state that only exists during a transition | it belongs to the functionality, not the aggregate — move it into the saga step and say so |
   | checks **only** that both sides are mutually consistent | it becomes the reconciliation rule of the inter-invariant, not a separate check |

3. **Convert, do not delete.** For each invariant that becomes an inter-invariant, produce all four
   parts: the subscription in the reading aggregate's `getEventSubscriptions()`, the event, the
   handler, and the reaction when the check fails. Three of four is a rule that fires and does
   nothing.

4. **Count them** into `invariants converted to inter-aggregate: <n> (<names>)`. Case 2 with a count
   of 0 triggers the `WARNING:` in `## Mandatory log lines` — it means the rules were dropped.

5. Run the four layers (`SKILL.md`, *Mandatory read order*). Layer 1.4 places each invariant as
   classified above; layer 3.4 builds the event pieces step 3 requires; layer 4 creates the service
   that will host the receiving side.

6. **A replicated scalar with no refresh path is the failure this case creates.** The rule keeps
   compiling and keeps passing, against a value frozen at the moment of the split. After step 3,
   check that every scalar copied in this case has a subscription, a handler, a `@Scheduled` and a
   `process…Event` — the same quad as any other replica — and name it in the log.

## Worked example — `Tournament`, core/satellite as in the classifier

Cut: core = `{TournamentCreator, TournamentTopic, TournamentQuiz, TournamentCourseExecution}` +
scalars; satellite = `{TournamentParticipant, TournamentParticipantQuizAnswer}`.

| Straddling invariant | Reads | Resolution |
|---|---|---|
| `invariantParticipantsEnrolledBeforeStarTime` | `p.getEnrollTime()` + `this.startTime` | **copy the scalar** — `startTime` is set at creation and rarely changes; replicate it onto the satellite and the rule stays intra-aggregate and strong |
| `invariantAnswerBeforeStart` | `t.getParticipantAnswer()` + `this.startTime` | **copy the same scalar** — one replicated `startTime` resolves both invariants |
| `invariantDeleteWhenNoParticipants` | `getState()` + `getTournamentParticipants()` | **inter-invariant** — the core cannot see the satellite's collection; the core's deletion must be conditional on an event from the satellite reporting it is empty |
| `invariantCreatorParticipantConsistency` | `tournamentCreator` + `tournamentParticipants` | **inter-invariant** — both sides hold replicas of the same User, and the rule is precisely that they agree. It becomes the reconciliation rule between them |

Two of the four are resolved by replicating one scalar. That is the point of step 2: reaching for
`inter-invariant` on every straddle over-weakens the design, and the collection-reading ones are the
only ones that genuinely require it.

Note the second-order effect in the last row: after the split, `TournamentCreator` (core) and
`TournamentParticipant` (satellite) are replicas of User in *two different aggregates*. The split has
turned one intra-aggregate consistency check into a cross-aggregate one, and that is exactly what
`invariantCreatorParticipantConsistency` now has to enforce. Say so in the `Summary:`.

## Common failure, and how it is caught

1. **Every straddle turned into an inter-invariant.** Over-weakening. If a straddle reads a stable
   scalar, replicate it. Check the resolution column above before converting.
2. **The invariant deleted rather than converted.** The split compiles and the tests pass, because
   the tests assert behaviour that the invariant protected against rather than the invariant itself.
   `invariants converted to inter-aggregate: 0` in case 2 is the trigger for the mandated `WARNING:`.
3. **A converted rule with no reaction.** Grep the new handler for what it does when the check
   fails. If the answer is "logs", the rule is gone:
   ```bash
   grep -A5 "process.*Event" src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svc>/coordination/eventProcessing/*EventProcessing.java
   ```
4. **The replicated scalar has no refresh path.** Copying `startTime` to the satellite without
   subscribing to a core update event produces a rule enforced against a value that is correct once
   and wrong after the first edit. If you replicate, you also subscribe.
