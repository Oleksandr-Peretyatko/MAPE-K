# Layer 4 — Deployment

Collapse the topology: `<svcB>` ceases to exist as a deployment unit. **Mandatory on every merge,
even when the plan document does not enumerate it.** Layers 1–3 leave a codebase that compiles and a
deployment that does not run: the Execute phase rediscovers the compose file and keeps deploying
both, and `<svcB>`'s container crashes on startup with `ClassNotFoundException` the moment
`<SvcB>ServiceApplication` is gone, so the deploy never becomes healthy.

`<svcA>` keeps its name, port, database, Spring profile and `spring.application.name`. **Nothing is
renamed.**

---

## 4.1 — `ServiceMapping`

Delete the `<AGGB>` constant and re-point all of its call sites to `ServiceMapping.<AGGA>` (18 call
sites for `COURSE`). This is the structural truth: there is no `<svcB>` service any more. External
senders then write straight to `<svcA>-command-channel`, and internal ones satisfy
`service.equals(appName)` and dispatch in-process.

> This reverses the older "never touch `ServiceMapping`" rule. That rule was correct only while
> `Saga<AggrB>` survived, which dissolution rules out — see *Invariants* below.

## 4.2 — Discover, do not assume

A previous merge may already have removed `<svcB>`, so treat every deletion as idempotent:

```bash
grep -n "^  <svcB>-service:\|^  <svcB>-db:" docker-compose.yml
```

## 4.3 — `docker-compose.yml`

**Delete** the `<svcB>-service` block and the `<svcB>-db` block in full. The `<svcA>-service` and
`<svcA>-db` blocks are **left untouched** — same port, same container name, same datasource.
`<svcB>`'s port is freed.

## 4.4 — `pom.xml`

**Delete** the `<svcB>-service` `<profile>`. The `<svcA>-service` profile is left untouched.

## 4.5 — `application-<svcA>-service.yaml`

**Adopt `<svcB>`'s command channel.** Append `<svcB>ServiceCommandChannel` to
`spring.cloud.function.definition`:

```yaml
definition: commandResponseChannel;<svcA>ServiceCommandChannel;<svcB>ServiceCommandChannel;<svcA>EventSubscriber;
```

**This is a safety net, and is applied anyway.** Nothing should address `<svcB>` once 4.1 removed the
constant and layer 1 deleted `Saga<AggrB>` — but it costs one entry, and it converts "a sender
escaped the layer 3.8 sweep" from a 10-second gateway timeout into a correctly served command. Keep
it. No binding needs declaring here: the shared `application.yaml` already maps
`<svcB>ServiceCommandChannel-in-0 → <svcB>-command-channel`, and 4.7 keeps it.

Do **not** add `<svcB>EventSubscriber` — exactly one event subscriber per deployment unit (see
*Invariants*). `<svcA>`'s subscriber already serves the merged aggregate, because layer 1 moved
`AggrB`'s entities into a package `<svcA>` scans.

**Merge the `routes:` block — this is a behaviour requirement, not housekeeping.** Layer 2 retained
`AggrB`'s identifier precisely so that `/`-paths like `/topics/{topicAggregateId}` keep resolving; if
`<svcB>`'s Path predicates are dropped, those paths 404 and the merge has changed externally visible
behaviour. Copy every predicate from `application-<svcB>-service.yaml` into `<svcA>`'s `routes:`,
with **distinct ids** — all eight files use `id: ${spring.application.name}-service`, which would
collapse into one id and silently drop a route (composition doc §1). Give the adopted block
`id: ${spring.application.name}-service-<svcB>`.

If `<svcA>` has **no** `routes:` block at all (`course` does not), create one — otherwise `<svcB>`'s
paths become unreachable.

`server.port`, `grpc.server.port`, `spring.application.name` and `datasource.url` are unchanged.

## 4.6 — Delete `application-<svcB>-service.yaml`

## 4.7 — `application.yaml` (the shared file)

Remove **only** `<svcB>`'s event subscriber: its `<svcB>EventSubscriber-in-0` binding and its entry
in `quizzes.function-definition.events`.

**Keep `<svcB>`'s command-channel entries** — the `<svcB>ServiceCommandChannel` entry in
`spring.cloud.function.definition`, the `<svcB>-command-channel` producer binding, and the
`<svcB>ServiceCommandChannel-in-0` consumer binding. 4.5 relies on them.

