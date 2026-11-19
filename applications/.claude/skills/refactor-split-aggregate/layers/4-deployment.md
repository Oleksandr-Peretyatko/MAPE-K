# Layer 4 — Deployment

Create the topology: `<sat>-service` comes into existence. **Mandatory on every split, even when the
plan document does not enumerate it.** Layers 1–3 leave a codebase that compiles and a deployment
that does not run: the satellite's commits resolve to a service name nobody declared, its commands go
to a channel nobody consumes, and its rows have no database to land in.

`<svc>` keeps its name, port, database, Spring profile and `spring.application.name`. **Nothing is
renamed** — the core is still `<Aggr>` in `<svc>-service`, and only what the satellite needs is added.

This layer is the mirror of `refactor-merge-aggregate/layers/4-deployment.md`, subsection by
subsection: where the merge deletes, the split creates.

---

## 4.1 — `ServiceMapping`

Add the constant:

```java
<SAT>("<sat>"),
```

The value is the **camel** form, `lowerFirst(<Satellite>)` — the same string as
`spring.application.name` (4.5) and as `resolveServiceName(Saga<Satellite>)`. Every command layer 2.2
and 3.2 created for the satellite carries it (layer 3.1(a)).

> This and 4.3 are a **pair**. A constant without a compose block sends commands to a channel nobody
> consumes; a compose block without the constant starts a service nothing addresses. Apply both or
> neither.

## 4.2 — Discover, do not assume

A previous run, or a hand-written experiment, may already have added part of this. Treat every
creation as idempotent, and check the port before claiming it:

```bash
grep -n "^  <sat-infra>-service:\|^  <sat-infra>-db:" docker-compose.yml
# host ports taken — both files: docker-compose.yml `include:`s the root one (gateway, eureka, rabbitmq, jaeger…)
grep -hoE '"[0-9]+(-[0-9]+)?:[0-9]' docker-compose.yml ../../docker-compose.yml | grep -oE '[0-9]+(-[0-9]+)?' | sort -nu
grep -rn "port:" src/main/resources/application*.yaml | sort -u
```

> `8080` appears three times and is not a collision: `gateway` (root file) and `quizzes-local` /
> `quizzes-remote`, the monolith variants selected by profile — never run together. Exclude it from
> the duplicate check.

**Reserved host ports.** A port can be free in both compose files and still be taken: other compose
projects publish on the same Docker host, and `compose up` fails on them only at deployment, long after
every test has passed. Never allocate:

- **8090 and 8100**, always — the refactor tool's own `rt-app` and `locust-service`
  (`refactor-tool/docker-compose.yml`) publish them;
- any port listed under `## Reserved host ports` in your prompt — the pipeline lists there every host
  port held by a container outside this application's compose project.

**Port allocation.** Take the lowest `server.port` ≥ 8090 that is neither published by either compose
file nor reserved, and `grpc.server.port = port + 1010`, the offset every existing service uses
(8082/9092 … 8089/9099); the grpc port must not appear in any `application*.yaml` either. **8081 is not
usable**: its grpc pair 9091 is taken by `grpc.command.version.port` in `application.yaml`.

## 4.3 — `docker-compose.yml`

**Add** two blocks, copying the shape of an existing pair — `tournament-service` and `tournament-db`
are the model — and reusing the YAML anchors rather than inlining them: `*service-db`,
`*microservice-deps`, `*microservice-env`, `*limits-medium`, `*service-healthcheck`.

| Key | Value |
|---|---|
| service key and `container_name` | `<sat-infra>-service` |
| `ports` | `"<port>:<port>"` from 4.2 — never a reserved port |
| `START_CLASS` | `…quizzes.microservices.<sat>.<Satellite>ServiceApplication` |
| `SPRING_PROFILES_ACTIVE` | `<sat-infra>-service,sagas,${COMM_LAYER:-stream}` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<sat-infra>-db:5432/<satinfra>db` |
| healthcheck | `wget -q --spider http://localhost:<port>/actuator/health` |
| db block | `<sat-infra>-db`, `container_name` the same, `POSTGRES_DB: <satinfra>db` |

`<svc>-service` and `<svc>-db` are **left untouched** — same port, same container, same datasource.

## 4.4 — `pom.xml`

**Add** a profile beside the eight existing ones:

```xml
<profile>
  <id><sat-infra>-service</id>
  <properties>
    <activatedProperties><sat-infra>-service,sagas,${communication.layer}${version.suffix}</activatedProperties>
  </properties>
</profile>
```

## 4.5 — `application-<sat-infra>-service.yaml`

**Create** it, copying the shape of an existing service file. Seven keys name the service:

1. `spring.application.name: <sat>` — **the camel form**, and the single most consequential line in
   this layer (layer 3.1(b)).
