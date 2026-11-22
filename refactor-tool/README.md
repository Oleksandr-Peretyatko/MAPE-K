# Refactor Tool

A self-adaptive **MAPE-K loop** that observes the running quizzes microservices, detects
architectural problems, plans a concrete refactoring, applies it to the source code via Claude,
and redeploys the result as a new versioned image. Each phase of the loop is a package inside a
single Spring Boot application (`rt-app`); load testing is delegated to a standalone Python
sidecar (`locust-service`).

**Per-phase documentation lives in [`../docs/refactor-tool/`](../docs/refactor-tool/README.md).**
This README covers deployment, configuration, and running the loop end to end.

---

## Architecture

```
                              ┌─────────────────────────────┐
                              │  Next.js frontend  :3000    │
                              │  /loop /versions /iterations│
                              │  /config /graph             │
                              └──────────────┬──────────────┘
                                             │ REST (CORS)
                                             ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  rt-app  (Spring Boot 3.3.5, Java 17)                    :8090           │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  monitor/    analyze/    plan/    execute/    knowledge/            │  │
│  │        cross-cutting:  quality/   config/                           │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└───┬──────────┬─────────────┬──────────────┬──────────────┬───────────────┘
    │          │             │              │              │
    │ JDBC     │ REST        │ HTTP         │ CLI          │ docker.sock
    ▼          ▼             ▼              ▼              ▼
┌────────┐ ┌──────────────┐ ┌──────────┐ ┌─────────┐ ┌──────────────────┐
│ rt-db  │ │locust-service│ │  Jaeger  │ │ claude  │ │  Docker daemon   │
│postgres│ │FastAPI :8100 │ │  :16686  │ │   CLI   │ │ (outside-of-     │
│ :5433  │ │      │       │ └──────────┘ └─────────┘ │  Docker)         │
│refactor│ │      ▼       │      ▲                   └────────┬─────────┘
│  db    │ │ ┌──────────┐ │      │ traces                     │ build +
└────────┘ │ │ locust-db│ │      │                            │ compose up
           │ │ postgres │ │      │                            ▼
           │ │(internal)│ │ ┌────┴──────────────────────────────────────┐
           │ └──────────┘ │ │  quizzes application            :8080     │
           └──────┬───────┘ │  8 microservices (or quizzes-local)       │
                  │ load    │  images: quizzes:v1 … quizzes:latest      │
                  └────────▶│                                           │
                            └───────────────────────────────────────────┘

  locust-service ──POST /monitor/locust/callback──▶ rt-app   (results webhook)
  rt-app         ──POST /api/runs──────────────────▶ locust-service (trigger)
```

### Networks

| Network | Members | Purpose |
|---------|---------|---------|
| `rt-network` (bridge, owned) | `rt-app`, `rt-db`, `locust-service`, `locust-db` | Internal refactor-tool traffic — this is where rt-app reaches rt-db and locust-service |
| `microservices-simulator_messaging-network` (external) | `rt-app`, `locust-service` (and `rt-db`, see note) | Reach Jaeger and the quizzes services |

The external network is created by the repo-root `docker-compose.yml`. **The quizzes stack must be
up before the refactor-tool**, or the network will not exist. `rt-db` is also attached to the
external network in `docker-compose.yml`, but nothing needs that: rt-app reaches it on
`rt-network`.

The only UI is the Next.js frontend (routes `/loop`, `/versions`, `/iterations`, `/config`,
`/graph`; `/analyze`, `/execute`, `/monitor` and `/plan` redirect to `/loop?tab=…`). rt-app serves
no static pages of its own on :8090.

### Privileged mounts (what makes the Execute phase possible)

| Mount | Mode | Why |
|-------|------|-----|
| `../:/app/project` | read-write | Execute runs `git` and lets Claude mutate `applications/quizzes/` |
| `/var/run/docker.sock` | read-write | Execute runs `docker build` / `docker compose up` against the host daemon |
| `${USERPROFILE:-~}/.claude` | read-only | Claude Pro Max OAuth session (no API key consumed) |
| `${USERPROFILE:-~}/.claude.json` | read-only | Claude CLI main config |
| `claude-session-env` (volume) | read-write | Writable overlay for `/root/.claude/session-env` |

---

## MAPE-K Phases

All five phases are implemented. Nothing returns `501`.

