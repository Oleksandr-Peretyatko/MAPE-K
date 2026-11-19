# rt-app — Monitor Phase Endpoint Reference

Spring Boot application (port **8090**) implementing the MAPE-K loop.

This file is the **Monitor** endpoint reference with sample payloads. All five phases are
implemented — the other four are documented in [`../../docs/refactor-tool/`](../../docs/refactor-tool/README.md).

The Docker image (`Dockerfile`) copies a pre-built `target/rt-app-1.0-SNAPSHOT.jar` — it does not
run Maven. Build the JAR first (`mvn clean package -DskipTests` from `refactor-tool/`) or use
`../deploy.sh` / `../deploy-rt.sh`, which do it for you.

---

## Monitor Endpoints

Collection is either **automated** (`/monitor/pipeline/*`, what the UI uses) or **stepwise**
(`/monitor/collect/*` + `/monitor/snapshot/assemble`). There is no synchronous single-shot
cycle, no snapshot listing and no dashboard endpoint: a snapshot is reached by its ID, which the
owning loop iteration records.

### `POST /monitor/pipeline/start`

Starts the automated 3-stage pipeline — static analysis → Locust run → Jaeger collection —
and returns the empty snapshot immediately with `pipelineStatus = RUNNING_STATIC`. Each stage
patches the persisted snapshot as it completes.

**Body** (all fields optional): `{"host": "http://gateway:8080", "windowMinutes": 15, "iterationId": "..."}`.
`host` defaults to `LOCUST_TARGET_HOST`, `windowMinutes` to `RT_MONITOR_COLLECTION_WINDOW`.

```bash
curl -s -X POST http://localhost:8090/monitor/pipeline/start \
  -H 'Content-Type: application/json' \
  -d '{"iterationId":"'"$ITER"'","windowMinutes":15}' | jq -r .snapshotId
```

### `GET /monitor/pipeline/{snapshotId}/status`

Returns the snapshot as it currently is in the database — partial data plus `pipelineStatus`
(`RUNNING_STATIC | RUNNING_LOCUST | RUNNING_JAEGER | DONE | STOPPED | FAILED`) and
`pipelineError`. Poll every ~2 s to render tables incrementally.

```bash
curl -s http://localhost:8090/monitor/pipeline/$SNAP/status | jq -r .pipelineStatus
```

### `POST /monitor/pipeline/{snapshotId}/stop`

Asks the pipeline to stop after the current Locust poll cycle. The Jaeger stage still runs once
the stop is processed; the Locust run itself is not killed.

---

### `GET /monitor/snapshot/{id}`

Returns a persisted `MonitorSnapshot` by UUID (`404` if unknown).

```bash
curl -s http://localhost:8090/monitor/snapshot/$SNAP | jq .
```

**Example response (abbreviated):**
```json
{
  "snapshotId": "a1b2c3d4-...",
  "collectedAt": "2026-04-05T14:30:00Z",
  "collectionWindow": "PT15M",
  "windowStart": "2026-04-05T14:15:00Z",
  "windowEnd": "2026-04-05T14:30:00Z",
  "aggregateMetrics": {
    "tournament": {
      "serviceName": "tournament",
      "avgReadLatencyMs": 12.4,
      "avgWriteLatencyMs": 45.2,
      "p95LatencyMs": 98.1,
      "errorRate": 0.01,
      "operationFrequency": 120,
      "bandwidthBytesPerSec": 4096.0,
      "readWriteRatio": 2.3,
      "totalSpanCount": 600,
      "latencySource": "jaeger"
    }
  },
  "sagaMetrics": {
    "CreateTournamentFunctionalitySagas": {
      "workflowName": "CreateTournamentFunctionalitySagas",
      "avgTotalLatencyMs": 234.5,
      "p95TotalLatencyMs": 410.0,
      "avgCrossServiceCalls": 5,
      "compensationRate": 0.02,
      "executionCount": 50,
      "involvedServices": ["tournament", "execution", "topic", "question", "quiz"]
    }
  },
  "locustScenarios": [ ... ],
  "consistencyViolationCount": 0,
  "discoveredServices": ["tournament", "quiz", "..."],
  "staticVersions": [ ... ],
  "diagnostics": {
    "jaegerReachable": true,
    "jaegerSpanCount": 1842,
    "locustScenariosCollected": 23,
    "locustCollectionStatus": "OK",
    "staticVersionsFound": 2,
    "windowStart": "2026-04-05T14:15:00Z",
    "windowEnd": "2026-04-05T14:30:00Z"
  },
  "pipelineStatus": "DONE"
}
```

### `GET /monitor/snapshot/{id}/export`

Same payload, served as an attachment (`snapshot-<id>.json`).

---

### `GET /monitor/static/versions`

Lists the version names available for static analysis — git tags matching `v<n>` in
`STATIC_ANALYSIS_GIT_REPO_PATH` (plus the live working tree). Does not require a prior
collection.

