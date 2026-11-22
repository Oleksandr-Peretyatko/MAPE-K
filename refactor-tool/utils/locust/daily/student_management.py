"""
Locust scenario: Student Management (Flow 5)

3 concurrent users each update a student's name, anonymize them, then remove them
from the course execution. Measures cross-aggregate write operations and the cost
of domain-event propagation across aggregates.

Usage (run by locust-service; by hand, from utils/locust):
    locust -f daily/student_management.py --headless -u 10 -r 2 -t 60s --host http://localhost:8080
"""

import threading
import time
from datetime import datetime, timedelta, timezone

_RUN_ID = int(time.time()) % 100000

from locust import HttpUser, events, task
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
    print(f"[SETUP] student_management: starting on host={host}")
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

        print("[SETUP] student_management: creating course execution...")
        r = _check(
            s.post(
                f"{host}/executions/create",
                json={
                    "name": "BLCM",
                    "type": "TECNICO",
                    "acronym": f"MGT{_RUN_ID}",
                    "academicTerm": "2025/2026",
                    "endDate": _isodate(365),
                },
                timeout=10,
            ),
            "create execution",
        )
        course_execution_id = r["aggregateId"]
        print(f"[SETUP] student_management: course_execution_id={course_execution_id}")

        user_ids = []
        for i in range(1, 4):
            r = _check(
                s.post(
                    f"{host}/users/create",
                    json={
                        "name": f"MgmtStudent{i}",
                        "username": f"mgmtstudent{i}",
                        "role": "STUDENT",
                    },
                    timeout=10,
                ),
                f"create user {i}",
            )
            user_ids.append(r["aggregateId"])
        for uid in user_ids:
            s.post(f"{host}/users/{uid}/activate", timeout=10)
        for uid in user_ids:
            s.post(
                f"{host}/executions/{course_execution_id}/students/add",
                params={"userAggregateId": uid},
                timeout=10,
            )

        _ctx.update({"course_execution_id": course_execution_id, "user_ids": user_ids})
        print(f"[SETUP OK] student_management: {len(user_ids)} users ready")

    except Exception as exc:
        _setup_error = exc
        print(f"[SETUP ERROR] student_management on_test_start failed: {exc}")
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


class StudentManagementUser(HttpUser):
    """
    Each instance manages a different pre-created student: name update → read back
    → anonymize → remove. One-shot: raises StopUser after the full management flow.
    """

    _slot_counter = 0
    _slot_lock = threading.Lock()

    def on_start(self):
        with StudentManagementUser._slot_lock:
            self._slot = StudentManagementUser._slot_counter % 3
            StudentManagementUser._slot_counter += 1

        if _setup_error:
            raise StopUser()

    @task
    def manage_student(self):
        if _setup_error:
            print(f"[TASK SKIP] StudentManagementUser slot={self._slot}: setup failed, stopping user")
            raise StopUser()

        print(f"[TASK START] StudentManagementUser slot={self._slot}: starting requests")
        ctx = _ctx
        eid = ctx["course_execution_id"]
        uid = ctx["user_ids"][self._slot]

        self.client.post(
            f"/executions/{eid}/students/{uid}/update/name",
            json={"name": f"UpdatedName_{self._slot}"},
            headers=HEADERS,
            name="Update Student Name",
        )

        self.client.get(
            f"/executions/{eid}",
            headers=HEADERS,
            name="Read Execution After Name Update",
        )

        self.client.post(
            f"/executions/{eid}/anonymize",
            params={"userAggregateId": uid},
            json={},
            headers=HEADERS,
            name="Anonymize Student",
        )

        self.client.post(
            f"/executions/{eid}/students/remove",
            params={"userAggregateId": uid},
            json={},
            headers=HEADERS,
            name="Remove Student",
        )

        raise StopUser()