| Phase | URL prefix | Produces | Documentation |
|-------|-----------|----------|---------------|
| Monitor | `/monitor/**` | `MonitorSnapshot` — static M1/M2, Jaeger runtime metrics, Locust load results | [monitor.md](../docs/refactor-tool/monitor.md) |
| Analyze | `/analyze/**` | `AnalysisResult` — QA scores, hotspots, ranked `CandidateOperation[]` | [analyze.md](../docs/refactor-tool/analyze.md) |
| Plan | `/plan/**` | `PlanResponse` — Claude skill invocation + versioned plan document | [plan.md](../docs/refactor-tool/plan.md) |
| Execute | `/execute/**` | `ExecuteRefactorEntity` — git branch, commit SHA, `quizzes:v{n}` image | [execute.md](../docs/refactor-tool/execute.md) |
| Knowledge | `/knowledge/**` | `LoopIteration`, `CodeVersion`, `DiscoveryGraph` | [knowledge.md](../docs/refactor-tool/knowledge.md) |
| _(cross-cutting)_ | `/quality/**`, `/config/**` | QA scoring, thresholds, constraints, operation×QA matrix | [analyze.md](../docs/refactor-tool/analyze.md) |

There is **no server-side driver** that chains the phases. Each phase is its own REST call;
they are correlated by an `iterationId` and sequenced by the frontend (or by curl).

---

## Maven Modules

| Module | Type | Purpose |
|--------|------|---------|
| `rt-base` | Plain JAR (no Spring) | Shared domain models: `MonitorSnapshot`, `AggregateMetrics`, `SagaMetrics`, `StaticMetrics`, `AnalysisResult`, `CandidateOperation`, `RefactorBlueprint`, `LoopIteration`, `CodeVersion`, `DiscoveryGraph`, `QualityAttributeScore` |
| `rt-app` | Spring Boot fat JAR | All phase logic: controllers, services, collectors, JPA entities |

Java **17**, Spring Boot 3.3.5, Lombok 1.18.34, Jackson 2.17.2. The Jackson `ObjectMapper` is
configured leniently (unknown JSON properties are ignored), so rows written by older rt-app
versions — with fields that no longer exist — still load.

---

## Prerequisites

- Java 17 and Maven 3.9+ — the rt-app Docker image is **not** self-contained: `rt-app/Dockerfile`
  copies a pre-built `target/rt-app-1.0-SNAPSHOT.jar`, so run `mvn clean package -DskipTests`
  before `docker compose up --build` (the `deploy*.sh` scripts do this for you)
- Docker + Docker Compose
- The quizzes infrastructure running (provides Jaeger on 16686 and the `messaging-network`)
- The Claude CLI (`@anthropic-ai/claude-code`) logged in **on the host** — run `claude login`
  once. The Plan and Execute phases mount that session into the container.
- A `refactor-tool/.env` file with `GITHUB_TOKEN` (classic PAT, `repo` scope) if the remote is
  private, so the Execute phase can `git fetch` non-interactively. The tool never pushes.

---

## Configuration

Defaults live in `rt-app/src/main/resources/application.yml` under the `rt.*` tree. Every value
below can be overridden by an environment variable; `docker-compose.yml` sets the container-local
values, and `.env` supplies the secrets.

### Monitor

| Variable | Default | Description |
|----------|---------|-------------|
| `RT_MONITOR_JAEGER_BASE_URL` | `http://localhost:16686` | Jaeger Query HTTP API |
| `RT_MONITOR_COLLECTION_WINDOW` | `2` (compose sets `15`) | Minutes of trace history per cycle |
| `LOCUST_SERVICE_URL` | `http://localhost:8100` | locust-service base URL |
| `LOCUST_TARGET_HOST` | `http://localhost:8080` | Default load-test target |
| `RT_LOCUST_SCRIPTS_PATH` | `refactor-tool/utils/locust` | Scanned to map Locust tasks to Functionality classes |
| `STATIC_ANALYSIS_GIT_REPO_PATH` | `../..` | Git repo root — versions come from tags matching `v<n>` (compose sets `/app/project`) |

Locust is the only load generator; rt-app reads no result files, so there is no results or
script directory to configure beyond `RT_LOCUST_SCRIPTS_PATH` (used only to map task names to
Functionality classes).

### Execute

