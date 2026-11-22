from __future__ import annotations

from sqlalchemy import Column, Float, ForeignKey, Integer, String, Text, DateTime
from sqlalchemy.orm import DeclarativeBase, relationship


class Base(DeclarativeBase):
    pass


class LocustRun(Base):
    __tablename__ = "locust_run"

    run_id = Column(String, primary_key=True)
    iteration_id = Column(String, nullable=False, index=True)
    target_host = Column(String, nullable=False)
    status = Column(String, nullable=False, default="PENDING")
    callback_url = Column(String, nullable=True)
    error_message = Column(Text, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)

    metrics = relationship(
        "LocustMetric", back_populates="run", cascade="all, delete-orphan"
    )


class LocustMetric(Base):
    """One endpoint-level metric row per locust scenario run."""
    __tablename__ = "locust_metric"

    id = Column(String, primary_key=True)
    run_id = Column(String, ForeignKey("locust_run.run_id"), nullable=False, index=True)
    scenario_name = Column(String, nullable=False)   # e.g. "teacher_setup"
    endpoint_name = Column(String, nullable=False)   # e.g. "/executions/create"
    request_count = Column(Integer, default=0)
    failure_count = Column(Integer, default=0)
    error_rate = Column(Float, default=0.0)
    median_ms = Column(Float, default=0.0)
    p95_ms = Column(Float, default=0.0)
    p99_ms = Column(Float, default=0.0)
    max_pct_ms = Column(Float, default=0.0)
    avg_ms = Column(Float, default=0.0)
    min_ms = Column(Float, default=0.0)
    max_ms = Column(Float, default=0.0)
    rps = Column(Float, default=0.0)
    failures_per_sec = Column(Float, default=0.0)
    avg_content_size_bytes = Column(Float, default=0.0)

    run = relationship("LocustRun", back_populates="metrics")
