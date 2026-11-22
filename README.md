# Self-Adaptive Microservices Refactoring Tool

This repository contains a **self-adaptive refactoring tool** built as a **MAPE-K loop**
(Monitor → Analyze → Plan → Execute, over a shared Knowledge base) that operates on a running
microservices application. The loop observes the system at runtime (static metrics, distributed
traces, load tests), detects architectural problems, plans a concrete refactoring operation
(`MERGE_AGGREGATES`, `SPLIT_AGGREGATE`), applies it to the
source code using an LLM coding agent (Claude Code) as the mutator, and redeploys the result as a
new versioned image — closing the loop so the next iteration can measure the effect of the change.

The managed system is the **Quizzes** application, a business-logic-rich microservices case study
built on the **Microservices Simulator**, a DDD framework that isolates domain logic from
communication and transactional infrastructure (see [docs/simulator.md](docs/simulator.md)).

| Directory | Contents |
|-----------|----------|
| `refactor-tool/` | The MAPE-K loop: `rt-app` (Spring Boot) + `locust-service` (load-testing sidecar) — [refactor-tool/README.md](refactor-tool/README.md) |
| `frontend/` | Next.js dashboard that drives the loop — [frontend/README.md](frontend/README.md) |
| `applications/quizzes/` | The managed application (Quizzes), built on the simulator — the baseline the loop starts from |
| `applications/quizzes-v{n}/` | Snapshots of Quizzes after each loop iteration — [Generated code versions](#generated-code-versions-applicationsquizzes-v) |
| `simulator/` | The Microservices Simulator core library — [docs/simulator.md](docs/simulator.md) |
| `docs/`, `guides/` | Architecture and concept docs; run/deployment guides |

## Setup

Prerequisites:

- **JDK 21** and **Maven 3.9+** — the simulator and Quizzes build on Java 21; the refactor-tool
  targets Java 17 and builds fine under the same JDK.
- **Docker + Docker Compose** — the Quizzes stack and the refactor-tool run as containers.
- **Claude Code CLI** (`@anthropic-ai/claude-code`) logged in on the host (`claude login`) — the
  Plan and Execute phases mount that session read-only into the `rt-app` container; no API key is
  used.
- **Node.js 20+ with corepack** — only for the frontend.

Install the simulator library into the local Maven repository first (Quizzes depends on it):

```bash
cd simulator
mvn install
```

## One-time setup — git and credentials

Do this once, **before building the baseline image**: the Execute phase creates branches and
commits on top of a configured base branch (`RT_EXECUTE_BASE_BRANCH`, default
`tool/self-refactor`), so the `quizzes:baseline` image built in the next section should be built
from that baseline commit.

**1. Initialize the repository and push it to GitHub.** This archive is distributed without a
`.git` directory, but the tool uses git — the Execute phase runs `git fetch` against `origin`
before creating each version branch, and the static-analysis collector can read past versions from
remote branches/tags — so the repository must exist on GitHub:

```bash
git init -b tool/self-refactor
git add -A && git commit -m "baseline"
git remote add origin https://github.com/<your-user>/<your-repo>.git
git push -u origin tool/self-refactor
```

(or init any branch name and export `RT_EXECUTE_BASE_BRANCH` to match before `docker compose up`).

**2. GitHub access token.** Create a **classic** personal access token with the `repo` scope at
<https://github.com/settings/tokens> and put it in `refactor-tool/.env` (docker compose injects it
into the rt-app container):

```bash
cd refactor-tool
cp .env.example .env
# edit .env:  GITHUB_TOKEN=ghp_...
```

The token is used only for non-interactive `git fetch` inside the container — required when the
remote is private; the tool never pushes. Its value is masked in every log the tool writes
(`SecretRedactor`).

**3. Claude access token (OAuth session).** This is not a token you paste into a file: run
`claude login` once **on the host**. The refactor-tool's `docker-compose.yml` mounts the resulting
session read-only into the container (`~/.claude` and `~/.claude.json`; on Windows they resolve
via `%USERPROFILE%`), so the Plan and Execute phases authenticate through your Claude Pro/Max
OAuth session — no API key is consumed. Verify once rt-app is up:

```bash
curl -s http://localhost:8090/claude/status
```

## Quickstart — the full loop in three terminals