2. `spring.datasource.url: …/${spring.application.name}db`
3. `spring.cloud.function.definition: commandResponseChannel;<sat>ServiceCommandChannel;<sat>EventSubscriber;`
4. producer binding `<sat>-command-channel`
5. consumer binding `<sat>ServiceCommandChannel-in-0`, `group: ${spring.application.name}-service-group`
6. `commandResponseChannel-in-0` and `<sat>EventSubscriber-in-0` on `event-channel`
7. `server.port` and `grpc.server.port` from 4.2

**The `routes:` block — a behaviour requirement, not housekeeping.** Every path case 6 decided to move
is carved out of `<svc>`'s block and declared here, with:

- a **distinct id**: all existing files use `id: ${spring.application.name}-service`, which is unique
  per file, so the default is fine — but an *adopted* predicate keeps a suffix, as merge 4.5 does;
- an `order` **below** the core's, because the satellite's predicates are the more specific ones. The
  existing orders are 1–10 and all taken, so use `core order − 10`, which cannot collide;
- `uri: lb://${spring.application.name}${gateway.service-suffix}` — discovery resolves the rest.

A path that moves without its route 404s. A path that moves with an order *above* the core's is
shadowed by the core's catch-all and never reaches the satellite. Both are invisible to the test
profile; 4.11 is where they show.

## 4.6 — `application-<svc>-service.yaml`

What does **not** change: `spring.application.name`, `server.port`, `grpc.server.port`,
`datasource.url`, and its own command channel.

What does: the `routes:` predicates that 4.5 carved out are **removed or narrowed** here. Leaving a
broad predicate (`Path=/tournaments/**`) beside a specific one in the satellite's file is what makes
the order rule load-bearing; narrowing it is better than relying on the order.

## 4.7 — `application.yaml` (the shared file)

**Add** the satellite's entries, in the profile each belongs to:

| Profile | Entry |
|---|---|
| `remote` | `<sat>EventSubscriber-in-0` binding on `event-channel`, `group: <sat>-service-group` |
| `remote` | `<sat>EventSubscriber` in `quizzes.function-definition.events` |
| `stream` | `<sat>ServiceCommandChannel` in `spring.cloud.function.definition` |
| `stream` | producer binding `<sat>-command-channel` |
| `stream` | consumer binding `<sat>ServiceCommandChannel-in-0`, `group: <sat>-service-group` |

**Exactly one event subscriber per deployment unit**, and its group must be its own: sharing `<svc>`'s
group would split the event stream between the two services, each seeing half the events.

The service lists in this file are **not in a consistent order** across their sites, and they live in
different `on-profile` documents. Match by name, never by position.

## 4.8 — Application class

**Create** `microservices/<sat>/<Satellite>ServiceApplication.java`, copying an existing one:

```java
@SpringBootApplication(scanBasePackages = { "…quizzes.microservices.<sat>", "…ms" })
@EnableJpaRepositories({ "…quizzes.microservices.<sat>", "…ms" })
@EntityScan({ "…quizzes.microservices.<sat>", "…quizzes.events", "…ms" })
@Profile("<sat-infra>-service")
public class <Satellite>ServiceApplication { … }
```

The three scan roots are the entire mechanism by which one process owns one aggregate: they are why
layer 1.2 put the satellite in `microservices/<sat>/` and why a class left behind in `<svc>` is
invisible to this process.

