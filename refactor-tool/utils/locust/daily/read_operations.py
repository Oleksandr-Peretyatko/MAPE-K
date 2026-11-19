"""
Locust scenario: Tournament Discovery — Read Operations (Flow 3)

5 users continuously browse tournaments, questions, and topics.
Measures read-path performance under sustained query load, isolating
GET endpoints from the write-heavy scenarios.

Usage (run by locust-service; by hand, from utils/locust):
    locust -f daily/read_operations.py --headless -u 10 -r 2 -t 60s --host http://localhost:8080
"""

import threading
import time
from datetime import datetime, timedelta, timezone

_RUN_ID = int(time.time()) % 100000

from locust import HttpUser, between, events, task
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
    print(f"[SETUP] read_operations: starting on host={host}")
    s = requests.Session()
    s.headers.update(HEADERS)

    try:
        for path in ("/scheduler/stop", "/scheduler/start", "/traces/start"):
            try:
                s.get(f"{host}{path}", timeout=10)
                print(f"[SETUP] {path} OK")
            except Exception as infra_exc:
                print(f"[SETUP WARN] {path} failed (non-fatal): {infra_exc}")

        r = _check(
            s.post(
                f"{host}/executions/create",
                json={
                    "name": "BLCM",
                    "type": "TECNICO",
                    "acronym": f"RDS{_RUN_ID}",
                    "academicTerm": "2025/2026",
                    "endDate": _isodate(365),
                },
                timeout=10,
            ),
            "create execution",
        )
        course_execution_id = r["aggregateId"]
        course_id = r["courseAggregateId"]

        topic_ids = []
        for i in range(1, 3):
            r = _check(
                s.post(
                    f"{host}/courses/{course_id}/create",
                    json={"name": f"ReadTopic{i}"},
                    timeout=10,
                ),
                f"create topic {i}",
            )
            topic_ids.append(r["aggregateId"])

        for i in range(1, 3):
            s.post(
                f"{host}/courses/{course_id}/questions/create",
                json={
                    "name": f"ReadTopic{i}",
                    "title": f"Read question {i}",
                    "content": f"Read question content {i}",
                    "topicDto": [{"aggregateId": topic_ids[i - 1], "courseId": course_id}],
                    "optionDtos": [
                        {"sequence": 1, "correct": "true", "content": "Option A"},
                        {"sequence": 2, "correct": "false", "content": "Option B"},
                    ],
                },
                timeout=10,
            )

        user_ids = []
        for i in range(1, 3):
            r = _check(
                s.post(
                    f"{host}/users/create",
                    json={
                        "name": f"ReadStudent{i}",
                        "username": f"readstudent{i}",
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

        r = _check(
            s.post(
                f"{host}/executions/{course_execution_id}/tournaments/create",
                params={"userId": user_ids[0], "topicsId": topic_ids},
                json={
                    "startTime": _isodate(1),
                    "endTime": _isodate(2),
                    "numberOfQuestions": 2,
                },
                timeout=10,
            ),
            "create tournament",
        )

        _ctx.update(
            {
                "course_execution_id": course_execution_id,
                "course_id": course_id,
                "tournament_id": r["aggregateId"],
            }
        )
        print(f"[SETUP OK] read_operations: course_execution_id={course_execution_id}, tournament={r['aggregateId']}")

    except Exception as exc:
        _setup_error = exc
        print(f"[SETUP ERROR] read_operations on_test_start failed: {exc}")
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


class ReadOperationsUser(HttpUser):
    """
    Continuously browses course content: tournaments, questions, topics.
    Repeating task with 1–3 s wait to model realistic student browsing behaviour.
    """

    wait_time = between(1, 3)

    def on_start(self):
        if _setup_error:
            raise StopUser()

    @task
    def browse_course(self):
        if _setup_error:
            print(f"[TASK SKIP] ReadOperationsUser: setup failed, stopping user")
            raise StopUser()

        print(f"[TASK START] ReadOperationsUser: starting requests")
        ctx = _ctx
        eid = ctx["course_execution_id"]
        cid = ctx["course_id"]
        tid = ctx["tournament_id"]

        self.client.get(
            f"/executions/{eid}/tournaments/opened",
            headers=HEADERS,
            name="List Open Tournaments",
        )
        self.client.get(
            f"/executions/{eid}/tournaments/closed",
            headers=HEADERS,
            name="List Closed Tournaments",
        )
        self.client.get(
            f"/tournaments/{tid}",
            headers=HEADERS,
            name="Get Tournament Detail",
        )
        self.client.get(
            f"/courses/{cid}/questions",
            headers=HEADERS,
            name="List Questions",
        )
        self.client.get(
            f"/courses/{cid}/topics",
            headers=HEADERS,
            name="List Topics",
        )