> **[One-time setup](#one-time-setup--git-and-credentials) first:** init the git branch, push the
> repository to GitHub, set `GITHUB_TOKEN` in `refactor-tool/.env`, and run `claude login` on the
> host.

1. **Terminal 1 (repo root):** start the managed Quizzes stack in its distributed topology — see
   [Starting the managed stack](#starting-the-managed-stack-distributed-topology--what-the-loop-operates-on).
2. **Terminal 2 (`refactor-tool/`, bash):** `bash deploy.sh --seed-basic` (first run), or
   `bash deploy-rt.sh` to redeploy only the tool — see [Running the refactor tool](#running-the-refactor-tool).
3. **Terminal 3 (`frontend/`):** `yarn install && yarn dev`, then open <http://localhost:3000> —
   see [Frontend](#frontend).

## Running the Quizzes application

Run the test suite (no infrastructure needed — tests use an in-memory H2 database):

```bash
cd applications/quizzes
mvn clean -Ptest-sagas test                                          # all sagas tests
mvn clean -Ptest-sagas test -Dtest=AddParticipantAndUpdateStudentNameTest  # single class
```

### Starting the managed stack (distributed topology — what the loop operates on)

The refactor loop manages the **distributed** topology (gateway + one container per service) with
**distributed version IDs** and the **stream** communication layer. Starting it also provides
Jaeger and creates the `messaging-network` that the refactor-tool joins, so it must be up before
the refactor-tool. Run everything from the **repository root**.

In the original repository this is done from the `tool/self-refactor` branch
(`git checkout tool/self-refactor`); in this anonymized archive there is no `.git`, so after the
`git init -b tool/self-refactor` step from the
[one-time setup](#one-time-setup--git-and-credentials) you are already on the right branch.

Linux / macOS:

```bash
export VERSION_MODE=distributed-version COMM_LAYER=stream
docker compose -f applications/quizzes/docker-compose.yml build gateway
docker build -f applications/quizzes/Dockerfile --build-arg PROFILE=sagas -t quizzes:baseline -t quizzes:latest .
docker compose -f applications/quizzes/docker-compose.yml down --remove-orphans
docker compose -f applications/quizzes/docker-compose.yml up -d --no-build gateway answer-service course-service execution-service question-service quiz-service topic-service tournament-service user-service
```

Windows (PowerShell):

```powershell
$env:VERSION_MODE = "distributed-version"
$env:COMM_LAYER = "stream"
docker compose -f applications/quizzes/docker-compose.yml build gateway
docker build -f applications/quizzes/Dockerfile --build-arg PROFILE=sagas -t quizzes:baseline -t quizzes:latest .
docker compose -f applications/quizzes/docker-compose.yml down --remove-orphans
docker compose -f applications/quizzes/docker-compose.yml up -d --no-build gateway answer-service course-service execution-service question-service quiz-service topic-service tournament-service user-service
```

The explicit `docker build` tags the application image as `quizzes:baseline` / `quizzes:latest`;
the Execute phase builds subsequent `quizzes:v{n}` images on top of this baseline, and the
services are started with `--no-build` so they all run that same image.

### Alternative: single-container topology

For working on Quizzes itself without the loop, a simpler topology is enough:

```bash
cd applications/quizzes
docker compose up postgres -d
docker compose up quizzes-local -d
curl -s http://localhost:8080/actuator/health
```

Other deployment topologies (centralized/remote, RabbitMQ, gRPC) are described in
[docs/simulator.md](docs/simulator.md) and [guides/run-docker.md](guides/run-docker.md).

## Running the refactor tool

The [one-time setup](#one-time-setup--git-and-credentials) must already be done: git branch
pushed to GitHub, `GITHUB_TOKEN` in `refactor-tool/.env`, and `claude login` run on the host.

With the Quizzes stack up, build and start the refactor-tool:

```bash
cd refactor-tool
mvn clean package -DskipTests
docker compose up --build -d
```

Or use the helper scripts, which run the Maven build for you:

| Script | Effect |
|--------|--------|
| `./deploy.sh [--ghost] [--seed-basic]` | Full reset: wipes `rt-db`, rebuilds and recreates every container |
| `./deploy-rt.sh [--ghost] [--seed-basic]` | Rebuilds and recreates `rt-app` only |
| `./deploy-locust.sh` | Rebuilds and recreates `locust-service` only |

`--seed-basic` loads the baseline refactoring constraints; `--ghost` short-circuits the Execute
phase for UI work (no real mutation or redeploy).

Typical usage (the scripts are plain bash — on Windows run them from Git Bash):

```bash
cd refactor-tool
bash deploy.sh --seed-basic   # first run: full reset + seed the baseline constraints
bash deploy-rt.sh             # afterwards: rebuild and redeploy rt-app only
```

Once up:

- rt-app — <http://localhost:8090> (`GET /actuator/health`, `GET /claude/status`)
- locust-service — <http://localhost:8100> (`GET /health`)
- frontend — <http://localhost:3000> (started separately, see [frontend/README.md](frontend/README.md))

Key configuration variables (full reference in [refactor-tool/README.md](refactor-tool/README.md)):

| Variable | Default | Purpose |
|----------|---------|---------|
| `RT_ANALYZE_ENABLED_OPERATIONS` | `MERGE_AGGREGATES,SPLIT_AGGREGATE` | Which refactoring operations Analyze may propose (add `CREATE_REPLICA,REMOVE_REPLICA` to enable replicas) |
| `COMM_LAYER` | `stream` | Communication layer of the managed app — must match how Quizzes was started |
| `RT_EXECUTE_GHOST_MODE` | `false` | Park Execute at a manual verdict instead of mutating/deploying |
| `RT_EXECUTE_SKIP_TESTS` | `false` | Skip the test gate after the mutation |

### Running a loop iteration

Each phase is a REST call correlated by an `iterationId`; the frontend (or curl) sequences them:

```bash
RT=http://localhost:8090

# 0. Open a loop iteration
ITER=$(curl -s -X POST $RT/knowledge/iterations \
  -H 'Content-Type: application/json' -d '{"name":"iteration-1"}' | jq -r .iterationId)

# 1. MONITOR — static analysis → Locust load test → Jaeger collection (poll until DONE)
SNAP=$(curl -s -X POST $RT/monitor/pipeline/start \
  -H 'Content-Type: application/json' \
  -d "{\"iterationId\":\"$ITER\",\"windowMinutes\":15}" | jq -r .snapshotId)
curl -s $RT/monitor/pipeline/$SNAP/status | jq -r .pipelineStatus

# 2. ANALYZE — hotspots + ranked candidate operations
curl -s -X POST $RT/analyze/run -H 'Content-Type: application/json' \
  -d "{\"snapshotId\":\"$SNAP\",\"iterationId\":\"$ITER\",
       \"qualityPriorities\":[\"COUPLING\",\"CONSISTENCY\",\"PERFORMANCE\"]}" \
  | jq '.candidateOperations[0]'

# 3. PLAN — generate from a chosen candidate, then approve a plan version
PLAN=$(curl -s -X POST $RT/plan/generate \
  -H 'Content-Type: application/json' -d @candidate.json | jq -r .planId)
VER=$(curl -s $RT/plan/$PLAN/versions | jq -r '.[-1].planVersionId')
curl -s -X POST $RT/plan/versions/$VER/approve

# 4. EXECUTE — async; the LLM mutator applies the refactoring, tests gate it, and a
#    new quizzes:v{n} image is built and deployed
curl -s -X POST "$RT/execute/run/plan/$PLAN?iterationId=$ITER"
curl -s $RT/execute/status | jq -r '.status, .executeLog'

# 5. Inspect the iteration and the resulting code version
curl -s $RT/knowledge/iterations/$ITER | jq .
curl -s $RT/knowledge/versions/tree | jq .
```

The complete walkthrough, architecture diagram, and per-phase documentation are in
[refactor-tool/README.md](refactor-tool/README.md) and
[docs/refactor-tool/README.md](docs/refactor-tool/README.md).

## Frontend

The Next.js dashboard in [frontend/](frontend/) visualizes and drives the loop
(<http://localhost:3000>):

```bash
cd frontend
corepack enable   # once — the project uses yarn 4 via corepack
yarn install
yarn dev
```

See [frontend/README.md](frontend/README.md) for the available routes and build/lint commands.

## Further documentation

| Topic | Path |
|-------|------|
| Microservices Simulator (architecture, topologies, configuration) | [docs/simulator.md](docs/simulator.md) |
| Quizzes application architecture | [docs/architecture.md](docs/architecture.md) |
| Refactor tool — MAPE-K overview and per-phase docs | [docs/refactor-tool/README.md](docs/refactor-tool/README.md) |
| Refactor tool — deployment, configuration, end-to-end loop | [refactor-tool/README.md](refactor-tool/README.md) |
| Run guide (Docker Compose topologies) | [guides/run-docker.md](guides/run-docker.md) |

## Generated code versions (`applications/quizzes-v*`)

Each `applications/quizzes-v{n}` folder is the managed application exactly as the loop left it
after iteration *n*. In the original repository these are git branches created by the Execute
phase; since this archive is distributed without `.git`, they are materialized as folders. Gaps in
the numbering (v4, v8, v10, v13) are iterations that did not retain a code version.

They are inert — nothing builds or reads them in place — and exist so the results can be verified
without running the loop (which needs a Claude session and nondeterministic LLM runs):

- **Diff a version against the baseline:** `diff -r applications/quizzes applications/quizzes-v19`
- **Read the operation trace:** each version's `applications/quizzes-v{n}/CLAUDE.md` accumulates
  one `<!-- refactor-log -->` entry per operation — the skill invocation, the gate decisions with
  code citations, the result (`CHANGED` / `NO_CHANGE`) and the test-gate outcome.

### Deploying a version

The tool and the skills resolve the managed application by path (`applications/quizzes` —
`--project-path`, the static-analysis collector, the test gate), so to run the loop **on top of a
generated version** swap the folders: set the baseline aside and rename the version into its place,
so the tool sees that version's static structure:

```bash
mv applications/quizzes     applications/quizzes-baseline
mv applications/quizzes-v19 applications/quizzes
```

Then rebuild and start the stack as in the [quickstart](#quickstart--the-full-loop-in-three-terminals)
(the `quizzes:baseline` image must be rebuilt from the swapped-in source). Invert the two `mv`
commands to restore the baseline.

## License

[MIT License](LICENSE)
