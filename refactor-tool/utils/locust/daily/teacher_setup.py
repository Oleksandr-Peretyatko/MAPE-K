"""
Locust scenario: Teacher Course Setup (Flow 1)

A single user creates a course execution, 3 topics, and 5 questions.
Measures the cost of foundational content-creation operations.

Usage (run by locust-service; by hand, from utils/locust):
    locust -f daily/teacher_setup.py --headless -u 10 -r 2 -t 60s --host http://localhost:8080
"""

import threading
import time
from datetime import datetime, timedelta, timezone

from locust import HttpUser, events, task

_RUN_ID = int(time.time()) % 100000
from locust.exception import StopUser

_ctx: dict = {}
_setup_error: Exception | None = None

HEADERS = {
    "Content-Type": "application/json",
    "Accept-Language": "en,pt-PT;q=0.8,pt;q=0.5,en-US;q=0.3",
}


def _isodate(days_offset: int) -> str:
    dt = datetime.now(timezone.utc) + timedelta(days=days_offset)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _check(response, label: str) -> dict:
    if not response.ok:
        raise RuntimeError(
            f"[setup] {label} failed: {response.status_code} {response.text[:200]}"
        )
    return response.json()


@events.test_start.add_listener
def on_test_start(environment, **_kwargs):
    global _setup_error
    import traceback
    import requests

    host = environment.host.rstrip("/")
    print(f"[SETUP] teacher_setup: starting on host={host}")
    s = requests.Session()
    s.headers.update(HEADERS)

    try:
        for path in ("/scheduler/stop", "/scheduler/start", "/behaviour/clean", "/traces/start"):
            try:
                s.get(f"{host}{path}", timeout=10)
                print(f"[SETUP] {path} OK")
            except Exception as infra_exc:
                print(f"[SETUP WARN] {path} failed (non-fatal): {infra_exc}")
        try:
            s.post(f"{host}/behaviour/load?dir=jmeter/faults", timeout=10)
        except Exception as infra_exc:
            print(f"[SETUP WARN] /behaviour/load failed (non-fatal): {infra_exc}")
        print("[SETUP OK] teacher_setup: infrastructure setup done — users will spawn now")
    except Exception as exc:
        _setup_error = exc
        print(f"[SETUP ERROR] teacher_setup on_test_start failed: {exc}")
        traceback.print_exc()
        environment.runner.quit()
        raise


@events.test_stop.add_listener
def on_test_stop(environment, **_kwargs):
    if _setup_error:
        return
    import requests

    host = environment.host.rstrip("/")
    s = requests.Session()
    s.headers.update(HEADERS)
    s.get(f"{host}/traces/end", timeout=10)
    s.get(f"{host}/traces/flush", timeout=10)


class TeacherSetupUser(HttpUser):
    """
    Creates a course execution, 3 topics, and 5 questions.
    One-shot: raises StopUser after completing the full content-creation flow.
    """

    _slot_counter = 0
    _slot_lock = threading.Lock()

    def on_start(self):
        with TeacherSetupUser._slot_lock:
            self._slot = TeacherSetupUser._slot_counter
            TeacherSetupUser._slot_counter += 1

        if _setup_error:
            raise StopUser()

    @task
    def setup_content(self):
        if _setup_error:
            print(f"[TASK SKIP] TeacherSetupUser slot={self._slot}: setup failed, stopping user")
            raise StopUser()

        slot = self._slot
        print(f"[TASK START] TeacherSetupUser slot={slot}: starting requests")

        r = self.client.post(
            "/executions/create",
            json={
                "name": "BLCM",
                "type": "TECNICO",
                "acronym": f"T{_RUN_ID}_{slot}",
                "academicTerm": "2025/2026",
                "endDate": _isodate(365),
            },
            headers=HEADERS,
            name="Create Execution",
        )
        if not r.ok:
            raise StopUser()
        execution = r.json()
        course_execution_id = execution["aggregateId"]
        course_id = execution["courseAggregateId"]

        topic_ids = []
        for i in range(1, 4):
            r = self.client.post(
                f"/courses/{course_id}/create",
                json={"name": f"Topic{slot}_{i}"},
                headers=HEADERS,
                name="Create Topic",
            )
            if r.ok:
                topic_ids.append(r.json()["aggregateId"])

        for i in range(1, 6):
            topic_idx = (i - 1) % len(topic_ids) if topic_ids else 0
            self.client.post(
                f"/courses/{course_id}/questions/create",
                json={
                    "name": f"Topic{slot}_{topic_idx + 1}",
                    "title": f"Question title {slot}_{i}",
                    "content": f"Question content {slot}_{i}",
                    "topicDto": [
                        {
                            "aggregateId": topic_ids[topic_idx],
                            "courseId": course_id,
                        }
                    ] if topic_ids else [],
                    "optionDtos": [
                        {"sequence": 1, "correct": "true", "content": "Option A"},
                        {"sequence": 2, "correct": "false", "content": "Option B"},
                        {"sequence": 3, "correct": "false", "content": "Option C"},
                        {"sequence": 4, "correct": "false", "content": "Option D"},
                    ],
                },
                headers=HEADERS,
                name="Create Question",
            )

        raise StopUser()