```bash
curl -s http://localhost:8090/monitor/static/versions | jq .
# ["v1", "v2"]
```

### `GET /monitor/static/{version}`

Runs static analysis on demand for one version and returns its `VersionAnalysisResult`
(M1/M2, M1f/M2f, per-service metrics). Does not read from, or write to, any snapshot.

```bash
curl -s http://localhost:8090/monitor/static/v1 | jq .staticMetrics
```

---

### Stepwise collection

The three sources can be collected one by one and assembled at the end. This is what the
automated pipeline does internally.

#### `POST /monitor/collect/static`

Step 1 — static analysis across every version. Returns `List<VersionAnalysisResult>`; needs
only the git repository, not a running quizzes stack.

#### `POST /monitor/collect/locust`

Step 2a — starts a Locust run on `locust-service`. Returns `202 Accepted` with a
`LocustRunStatus`; `400` if a run is already in flight.

**Body** (optional): `{"host": "http://gateway:8080", "iterationId": "..."}`.

```bash
curl -s -X POST http://localhost:8090/monitor/collect/locust \
  -H 'Content-Type: application/json' \
  -d '{"host":"http://gateway:8080","iterationId":"'"$ITER"'"}' | jq .
```

#### `GET /monitor/collect/locust/status`

Step 2b — the current run: `status` (`IDLE | RUNNING | DONE | FAILED`), `startedAt`,
`finishedAt`, `exitCode`, the tail of the log and per-scenario `steps`.

#### `GET /monitor/collect/locust/scenarios?iterationId=...`

Step 2c — fetches the finished run's rows (`List<LocustScenarioMetrics>`) straight from
`locust-service` (`GET /api/runs/by-iteration/{iterationId}`), bypassing any snapshot.

**Example response:**
```json
[
  {
    "scenarioName": "POST /tournaments/1/join",
    "sourceFile": "concurrent_add_participant",
    "requestCount": 90,
    "failureCount": 0,
    "errorRate": 0.0,
    "medianResponseMs": 120.0,
    "avgResponseMs": 135.4,
    "minResponseMs": 85.0,
    "maxResponseMs": 980.0,
    "p95ResponseMs": 420.0,
    "p99ResponseMs": 850.0,
    "requestsPerSec": 3.0,
    "failuresPerSec": 0.0,
    "avgContentSizeBytes": 512.0
  }
]
```

#### `POST /monitor/locust/callback`

The webhook `locust-service` posts a finished run to (`LocustRunResult`). Not meant to be called
by hand; `RT_APP_BASE_URL` must be an address locust-service can reach for it to arrive.

#### `POST /monitor/collect/jaeger`

Step 3 — collects spans for a window and returns the aggregated `JaegerCollectionResult`
(`aggregateMetrics`, `sagaMetrics`, `consistencyViolationCount`, `collectedAt`,
`windowMinutes`). Does not persist anything.

**Body** (optional): either a relative window, `{"windowMinutes": 15}`, or an explicit one,
`{"windowStartMs": 1712325300000, "windowEndMs": 1712326200000}` (epoch ms, takes precedence).
With no body the server default window is used.

#### `POST /monitor/snapshot/assemble`

Assembles and persists a `MonitorSnapshot` from the step results. If `iterationId` is given and
`locustScenarios` is empty, the rows are pulled from locust-service for that iteration; the
snapshot is then linked to the iteration.

**Body:** `{"staticVersions": [...], "locustScenarios": [...], "aggregateMetrics": {...},
"sagaMetrics": {...}, "consistencyViolationCount": 0, "windowMinutes": 15, "iterationId": "..."}`.

---

## Load generation

Locust is the only load generator. It runs in the standalone `locust-service` container
(port 8100, its own Postgres): rt-app triggers a run with `POST /api/runs` and receives the
results through the `/monitor/locust/callback` webhook. No CSV files are shared or parsed by
rt-app, and there is no results directory to configure — see
[`../utils/locust/README.md`](../utils/locust/README.md).

---

## Health

```bash
curl http://localhost:8090/actuator/health
curl http://localhost:8090/claude/status      # is the mounted Claude CLI session usable?
```

---

## Other Phases

All four remaining phases are implemented. Their endpoint references live with their phase
documentation:

| Phase | Base path | Reference |
|-------|-----------|-----------|
| Analyze | `/analyze/**`, `/config/**`, `/quality/**` | [analyze.md](../../docs/refactor-tool/analyze.md#rest-api) |
| Plan | `/plan/**` | [plan.md](../../docs/refactor-tool/plan.md#rest-api) |
| Execute | `/execute/**`, `/claude/status` | [execute.md](../../docs/refactor-tool/execute.md#rest-api) |
| Knowledge | `/knowledge/**` | [knowledge.md](../../docs/refactor-tool/knowledge.md#rest-api) |
