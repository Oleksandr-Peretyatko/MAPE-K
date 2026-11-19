# Case 4 — Sibling merge (decompose into a chain)

Neither aggregate owns the other, but they share an owner somewhere up the tree. The pair **is**
mergeable — just not in one step.

`merge-aggregate question topic` is the canonical instance: Topic's owner is Course, Question's owner
is Course, so they are siblings.

## Why this is not a refusal

Ownership forms a forest (`../.claude/docs/concepts/aggregate-merge.md` §5, from the working directory). Two aggregates in the same
tree always have a lowest common ancestor, and merging them means merging *up to* that ancestor — a
sequence of ownership merges, each of which is a plain case 1.

There is nothing to invent: every step is `MERGE_AGGREGATES`, which is what keeps the whole path
traceable in a four-operation catalogue.

## Action

**Make no code changes.** Report `NO_CHANGE`, and emit the chain that would make the pair mergeable.

1. **Compute the chain.** Walk each aggregate's owner chain to its root using the single-valued parent
   links O1 already reads, intersect them, and take the deepest common entry — the lowest common
   ancestor. The chain is then one ownership merge per hop, deepest first:

   ```
   ownerChain(question) = [question, course]
   ownerChain(topic)    = [topic, course]
   LCA = course   ->   course<-topic, course<-question
   ```

   | pair | LCA | chain |
   |---|---|---|
   | question + topic | Course | `course<-topic`, `course<-question` |
   | tournament + answer | Execution | `quiz<-answer`, `execution<-quiz`, `execution<-tournament` |
   | quiz + question | Course | `execution<-quiz`, `course<-execution (ambiguous: 1/3)`, `course<-question` |

   Chains start with the aggregate itself and follow O1 alone (SKILL.md, *`ownerChain(X)`*). A hop the
   full O1–O3 rule would not confirm is emitted anyway, marked `(ambiguous: n/3)`: running it reaches
   the *Degenerate chain* verdict below, which shows the architect exactly where the chain stalls.

2. **Emit the verdicts**, naming the common owner and citing the parent link that proved it:

   ```
   GATE: neither <AggrA> nor <AggrB> owns the other — <AggrB>'s owner is <Owner>
         (<AggrB><Owner> at <file:line>), <AggrA>'s owner is <Owner> (<file:line>)
   GATE: MergeAggregates(<AggrA>, <AggrB>) reachable via <Owner> — <n>-step chain
   ```

3. **State the consequence the architect is accepting.** Merging siblings pulls in their common owner:

   > `question + topic` cannot produce an aggregate containing only Question and Topic. The only thing
   > binding a Question instance to a Topic instance is the Course they both belong to, so the merged
   > aggregate is Course. If that is unacceptable, the merge is not expressible — a property of the
   > domain, not a limit of the catalogue.

4. **Emit the prerequisite block**, after the `GATE:` lines, inside the CLAUDE.md entry:

   ```
   <!-- REFACTOR_PREREQUISITE -->
   operation: MERGE_AGGREGATES
   targetService: <svcA>
   secondaryTarget: <svcB>
   chain: course<-topic, course<-question
   reason: <one sentence naming the common owner>
   <!-- END_REFACTOR_PREREQUISITE -->
   ```

   The pipeline reads it back, records a **non-blocking** constraint — this pair needs a prerequisite,
   it is not permanently refused — and writes the chain into the candidate's `dependencies`, which the
   plan document and the Analyze UI already render.

5. Emit **no** `Topology:`, no `Impact:`, and no `LAYER n:` lines. Nothing was merged.

## Degenerate chain — the LCA is an operand

`course execution`: `ownerChain(execution) = [execution, course]` and `ownerChain(course) = [course]`,
so the LCA is `course` itself. The "chain" would be `course<-execution` — the same pair — and a
prerequisite that points at itself tells the architect nothing. The relation is **ambiguous
ownership**: an O1 link exists (`CourseExecutionCourse`) but the full rule fails (1/3). A genuine O1 tie
between candidate owners is the same verdict.

Make no code changes. Report `NO_CHANGE` and emit a **non-blocking restriction**, with no chain:

```
<!-- REFACTOR_RESTRICTION -->
operation: MERGE_AGGREGATES
targetService: <svcA>
secondaryTarget: <svcB>
blocking: false
reason: <AggrB> holds a single-valued link to <AggrA> but is <created | queried> independently of it (<n>/3 signals), so ownership is ambiguous
<!-- END_REFACTOR_RESTRICTION -->
```

`blocking: false` is honoured (`ExecuteService.persistStop`): the candidate keeps its rank and carries
`reason` as a warning badge instead of being vetoed as in case 5. That is the right weight — the
ambiguity is a fact about today's creation and query paths, which a later refactoring can change.

## Do not co-host as a consolation

Relocating one package into the other's tree while both keep their roots is a *service* merge. It
leaves two aggregate roots, contradicts the Completeness contract, and would be recorded as a
`MERGE_AGGREGATES` that never happened. The chain is the answer; a half-merge is not.

## After the first step

The chain is applied one operation per iteration, so each step is planned, executed and measured on
its own — which is the point. Once `course<-topic` has run, re-classifying `question + topic` finds
Topic already inside Course, and the remaining work is the plain case-1 merge `course<-question`.
