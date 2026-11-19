from __future__ import annotations

import asyncio
import logging
import uuid

from fastapi import APIRouter, HTTPException

from .db.models import LocustRun
from .db.session import AsyncSessionLocal
from .runner import execute_run, get_run_result, get_run_result_by_iteration
from .schemas import RunRequest, RunResult, StartRunResponse

log = logging.getLogger(__name__)
router = APIRouter()


@router.post("/api/runs", response_model=StartRunResponse, status_code=202)
async def start_run(request: RunRequest):
    """Trigger a new locust load test run."""
    run_id = str(uuid.uuid4())

    async with AsyncSessionLocal() as session:
        session.add(LocustRun(
            run_id=run_id,
            iteration_id=request.iteration_id,
            target_host=request.target_host,
            status="PENDING",
            callback_url=request.callback_url,
        ))
        await session.commit()

    asyncio.create_task(execute_run(run_id, request))

    log.info("[routes] started run %s (iteration=%s, host=%s, scenarios=%s)",
             run_id, request.iteration_id, request.target_host, request.scenarios)

    return StartRunResponse(
        run_id=run_id,
        iteration_id=request.iteration_id,
        status="PENDING",
    )


@router.get("/api/runs/{run_id}", response_model=RunResult)
async def get_run(run_id: str):
    """Get current status and results of a run."""
    result = await get_run_result(run_id)
    if result is None:
        raise HTTPException(status_code=404, detail=f"Run {run_id!r} not found")
    return result


@router.get("/api/runs/by-iteration/{iteration_id}", response_model=RunResult)
async def get_run_by_iteration(iteration_id: str):
    """Get the most recent run for a given iteration UUID."""
    result = await get_run_result_by_iteration(iteration_id)
    if result is None:
        raise HTTPException(
            status_code=404,
            detail=f"No run found for iteration {iteration_id!r}",
        )
    return result


@router.get("/health")
async def health():
    return {"status": "ok"}