The service lists in this file are **not in a consistent order** across its five sites, and they live
in different `on-profile` documents (`remote` holds the event lists, `stream` the command lists).
Match by name, never by position.

## 4.8 — Application class

**Confirm `<SvcB>ServiceApplication.java` is gone** — layer 1.4 deletes it with the rest of
`<svcB>`'s package. Verify here, because its profile and compose block are now gone and a surviving
class would make static analysis keep reporting a service boundary that no longer exists:

```bash
ls src/main/java/org/anonymous/msrefactor/quizzes/microservices/<svcB>/*ServiceApplication.java   # empty
find src/main/java -name "*ServiceApplication.java" | wc -l                                               # pre - 1
```

**The name comes from `<SvcB>`, not from `<AggrB>`** — `answer`'s is `AnswerServiceApplication.java`
while its root is `QuizAnswer`. The first line's expected result is empty, so a wrong name reads as a
pass; the count beside it is what makes the check real, and it is the same figure 4.11 reads seven ways.

**Do NOT rename `<SvcA>ServiceApplication`'s `@Profile`, and do NOT edit anything under
`simulator/`.** `scanBasePackages` on `<SvcA>ServiceApplication` already covers the package layer 1
moved `AggrB` into, so both entity sets and the merged command handler land in one context with no
annotation change — that scan root is the entire mechanism by which one process owns both entity sets
(composition doc §2.1).

## 4.9 — Database

`<svcB>db` is destroyed with its container; `<svcA>db` survives untouched. `AggrB`'s tables are
created in `<svcA>db` at startup because its entity classes now sit under `<svcA>`'s `@EntityScan`
root.

**No data is migrated.** Aggregate ids are unique per database, not globally (composition doc §2.4),
so rows from the two databases cannot be reconciled. This is acceptable here only because every
service runs `spring.jpa.hibernate.ddl-auto: create` and the Monitor phase regenerates state with a
fresh load test — it would not be acceptable in production. **State this in the CLAUDE.md log.**

## 4.10 — Tests

`BeanConfigurationSagas.groovy` declares its `@Bean` methods grouped **by kind, not by aggregate** —
Functionalities, EventProcessing, CustomRepository, Factory, Service, EventHandling and
CommandHandler are each their own region of the file. Removing `<AggrB>` means editing **4–6 separate
places** plus the imports at the top; there is no single block to delete.

Drop the beans whose classes layers 1–3 deleted (`Sagas<AggrB>Factory`,
`<AggrB>CustomRepositorySagas`, `<AggrB>Service`, `<SvcB>CommandHandler`,
`<AggrB>Functionalities`), and update `<AggrA>Service`'s bean method to match its new constructor
signature from 3.1.

Re-point the `.groovy` tests that import `microservices.<svcB>.`. Skipping this breaks test
compilation, not just the tests.

**A replica 1.7 deleted changes arities the tests wrote out by hand.** A test that built the aggregate
directly — `new Topic(id, name, new TopicCourse(courseDto))`, or a factory call carrying the copy — has
to lose the argument, and so does any assertion reading the copy. Where a merge finds none, say
`no .groovy constructs the deleted replica` in the log: verified absence, not assumed.

Then run the sagas profile:

```bash
test-gate --project-path <project-path> --profile <profile>   # no JDK here — the exact command is in your prompt
```

See `applications/CLAUDE.md` § "Final step — test gate": run it last, fix what it reports
(at most 3 cycles), and report the result you actually saw.

The pipeline re-runs this profile as a hard gate right after the skill (`ExecuteService` step 5a) and
on red **discards the entire branch** — a red run is lost work, not a partial success. Fix failures
here, where you can still see them: after a merge they are almost always a `@Bean` method for a class
layers 1–3 deleted, a `<AggrA>Service` bean whose arguments no longer match the constructor from 3.1,
or a `.groovy` import still naming `microservices.<svcB>.`. If it is still red after fixing what you
can, report `Result: CHANGED` with `WARNING: sagas test profile red — <n> failure(s): <test names>`
so the log explains the discarded version. Never report it green unless you saw it green.

## 4.11 — Verify

```bash
docker compose -f docker-compose.yml config -q                          # must exit 0
grep -rn "<svcB>-service\|<svcB>-db\|<svcB>db" .                        # must be empty
grep -rn "microservices\.<svcB>\." src                                  # must be empty
grep -rn "commands\.<svcB>\." src                                       # must be empty
mvn -f pom.xml -P<svcA>-service,sagas,stream help:active-profiles
```