| Variable | Default | Description |
|----------|---------|-------------|
| `RT_EXECUTE_PROJECT_ROOT` | `../` | Repository root inside the container |
| `RT_EXECUTE_COMPOSE_FILE` | `../applications/quizzes/docker-compose.yml` | Compose file used for redeploy |
| `RT_EXECUTE_BUILD_PROFILES` | `sagas` | Maven profile baked into the runtime image |
| `RT_EXECUTE_BASE_BRANCH` | `tool/self-refactor` | Fallback branch start point |
| `RT_EXECUTE_DEPLOY_TARGET` | `auto` | `auto` derives the service list from the quizzes compose file on every deploy (a merge/split needs no config change); any other value is a literal comma-separated list, e.g. `quizzes-local` |
| `RT_EXECUTE_DEPLOY_HEALTH_TIMEOUT_SECONDS` | `600` | Seconds to wait for services to report healthy after the cold start (each service has a 180 s healthcheck start period behind a fresh database) |
| `RT_EXECUTE_SKIP_TESTS` | `false` | Skip the Sagas test gate (the only gate — the stack runs Sagas only) |
| `RT_EXECUTE_TEST_TIMEOUT_SECONDS` / `RT_EXECUTE_BUILD_TIMEOUT_SECONDS` | `1800` | Kill the test gate / image build / compose down-up after this long and fail the run |
| `RT_EXECUTE_GHOST_MODE` | `false` | Ghost mode — see below. Set it with `./deploy-rt.sh --ghost` rather than by hand |
| `RT_EXECUTE_GIT_USER_NAME` / `_EMAIL` | `refactor-tool` / `refactor@simulator` | Commit identity |
| `RT_TOPOLOGY_ALWAYS_UP` / `RT_TOPOLOGY_EXCLUDE` | `gateway` / _(empty)_ | Services always prepended to the deploy set / discovered services to drop |
| `GITHUB_TOKEN` | _(unset)_ | PAT injected as a git `insteadOf` credential |

`VERSION_MODE` and `COMM_LAYER` are forwarded to the quizzes redeploy and **must match how the
quizzes stack was originally launched** — the step-6 `docker compose down` + `up -d --no-build`
re-interpolates them from rt-app's own environment. There is no transaction-mode variable: the
application implements Sagas only.

### Claude and Analyze

Defaults live in `rt-app/.../rt/constants/` (`ClaudeConstants`, `AnalysisConstants`); any of them
can be overridden through the matching environment variable without a rebuild.

| Variable | Default | Description |
|----------|---------|-------------|
| `RT_CLAUDE_MUTATOR_MAX_TURNS` | `200` (set explicitly in compose) | `--max-turns` for the Execute-phase mutator; merge-aggregate needs well over a hundred |
| `RT_CLAUDE_ENRICHER_MAX_TURNS` | `30` | `--max-turns` for the Plan enricher |
| `RT_CLAUDE_CLI_MODEL` | `claude-opus-4-8` | Model used by the CLI phases |
| `RT_ANALYZE_ENABLED_OPERATIONS` | `MERGE_AGGREGATES,SPLIT_AGGREGATE` | Operations Analyze may propose; add `CREATE_REPLICA`/`REMOVE_REPLICA` to re-enable replicas |

Claude is driven exclusively through the `claude` CLI and the host's Pro Max session mounted into
the container; there is no API-key mode. `GET /claude/status` reports whether that session is
usable.

#### Obsolete `.env` keys

`.env` is gitignored, so it is not cleaned up for you. Remove these by hand if they are still
there — nothing reads them: `TX_MODE`, `RT_EXECUTE_CLAUDE_MODE`, `ANTHROPIC_API_KEY`,
`RT_EXECUTE_MUTATOR_MAX_TURNS` (replaced by `RT_CLAUDE_MUTATOR_MAX_TURNS`), `JMETER_RESULTS_DIR`,
`LOCUST_RESULTS_DIR`, `LOCUST_SCRIPT_PATH`, `RT_APP_CALLBACK_BASE`.

