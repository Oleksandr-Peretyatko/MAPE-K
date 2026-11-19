from __future__ import annotations

from typing import List, Optional
from pydantic import BaseModel, Field

DEFAULT_SCENARIOS = [
    "teacher_setup",
    "student_enrollment",
    "tournament_lifecycle",
    "student_management",
    "read_operations",
]


class RunRequest(BaseModel):
    iteration_id: str
    target_host: str
    scenarios: List[str] = Field(default_factory=lambda: list(DEFAULT_SCENARIOS))
    callback_url: Optional[str] = None


class EndpointMetric(BaseModel):
    """One non-Aggregated row from a Locust *_stats.csv."""
    endpoint_name: str
    request_count: int
    failure_count: int
    error_rate: float
    median_ms: float
    p95_ms: float
    p99_ms: float
    max_pct_ms: float
    avg_ms: float
    min_ms: float
    max_ms: float
    rps: float
    failures_per_sec: float
    avg_content_size_bytes: float


class ScenarioResult(BaseModel):
    """All endpoint metrics collected during one locust scenario run."""
    scenario_name: str
    metrics: List[EndpointMetric] = Field(default_factory=list)


class RunResult(BaseModel):
    run_id: str
    iteration_id: str
    status: str  # PENDING | RUNNING | DONE | FAILED
    scenarios: List[ScenarioResult] = Field(default_factory=list)
    error: Optional[str] = None
    started_at: Optional[str] = None
    completed_at: Optional[str] = None


class StartRunResponse(BaseModel):
    run_id: str
    iteration_id: str
    status: str
