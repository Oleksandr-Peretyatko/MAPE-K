from __future__ import annotations

import asyncio
import csv
import logging
import os
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

import httpx
from sqlalchemy import select

from .db.models import LocustMetric, LocustRun
from .db.session import AsyncSessionLocal
from .schemas import EndpointMetric, RunRequest, RunResult, ScenarioResult

log = logging.getLogger(__name__)

_SCENARIOS_DIR = Path(__file__).parent.parent

SCENARIO_CONFIG: dict = {
    "teacher_setup": {
        "file": _SCENARIOS_DIR / "daily" / "teacher_setup.py",
        "users": 1, "spawn_rate": 1, "run_time": "30s",
    },
    "student_enrollment": {
        "file": _SCENARIOS_DIR / "daily" / "student_enrollment.py",
        "users": 5, "spawn_rate": 5, "run_time": "30s",
    },
    "tournament_lifecycle": {
        "file": _SCENARIOS_DIR / "daily" / "tournament_lifecycle.py",
        "users": 3, "spawn_rate": 3, "run_time": "60s",
    },
    "student_management": {
        "file": _SCENARIOS_DIR / "daily" / "student_management.py",
        "users": 3, "spawn_rate": 3, "run_time": "30s",
    },
    "read_operations": {
        "file": _SCENARIOS_DIR / "daily" / "read_operations.py",
        "users": 5, "spawn_rate": 5, "run_time": "60s",
    },
    "concurrent_add_participant": {
        "file": _SCENARIOS_DIR / "tournament" / "concurrent_add_participant.py",
        "users": 3, "spawn_rate": 3, "run_time": "30s",
    },
}

_CALLBACK_RETRIES = 3


# ─── CSV helpers ──────────────────────────────────────────────────────────────

def _float_val(raw: Optional[str]) -> float:
    if not raw:
        return 0.0
    raw = raw.strip()
    if raw in ("", "N/A"):
        return 0.0
    try:
        return float(raw)
    except ValueError:
        return 0.0


def _int_val(raw: Optional[str]) -> int:
    return int(_float_val(raw))


def _parse_stats_csv(csv_path: Path, scenario_name: str) -> List[EndpointMetric]:
    """Return one EndpointMetric per non-Aggregated row in *_stats.csv."""
    if not csv_path.exists():
        log.warning("[runner] CSV not found: %s", csv_path)
        return []

    metrics: List[EndpointMetric] = []
    with open(csv_path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            name = (row.get("Name") or "").strip()
            if not name or name.lower() == "aggregated":
                continue
            req = _int_val(row.get("Request Count"))
            fail = _int_val(row.get("Failure Count"))
            metrics.append(EndpointMetric(
                endpoint_name=name,
                request_count=req,
                failure_count=fail,
                error_rate=fail / req if req > 0 else 0.0,
                median_ms=_float_val(row.get("Median Response Time")),
                p95_ms=_float_val(row.get("95%")),
                p99_ms=_float_val(row.get("99%")),
                max_pct_ms=_float_val(row.get("100%")),
                avg_ms=_float_val(row.get("Average Response Time")),
                min_ms=_float_val(row.get("Min Response Time")),
                max_ms=_float_val(row.get("Max Response Time")),
                rps=_float_val(row.get("Requests/s")),
                failures_per_sec=_float_val(row.get("Failures/s")),
                avg_content_size_bytes=_float_val(row.get("Average Content Size")),
            ))
    return metrics


# ─── Scenario execution ───────────────────────────────────────────────────────

async def _run_scenario(
    run_id: str,
    scenario_name: str,
    cfg: dict,
    target_host: str,
    tmp_dir: Path,
) -> List[EndpointMetric]:
    scenario_out = tmp_dir / scenario_name
    scenario_out.mkdir(parents=True, exist_ok=True)
    csv_prefix = str(scenario_out / scenario_name)

    cmd = [
        "python3", "-B", "-m", "locust",
        "-f", str(cfg["file"]),
        "--headless",
        "-u", str(cfg["users"]),
        "-r", str(cfg["spawn_rate"]),
        "--run-time", cfg["run_time"],
        "--csv", csv_prefix,
        "--host", target_host,
        "--reset-stats",
    ]

    log.info("[runner][%s] starting %s: users=%d run_time=%s host=%s",
             run_id, scenario_name, cfg["users"], cfg["run_time"], target_host)

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env={**os.environ, "PYTHONUNBUFFERED": "1"},
    )

    timeout_s = 300  # 5-minute hard cap per scenario
    try:
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.communicate()
        log.error("[runner][%s] %s timed out after %ds", run_id, scenario_name, timeout_s)
        return []

    if stdout:
        for line in stdout.decode(errors="replace").splitlines():
            log.info("[locust][%s][%s] %s", run_id, scenario_name, line)

    if proc.returncode != 0:
        log.warning("[runner][%s] %s exited with code %d", run_id, scenario_name, proc.returncode)

    return _parse_stats_csv(Path(csv_prefix + "_stats.csv"), scenario_name)


# ─── Callback delivery ────────────────────────────────────────────────────────

