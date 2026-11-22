# Locust Service

Standalone load-test service for the Quizzes application, built on [Locust](https://locust.io/) and [FastAPI](https://fastapi.tiangolo.com/).

Previously, Locust ran as a subprocess inside `rt-app` and communicated via CSV files on a shared Docker volume.  
The service is now **its own Docker container** with a small REST API and its own PostgreSQL database. Nothing
outside this container reads Locust's CSV output: the runner parses each scenario's `*_stats.csv` in a temporary
directory and stores the rows in `locust-db`.

---

## Architecture

```
rt-app    ─── POST /api/runs {iteration_id, target_host, ...} ──► locust-service (8100)
          ◄── 202 {run_id}                                            │
                                                              runs scenarios
                                                              stores in locust-db
                                                                      │
rt-app    ◄── POST /monitor/locust/callback (webhook) ────────────────┘
rt-app    ─── GET /api/runs/{run_id} (polling) ────────────────► locust-service
```

- **rt-app → locust-service**: REST trigger, then polling of the run status
- **locust-service → rt-app**: HTTP webhook callback with full results (3 delivery attempts)
- **locust-service → locust-db**: PostgreSQL persistence (results survive restarts)

The frontend never talks to locust-service directly; it polls rt-app (`GET /monitor/pipeline/{snapshotId}/status`).

---

## REST API

### Start a run
```
POST /api/runs
Content-Type: application/json

{
  "iteration_id": "uuid-from-rt-app",
  "target_host": "http://quizzes-local:8080",
  "scenarios": ["teacher_setup", "student_enrollment", "tournament_lifecycle",
                 "student_management", "read_operations"],
  "callback_url": "http://rt-app:8090/monitor/locust/callback"
}
```

`scenarios` is optional and defaults to the five `daily/` scenarios (`DEFAULT_SCENARIOS` in
`api/schemas.py`); `callback_url` is optional — without it no webhook is sent and the caller polls.

Response `202`:
```json
{
  "run_id": "generated-uuid",
  "iteration_id": "...",
  "status": "PENDING"
}
```

### Get run status / results
```
GET /api/runs/{run_id}
GET /api/runs/by-iteration/{iteration_id}     → most recent run for that iteration
```

Both return a `RunResult`: `status` (`PENDING | RUNNING | DONE | FAILED`), `scenarios[]` (each with
its `metrics[]` endpoint rows), `error`, `started_at`, `completed_at`. `404` when unknown.

### Health check
```
GET /health                         → {"status": "ok"}
```

That is the whole API (`api/routes.py`). There is no WebSocket progress stream, no scenario listing
and no target-reachability probe; the scenario catalogue is the `SCENARIO_CONFIG` dict in
`api/runner.py`, mirrored in the table below.

---

## Scenario configuration

| Scenario | Users | Spawn rate | Run time | File |
|---|---|---|---|---|
| `teacher_setup` | 1 | 1 | 30s | `daily/teacher_setup.py` |
| `student_enrollment` | 5 | 5 | 30s | `daily/student_enrollment.py` |
| `tournament_lifecycle` | 3 | 3 | 60s | `daily/tournament_lifecycle.py` |
| `student_management` | 3 | 3 | 30s | `daily/student_management.py` |
| `read_operations` | 5 | 5 | 60s | `daily/read_operations.py` |
| `concurrent_add_participant` | 3 | 3 | 30s | `tournament/concurrent_add_participant.py` |

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `postgresql+asyncpg://locust:locust@localhost:5432/locustdb` | SQLAlchemy async URL for locust-db |

That is the only variable the service reads. The callback address comes entirely from the
caller's `callback_url` (rt-app builds it from its own `RT_APP_BASE_URL`).

---

## Running with Docker Compose

```bash
cd refactor-tool/

# Build and start the locust stack
docker compose up locust-db locust-service --build

# Connectivity smoke test (from host)
curl "http://localhost:8100/health"

# Trigger a single scenario run
curl -X POST http://localhost:8100/api/runs \
  -H "Content-Type: application/json" \
  -d '{
    "iteration_id": "test-001",
    "target_host": "http://quizzes-local:8080",
    "scenarios": ["teacher_setup"],
    "callback_url": "http://rt-app:8090/monitor/locust/callback"
  }'

# Poll status
curl http://localhost:8100/api/runs/<run_id>
```

---

## Running locally (outside Docker)

```bash
cd refactor-tool/utils/locust
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
DATABASE_URL=postgresql+asyncpg://locust:locust@localhost:5432/locustdb \
  uvicorn api.main:app --reload --port 8100
```

There are no helper shell scripts any more (`collect.sh`, `consolidate-logs.sh` and the per-scenario
`run-*.sh` files are gone, as is the old top-level `locustfile.py`). For an ad-hoc headless run
against a locally running application, call Locust directly on a scenario file:

```bash
locust -f daily/teacher_setup.py --headless --host http://localhost:8080 \
       --users 1 --spawn-rate 1 --run-time 30s
```

---

## Iteration ID flow

1. Frontend creates an iteration via `POST /knowledge/iterations` → receives `iterationId`
2. Frontend calls `POST /monitor/pipeline/start` with `{"iterationId": "...", "host": "..."}`
3. rt-app (`MonitorPipelineService`) calls `POST locust-service:8100/api/runs` with `iteration_id` + `callback_url`
4. locust-service runs all scenarios, stores metrics in locust-db tagged with `iteration_id`
5. locust-service POSTs results to `rt-app:8090/monitor/locust/callback`
6. rt-app fetches the metrics from locust-service (`GET /api/runs/by-iteration/{id}`) and writes them into the pipeline snapshot
7. The snapshot is linked to the iteration in the knowledge base