### Platform

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/refactordb` | rt-db connection |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `rt` / `rt` | rt-db credentials |
| `RT_APP_BASE_URL` | `http://localhost:8090` | Used to build the Locust callback URL |
| `RT_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated browser origins |

Schema is generated by Hibernate (`ddl-auto: update`) — there are no migrations.

---

## Run the quizzes project

```bash
# From the repository root
cd applications/quizzes
docker compose down
docker compose up postgres -d
docker compose up quizzes-local -d
curl -s http://localhost:8080/actuator/health
```

## Build the refactor-tool

The Docker build does not compile anything — it copies the JAR, so this step is mandatory before
every image rebuild:

```bash
cd refactor-tool
# macOS
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean package -DskipTests
# Windows / Linux
mvn clean package -DskipTests
```

## Run the refactor-tool

```bash
cd refactor-tool
docker compose up --build -d
```

Three helper scripts wrap the common redeploy paths (each runs `mvn clean package -DskipTests`
for you where rt-app is rebuilt; `deploy-rt.sh` and `deploy-locust.sh` run in the background and
write `deploy-rt.log` / `deploy-locust.log`, which are gitignored):

| Script | Effect |
|--------|--------|
| `./deploy.sh [--ghost] [--seed-basic]` | Full reset — `docker compose down -v` (wipes `rt-db`), rebuild and recreate every refactor-tool container |
| `./deploy-rt.sh [--ghost] [--seed-basic]` | Rebuild and recreate `rt-app` only (leaves the databases alone) |
| `./deploy-locust.sh` | Rebuild and recreate `locust-service` only (for work on `utils/locust/api/`) |

`--seed-basic` loads `data/constraints-quizzes.sql` into `rt-db`: the refactoring constraints that
hold before any run (the aggregates that cannot be split, the merges that name `user`). It is
idempotent (`ON CONFLICT DO NOTHING`), so pass it on every deploy; because `./deploy.sh` wipes
`rt-db`, a plain `./deploy.sh` loses them.

### Ghost mode (`--ghost`)

Frontend work needs the *shape* of an iteration, not its cost. A real Execute run spends minutes
on a Claude mutation, a test gate, an image build and a full stack recreate — none of which the UI
cares about.

With `--ghost`, an Execute run creates its git branch and then **parks at `AWAITING_VERDICT`**.
The Execute page shows a *Verdict required* card with **Mark Success** / **Mark Failed**. That
verdict is what the tool writes into the target module's `CLAUDE.md` refactor-log entry —
`Result: CHANGED` for success, `Result: NO_CHANGE` for failure — which it then commits. Success
records the code version and hands over to Monitor exactly as a real deploy does; failure marks the
iteration failed and returns to the parent branch. The running application stack is never touched
in either direction, and the version produced carries no image and no code change.

Monitor, Analyze and Plan are unaffected — only Execute is short-circuited.

Use `./deploy-rt.sh --ghost` to turn it on: `./deploy.sh` starts with `docker compose down -v`,
which wipes `rt-db` and with it the iterations you are trying to look at. A plain
`./deploy-rt.sh` turns it back off.

Once up:

- rt-app — <http://localhost:8090> (`GET /actuator/health`)
- locust-service — <http://localhost:8100> (`GET /health`)
- frontend — <http://localhost:3000> (started separately with `yarn dev`)

---

## Run the loop end to end

Every phase writes back to the same `iterationId`, which is what stitches the run together in
the Knowledge phase.

```bash
RT=http://localhost:8090

# 0. Open a loop iteration
ITER=$(curl -s -X POST $RT/knowledge/iterations \
  -H 'Content-Type: application/json' -d '{"name":"iteration-1"}' | jq -r .iterationId)

# 1. MONITOR — async: static analysis → Locust load test → Jaeger collection
SNAP=$(curl -s -X POST $RT/monitor/pipeline/start \
  -H 'Content-Type: application/json' \
  -d "{\"iterationId\":\"$ITER\",\"windowMinutes\":15}" | jq -r .snapshotId)

# poll until pipelineStatus is DONE (or STOPPED / FAILED)
curl -s $RT/monitor/pipeline/$SNAP/status | jq -r .pipelineStatus

# 2. ANALYZE — hotspots + ranked candidate operations
ANALYSIS=$(curl -s -X POST $RT/analyze/run \
  -H 'Content-Type: application/json' \
  -d "{\"snapshotId\":\"$SNAP\",\"iterationId\":\"$ITER\",
       \"qualityPriorities\":[\"COUPLING\",\"CONSISTENCY\",\"PERFORMANCE\"]}")
echo "$ANALYSIS" | jq '.candidateOperations[0]'

# 3. PLAN — turn the chosen candidate into a Claude skill invocation
PLAN=$(curl -s -X POST $RT/plan/generate \
  -H 'Content-Type: application/json' \
  -d @candidate.json | jq -r .planId)

# 3b. Approve a plan version
VER=$(curl -s $RT/plan/$PLAN/versions | jq -r '.[-1].planVersionId')
curl -s -X POST $RT/plan/versions/$VER/approve

# 4. EXECUTE — async (202 Accepted; 409 if another run is in flight)
curl -s -X POST "$RT/execute/run/plan/$PLAN?iterationId=$ITER"
curl -s $RT/execute/status | jq -r '.status, .executeLog'