async def _post_callback(callback_url: str, result: RunResult) -> None:
    for attempt in range(_CALLBACK_RETRIES):
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.post(callback_url, json=result.model_dump())
                if resp.status_code < 300:
                    log.info("[callback] delivered to %s (status=%d)", callback_url, resp.status_code)
                    return
                log.warning("[callback] %s returned %d (attempt %d/%d)",
                            callback_url, resp.status_code, attempt + 1, _CALLBACK_RETRIES)
        except Exception as exc:
            log.warning("[callback] attempt %d/%d failed: %s", attempt + 1, _CALLBACK_RETRIES, exc)
        if attempt < _CALLBACK_RETRIES - 1:
            await asyncio.sleep(2 ** attempt)  # 1s, 2s backoff
    log.error("[callback] gave up after %d attempts for %s", _CALLBACK_RETRIES, callback_url)


# ─── Main execution coroutine ─────────────────────────────────────────────────

async def execute_run(run_id: str, request: RunRequest) -> None:
    """Background coroutine that runs all requested scenarios sequentially."""
    tmp_dir = Path(f"/tmp/locust-{run_id}")
    tmp_dir.mkdir(parents=True, exist_ok=True)

    all_scenario_results: List[ScenarioResult] = []
    error_msg: Optional[str] = None
    final_status = "DONE"

    try:
        async with AsyncSessionLocal() as session:
            run = await session.get(LocustRun, run_id)
            if run is None:
                log.error("[runner] run %s not found in DB; aborting", run_id)
                return
            run.started_at = datetime.now(timezone.utc)
            run.status = "RUNNING"
            await session.commit()

        for scenario_name in request.scenarios:
            cfg = SCENARIO_CONFIG.get(scenario_name)
            if cfg is None:
                log.warning("[runner][%s] unknown scenario '%s', skipping", run_id, scenario_name)
                continue

            metrics = await _run_scenario(run_id, scenario_name, cfg, request.target_host, tmp_dir)
            sr = ScenarioResult(scenario_name=scenario_name, metrics=metrics)
            all_scenario_results.append(sr)

    except Exception as exc:
        error_msg = str(exc)
        final_status = "FAILED"
        log.exception("[runner][%s] run failed: %s", run_id, exc)

    finally:
        completed_at = datetime.now(timezone.utc)

        async with AsyncSessionLocal() as session:
            run = await session.get(LocustRun, run_id)
            if run:
                run.status = final_status
                run.error_message = error_msg
                run.completed_at = completed_at
                for sr in all_scenario_results:
                    for m in sr.metrics:
                        session.add(LocustMetric(
                            id=str(uuid.uuid4()),
                            run_id=run_id,
                            scenario_name=sr.scenario_name,
                            endpoint_name=m.endpoint_name,
                            request_count=m.request_count,
                            failure_count=m.failure_count,
                            error_rate=m.error_rate,
                            median_ms=m.median_ms,
                            p95_ms=m.p95_ms,
                            p99_ms=m.p99_ms,
                            max_pct_ms=m.max_pct_ms,
                            avg_ms=m.avg_ms,
                            min_ms=m.min_ms,
                            max_ms=m.max_ms,
                            rps=m.rps,
                            failures_per_sec=m.failures_per_sec,
                            avg_content_size_bytes=m.avg_content_size_bytes,
                        ))
                await session.commit()

        run_result = RunResult(
            run_id=run_id,
            iteration_id=request.iteration_id,
            status=final_status,
            scenarios=all_scenario_results,
            error=error_msg,
            started_at=None,
            completed_at=completed_at.isoformat(),
        )

        if request.callback_url:
            await _post_callback(request.callback_url, run_result)

        shutil.rmtree(tmp_dir, ignore_errors=True)


# ─── DB query helpers used by routes ─────────────────────────────────────────

async def get_run_result(run_id: str) -> Optional[RunResult]:
    async with AsyncSessionLocal() as session:
        run = await session.get(LocustRun, run_id)
        if run is None:
            return None
        stmt = select(LocustMetric).where(LocustMetric.run_id == run_id)
        rows = (await session.execute(stmt)).scalars().all()

    scenario_map: dict[str, List[EndpointMetric]] = {}
    for row in rows:
        scenario_map.setdefault(row.scenario_name, []).append(EndpointMetric(
            endpoint_name=row.endpoint_name,
            request_count=row.request_count,
            failure_count=row.failure_count,
            error_rate=row.error_rate,
            median_ms=row.median_ms,
            p95_ms=row.p95_ms,
            p99_ms=row.p99_ms,
            max_pct_ms=row.max_pct_ms,
            avg_ms=row.avg_ms,
            min_ms=row.min_ms,
            max_ms=row.max_ms,
            rps=row.rps,
            failures_per_sec=row.failures_per_sec,
            avg_content_size_bytes=row.avg_content_size_bytes,
        ))

    return RunResult(
        run_id=run.run_id,
        iteration_id=run.iteration_id,
        status=run.status,
        scenarios=[ScenarioResult(scenario_name=k, metrics=v) for k, v in scenario_map.items()],
        error=run.error_message,
        started_at=run.started_at.isoformat() if run.started_at else None,
        completed_at=run.completed_at.isoformat() if run.completed_at else None,
    )


async def get_run_result_by_iteration(iteration_id: str) -> Optional[RunResult]:
    """Return the most recent run for a given iteration_id."""
    async with AsyncSessionLocal() as session:
        stmt = (
            select(LocustRun)
            .where(LocustRun.iteration_id == iteration_id)
            .order_by(LocustRun.started_at.desc().nullslast())
            .limit(1)
        )
        run = (await session.execute(stmt)).scalar_one_or_none()
        if run is None:
            return None
        run_id = run.run_id

    return await get_run_result(run_id)
