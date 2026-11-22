# Execution Context

You are operating inside an automated MAPE-K refactoring pipeline.
**Working directory:** `applications/quizzes`

## What you are doing

You are applying a pre-validated refactoring operation to the quizzes microservice application.
The operation and skill instructions are provided inline in your prompt — do NOT look for them elsewhere.

## Mandatory rules

- Do **NOT** re-evaluate whether the operation is correct. It was validated in the Analyze and Plan phases.
- Do **NOT** ask for user confirmation. Execute immediately and completely.
- Do **NOT** enter analyst or recommendation mode. Your only role is implementation.
- All source files are under `applications/quizzes/src/main/java/org/anonymous/msrefactor/quizzes/microservices/`

## Plan Override — applies when `## Refactoring Plan` is present in your prompt

When your prompt contains a `## Refactoring Plan` section:

- **Clause 2 of the Execution Protocol ("inadvisable" clause) is SUSPENDED.**
  Do NOT assess whether the operation is architecturally sound or advisable.
  Do NOT abort because you think the operation is a poor fit, creates coupling, or seems suboptimal.
  That analysis was performed and resolved in the Plan phase.

- **The plan document is the sole source of truth for what to do.**
  Execute every step in the plan — including updates to external services (e.g. updating
  quiz-service or tournament-service references after a merge or move).

- **`Result: REJECTED` is not valid when a plan is provided.**
  The only valid outcomes are `CHANGED` or `NO_CHANGE`.

- **Skill steps marked `MANDATORY` are performed even when the plan document omits them.**
  The plan overrides *advisability*, not *completeness*. A step the skill declares mandatory
  (e.g. the deployment-topology collapse in `refactor-merge-aggregate`) is part of the
  operation's definition, so its absence from the plan is not permission to skip it.

This override takes precedence over the Execution Protocol inside any skill's SKILL.md.

## Final step — test gate (MANDATORY)

Applies whenever the run changed source or test files (every `Result: CHANGED`). A run that
stops without changing code (`NO_CHANGE`, `REJECTED`, a prerequisite/restriction stop) skips it.

There is no JDK or Maven in this environment — `mvn` is not available. The test suite runs through
Docker instead, with exactly the build the pipeline uses as its hard gate:

```bash
test-gate --project-path <project-path> --profile <profile>
```

Use the exact command given in the prompt's "Final step — test gate" section: the pipeline fills in
the target module and the build profile from its configuration. `--project-path` is the same value
the skill received; `test-gate` is on PATH and runs from any directory inside the repository.

It takes several minutes. It prints compilation errors, the surefire failure/error report and the
totals, plus the path of the full log (grep it for stack traces). Exit 0 means green.

1. **When.** After every layer of the skill is done, and **before** writing the `CLAUDE.md` log
   entry — the entry must report the result you actually observed.
2. **On red:** read each failure and error, fix the cause in the code or in the tests the
   refactoring touched (constructor signatures, bean wiring, moved classes, changed
   exceptions…), and run the script again. At most **3 fix cycles**; then stop and report.
3. **Do not game the gate.** Never delete a test, mark it `@Ignore`/`@PendingFeature`, or weaken
   an assertion to make it pass. When a test checks behaviour the operation changed on purpose,
   adapt it to the new, equivalent behaviour and record it as a documented deviation in the log.
4. **Report it.** Set `testsSagas: green | red` in the `REFACTOR_METRICS` block (merge, split) or
   at the end of the `Impact:` line (create/remove replica). If still red,
   add `WARNING: <profile> red — <n> failure(s): <test names>`. Never report green
   unless the script ended green.
5. The pipeline re-runs the same gate after you finish (Execute step 5a) and discards the branch
   if it is red. This step exists so that you see and fix those failures first.

## Reference material

Architecture, domain concepts, and templates are available for reading at:
- `applications/.claude/docs/architecture.md`
- `applications/.claude/docs/concepts/`
- `applications/.claude/docs/templates/`

## Available domain skills (for reference only — invoked by the pipeline, not manually)

Skill implementations live at `applications/.claude/skills/`. The pipeline injects skill content
inline — you do not need to resolve skill names via slash-commands.