# 5. Inspect the iteration and the resulting code version
curl -s $RT/knowledge/iterations/$ITER | jq .
curl -s $RT/knowledge/versions/tree | jq .
```

After `DEPLOYED`, start a new iteration: the next Monitor cycle measures the freshly built
`quizzes:v{n}` image and the Analyze phase compares M1/M2 across versions to validate the
refactoring.

---

## Load testing (locust-service)

Load generation runs in its own container — there is no need to install Locust locally or share
CSV files. rt-app triggers a run over REST and locust-service posts the results back.

```bash
# Trigger a run directly (rt-app does this during the Monitor pipeline)
curl -s -X POST http://localhost:8100/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"iteration_id":"'"$ITER"'","target_host":"http://gateway:8080"}'

curl -s http://localhost:8100/api/runs/by-iteration/$ITER | jq .status
curl -s http://localhost:8100/health
```

The service exposes only `POST /api/runs`, `GET /api/runs/{id}`, `GET /api/runs/by-iteration/{id}`
and `GET /health`; the scenario catalogue is `SCENARIO_CONFIG` in `utils/locust/api/runner.py`.

| Scenario | Users | Run time |
|----------|-------|----------|
| `teacher_setup` | 1 | 30s |
| `student_enrollment` | 5 | 30s |
| `tournament_lifecycle` | 3 | 60s |
| `student_management` | 3 | 30s |
| `read_operations` | 5 | 60s |
| `concurrent_add_participant` | 3 | 30s |

Scenario sources live in [`utils/locust/daily/`](utils/locust/daily/) and
[`utils/locust/tournament/`](utils/locust/tournament/); the business flows they encode are
described in [daily-use-flows.md](../docs/refactor-tool/daily-use-flows.md).

---

## Data stores

| Store | Where | Contents |
|-------|-------|----------|
| `refactordb` | `rt-db`, host port 5433 | 13 tables — one snapshot / analysis / plan / execution / iteration per row |
| `locustdb` | `locust-db`, internal only | `locust_run`, `locust_metric` |
| Git | **local** branches `refactor/v{n}-{iterationId}` | The refactored source itself — never pushed; the team publishes the version it selects |
| Docker images | host daemon | `quizzes:v{n}`, `quizzes:{branch}`, `quizzes:latest` |
| `data/` | repo | `constraints-quizzes.sql` (loaded by `--seed-basic`) and `drop-removed-columns.sql` — a manual DDL for the columns and tables Hibernate (`ddl-auto: update`) never drops after a feature is removed; run it by hand with `docker compose exec -T rt-db psql -U rt -d refactordb -v ON_ERROR_STOP=1 < data/drop-removed-columns.sql` once the cleaned-up rt-app has booted |

There is no seed SQL: an empty `rt-db` is a valid start — the schema comes from Hibernate and the
`BASELINE` code version is created at boot by `CodeVersionService.initBaseline`.

Execution logs are stored in the `execute_refactor.execute_log` column, not on disk. Plan-enricher
progress logs are in-memory only and are lost on restart.

---

## Documentation

| Document | Covers |
|----------|--------|
| [`../docs/refactor-tool/README.md`](../docs/refactor-tool/README.md) | MAPE-K loop overview, module layout, table inventory, document index |
| [`../docs/refactor-tool/monitor.md`](../docs/refactor-tool/monitor.md) | Monitor phase — collectors, M1/M2, snapshot schema |
| [`../docs/refactor-tool/analyze.md`](../docs/refactor-tool/analyze.md) | Analyze phase — 9-stage pipeline, hotspots, ranking |
| [`../docs/refactor-tool/plan.md`](../docs/refactor-tool/plan.md) | Plan phase — skill invocation, plan versions, enrichment |
| [`../docs/refactor-tool/execute.md`](../docs/refactor-tool/execute.md) | Execute phase — git, test gate, build, deploy, rollback, ghost mode |
| [`../docs/refactor-tool/knowledge.md`](../docs/refactor-tool/knowledge.md) | Knowledge phase — iterations, code versions, discovery graph |
| [`../docs/refactor-tool/metrics-extraction.md`](../docs/refactor-tool/metrics-extraction.md) | Deep dive: every metric from source to snapshot field |
| [`rt-app/README.md`](rt-app/README.md) | Monitor endpoint reference with sample payloads |
| [`utils/locust/README.md`](utils/locust/README.md) | Locust harness internals |
