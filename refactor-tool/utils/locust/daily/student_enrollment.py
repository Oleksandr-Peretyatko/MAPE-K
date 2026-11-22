"""
Locust scenario: Student Registration (Flow 2)

5 concurrent users each create a student, activate them, and enroll in a shared
course execution. Measures student registration throughput under concurrent load.

Usage (run by locust-service; by hand, from utils/locust):
    locust -f daily/student_enrollment.py --headless -u 10 -r 2 -t 60s --host http://localhost:8080
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
    print(f"[SETUP] student_enrollment: starting on host={host}")
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

        print("[SETUP] student_enrollment: creating course execution...")
        r = _check(
            s.post(
                f"{host}/executions/create",
                json={
                    "name": "BLCM",
                    "type": "TECNICO",
                    "acronym": f"ENR{_RUN_ID}",
                    "academicTerm": "2025/2026",
                    "endDate": _isodate(365),
                },
                timeout=10,
            ),
            "create execution",
        )
        _ctx["course_execution_id"] = r["aggregateId"]
        _ctx["course_id"] = r["courseAggregateId"]
        print(f"[SETUP OK] student_enrollment: course_execution_id={_ctx['course_execution_id']}")

    except Exception as exc:
        _setup_error = exc
        print(f"[SETUP ERROR] student_enrollment on_test_start failed: {exc}")
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


class StudentEnrollmentUser(HttpUser):
    """
    Each instance independently creates a student, activates it, and enrolls in the
    shared execution. One-shot: raises StopUser after completing enrollment.
    """

    _slot_counter = 0
    _slot_lock = threading.Lock()

    def on_start(self):
        with StudentEnrollmentUser._slot_lock:
            self._slot = StudentEnrollmentUser._slot_counter
            StudentEnrollmentUser._slot_counter += 1

        if _setup_error:
            raise StopUser()

    @task
    def enroll_student(self):
        if _setup_error:
            print(f"[TASK SKIP] StudentEnrollmentUser slot={self._slot}: setup failed, stopping user")
            raise StopUser()

        slot = self._slot
        print(f"[TASK START] StudentEnrollmentUser slot={slot}: starting requests")
        ts = int(time.time() * 1000)
        ctx = _ctx

        r = self.client.post(
            "/users/create",
            json={
                "name": f"Student_{slot}",
                "username": f"student_{slot}_{ts}",
                "role": "STUDENT",
            },
            headers=HEADERS,
            name="Create User",
        )
        if not r.ok:
            raise StopUser()
        user_id = r.json()["aggregateId"]

        self.client.post(
            f"/users/{user_id}/activate",
            headers=HEADERS,
            name="Activate User",
        )

        self.client.post(
            f"/executions/{ctx['course_execution_id']}/students/add",
            params={"userAggregateId": user_id},
            json={},
            headers=HEADERS,
            name="Enroll Student",
        )

        self.client.get(
            f"/users/{user_id}/executions",
            headers=HEADERS,
            name="Get User Executions",
        )

        raise StopUser()