**Do not touch `<Svc>ServiceApplication`** — named after the package directory, not the root (`answer`'s is `AnswerServiceApplication`) —, and do not edit anything under `simulator/`.

## 4.9 — Database

`<satinfra>db` is created empty by its container at first start; `<svc>db` survives untouched. The
satellite's tables appear in the new database because its entity classes sit under this application's
`@EntityScan` root.

**No data is migrated.** Aggregate ids come from a per-database sequence (composition doc §2.4), so
the moved entities' existing rows stay in `<svc>db` and are abandoned, and the satellite's ids restart
at 1 — `<satellite>AggregateId = 3` and `<aggr>AggregateId = 3` will coexist. This is acceptable only
because every service runs `ddl-auto: create` and the Monitor phase regenerates state with a fresh
load test; it would not be acceptable in production. **State it in the CLAUDE.md log.**

## 4.10 — Tests

`BeanConfigurationSagas.groovy` groups `@Bean` methods **by kind, not by aggregate**, so the
satellite's beans land in 5–7 different regions, each with the method name equal to the bean name:

| Region | Bean |
|---|---|
| Functionalities | `<sat>Functionalities()` |
| EventProcessing | `<sat>EventProcessing()` — only if 3.4 moved a subscription here |
| CustomRepository | `<sat>CustomRepositorySagas()` |
| Factory | `sagas<Satellite>Factory()` |
| Service | `<sat>Service()` — with the repository arity its constructor actually takes |
| EventHandling | `<sat>EventHandling()` — only if it consumes events |
| CommandHandler | `<sat>CommandHandler()` |

Add the matching imports at the top. A missing bean fails the test profile loudly — the one wiring gap
tests do catch. Then re-point the `.groovy` tests whose imports named the moved classes.

**Including the ones that assert on the DTO.** A test reading `<aggr>Dto.get<MovedField>()` follows the
shape 2.7 chose: through the composed `<Satellite>Dto` when composing, or through the satellite's own
read when dropping. This is the assertion that proves the composition actually happens — without it, a
core DTO whose satellite field is never filled returns `null` and every test still passes.

Then run:

```bash
test-gate --project-path <project-path> --profile <profile>   # no JDK here — the exact command is in your prompt
```

See `applications/CLAUDE.md` § "Final step — test gate": run it last, fix what it reports
(at most 3 cycles), and report the result you actually saw.

The pipeline re-runs this profile as a hard gate right after the skill and on red **discards the
entire branch** — a red run is lost work, not a partial success. If it is still red after fixing what
you can, report `Result: CHANGED` with `WARNING: sagas test profile red — <n> failure(s): <test names>`.
Never report it green unless you saw it green.

> `applications/quizzes-full` has its own `BeanConfigurationSagas.groovy` and its own `ServiceMapping`.
> Only files under `<projectPath>/` may be modified, so **name it in the log** as work this run could
> not do rather than leaving the divergence unrecorded.

## 4.11 — Verify

The count identity first. Seven independent readings of "how many deployment units are there" must
agree, and all must have risen by exactly one:

```bash
find src/main/java -name "*ServiceApplication.java" | wc -l
grep -cE "^  [a-zA-Z-]+-service:" docker-compose.yml
grep -cE "^  [a-zA-Z-]+-db:"      docker-compose.yml
grep -cE "<id>[a-zA-Z-]+-service</id>" pom.xml
ls src/main/resources/application-*-service.yaml | wc -l
grep -cE '^    [A-Z_]+\("' src/main/java/org/anonymous/msrefactor/quizzes/ServiceMapping.java
grep -c "EventSubscriber-in-0" src/main/resources/application.yaml
grep -hoE '"[0-9]+:' docker-compose.yml ../../docker-compose.yml | grep -v '"8080:' | sort | uniq -d   # collisions: empty
grep -hc '"<port>:' docker-compose.yml ../../docker-compose.yml        # 1 in total, and <port> is not reserved (4.2)
docker compose -f docker-compose.yml config -q                         # exit 0
```

Any one of them out of step is the operation half-applied, and names which subsection was skipped.

Then the path lengths. Every new path carries the satellite's name twice (package directory and class
name), and a Windows checkout cannot create a path over 260 characters — the files silently go
missing on the host. Repository-relative paths must stay within 210:

```bash
git status --porcelain -uall -- . | awk '{p=$NF} length(p) > 210 {print length(p), p}'   # empty, else WARNING
```

Then the runtime proof — a static check cannot see either failure:

```bash
# the satellite's commits reach a consumer, and nothing times out
docker logs <sat-infra>-service | grep -c "Dispatching command locally"        # > 0
docker logs <svc>-service      | grep -c "to <sat>-command-channel"            # > 0 when the core reads it
docker logs <svc>-service      | grep -c "Timeout after"                       # 0
# the queue has a consumer, not just a producer
docker exec rabbitmq rabbitmqctl list_queues name consumers | grep "<sat>-command-channel"   # consumers >= 1
# every moved route still answers through the gateway
curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/<moved-path>       # not 404
```

**Nothing was lost**, against Step 0.5's baseline. Both must print nothing — and note that the
satellite's files are now outside `$M`, so the greps span both trees:

```bash
B="${TMPDIR:-/tmp}/refactor-baseline-<svc>-split"
MS=src/main/java/org/anonymous/msrefactor/quizzes/microservices
grep -rhoE '(QuizzesErrorMessage\.|QuizzesException\(\s*)[A-Z][A-Z0-9_]+' $MS/<svc> $MS/<sat> --include=*.java \
  | grep -oE '[A-Z][A-Z0-9_]+$' | sort -u | comm -23 "$B/errors" -
grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping\([^)]*\)' $MS/<svc> $MS/<sat> --include=*.java \
  | sort -u | comm -23 "$B/routes" -
```

A route printed no longer answers — an externally visible change. An error printed is no longer
raised. Fix it, or name it in the log with its reason; never report `CHANGED` over a silent one.

---

## Invariants

- **4.1 and 4.3/4.7 are a pair.** The constant and the deployment unit are added together, or the
  split leaves commands addressed to nothing. The count identity in 4.11 is what proves it.
- **`spring.application.name` is `lowerFirst(<Satellite>)`.** Not the kebab form, not the profile
  name, not the class name. Layer 3.1(b) explains what breaks otherwise.
- **The core is not renamed.** Its port, database, profile and application name are exactly what they
  were; a diff that touches them means the split turned into something else.
- **No data is migrated**, and the log says so.

---

## Log line

```
LAYER 4 (deployment): <sat-infra>-service created (port <n>, grpc <n>, <satinfra>db); ServiceMapping.<SAT> added; routes moved: <paths or none>; <n> test bean(s) across <n> region(s); deployment units <pre> -> <post> (seven readings agree); compose config ok; sagas test profile <green | red — n failure(s)>
```