**The check that proves the flow actually collapsed** — a stack that merely starts is not enough.
During a functionality that used to cross the boundary, in `<svcA>-service`'s log:

```bash
docker logs <svcA>-service | grep -c "to <svcB>-command-channel"    # 0
docker logs <svcA>-service | grep -c "Dispatching command locally"  # > 0
```

Any `Sending command … to <svcB>-command-channel` originating in `<svcA>-service` means layer 3 was
not applied and the merge degenerated into a `MergeServices`. Scope the count to `<svcA>-service`'s
own log: other services may still legitimately publish to that channel if the 3.8 sweep missed one,
and a busy queue on its own would not tell you who filled it.

**And that nothing is left unconsumed:**

```bash
docker exec rabbitmq rabbitmqctl list_queues name messages consumers | grep "<svcB>-command-channel"
```

The queue should be idle. Messages sitting in it with zero consumers mean a sender escaped the sweep
and 4.5's safety net was skipped, so those commits will hang.

**And that the retained routes answer** — the paths layer 2 preserved identifiers for:

```bash
curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/<svcB-path>/<known-id>   # not 404
```

**And that nothing was lost** — against Step 0.5's baseline. Both must print nothing:

```bash
B="${TMPDIR:-/tmp}/refactor-baseline-<svcA>-<svcB>"; M=src/main/java/org/anonymous/msrefactor/quizzes/microservices
grep -rhoE '(QuizzesErrorMessage\.|QuizzesException\(\s*)[A-Z][A-Z0-9_]+' $M/<svcA> --include=*.java \
  | grep -oE '[A-Z][A-Z0-9_]+$' | sort -u | comm -23 "$B/errors" -
grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping\([^)]*\)' $M/<svcA> --include=*.java | sort -u | comm -23 "$B/routes" -
```

A route printed here no longer answers — an externally visible behaviour change. An error printed here
is no longer raised. Fix either, or name it in the log with its reason; never report `CHANGED` over a
silent one.

---

## Invariants

Non-obvious constraints; violating any of them produces a stack that builds but does not run.

- **Only files under `<projectPath>/` may be modified.** The pipeline stages
  `git add -A -- <projectPath>/` — a pathspec evaluated from the repository root, which is why this
  one keeps the prefix. Edits anywhere else are silently discarded and then wiped by the branch
  cleanup.
- **`ServiceMapping` is not the binding router**, so editing it is never sufficient on its own. The
  saga commit protocol addresses services via `resolveServiceName`, which derives the name from
  the aggregate class's simple name and never consults `ServiceMapping`. 4.1 fixes *domain command*
  routing **under dissolution only**; what makes it safe is that layer 1 deleted `Saga<AggrB>`, so
  `resolveServiceName` stops producing `<svcB>` too. Those two edits are a pair — re-pointing
  `ServiceMapping` while `Saga<AggrB>` still existed would leave the commit protocol addressing a
  service that no longer runs.
- **The merged command handler bean must be named `<svcA>CommandHandler`** — otherwise every command
  takes the broker path instead of in-process dispatch.
- **Exactly one `*EventSubscriber` per deployment unit.** `EventSubscriberRegistrar` registers every
  subscriber as the same delegate, so two sharing a consumer group on `event-channel` compete and
  each receives only half the events.
- **Every `routes:` entry needs a unique `id`** — duplicates collapse in the gateway registry.
- **The surviving service keeps its own ports**; `<svcB>`'s port is freed.
- **Only `<svcB>db` is destroyed.** `<svcA>db` survives untouched — nothing is renamed.
- **The gateway needs no edit.** Routing is pull-based (each service serves its own `/routes`, polled
  every 15s) and `<svcA>`'s Eureka app id is unchanged, so nothing to heal.
- **Nothing is renamed.** `spring.application.name` stays `<svcA>`, so commands that were dispatched
  in-process still are. A combined `<svcA>+<svcB>` name would break `service.equals(appName)` and add
  a RabbitMQ round-trip to the same process.

---

## Log line

```
LAYER 4 (deployment): <svcB>-service and <svcB>-db removed; <n> route predicate(s) adopted into <svcA> as id '<id>'; <svcB>db destroyed without migration; <n> test bean(s) dropped across <n> region(s); sagas test profile <green | red — n failure(s)>
```
