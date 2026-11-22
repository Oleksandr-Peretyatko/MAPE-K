"""
Locust port of concurrentAddparticipant.jmx

Scenario: 3 users concurrently join the same tournament.
Verifies that the sagas/TCC concurrency control handles race conditions correctly.

Usage:
    ./run-test-concurrent-add-participant.sh [http://localhost:8080]
"""

import threading
import time
from datetime import datetime, timedelta, timezone

from locust import HttpUser, events, task
from locust.exception import StopUser

# ---------------------------------------------------------------------------
# Shared state – populated once in test_start, read by all user instances
# ---------------------------------------------------------------------------
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


# ---------------------------------------------------------------------------
# One-time setup (runs before any user is spawned)
# ---------------------------------------------------------------------------
@events.test_start.add_listener
def on_test_start(environment, **_kwargs):
    global _setup_error
    import requests

    host = environment.host.rstrip("/")
    s = requests.Session()
    s.headers.update(HEADERS)

    try:
        # --- scheduler ---
        s.get(f"{host}/scheduler/stop", timeout=10)
        s.get(f"{host}/scheduler/start", timeout=10)

        # --- fault behaviours ---
        s.post(f"{host}/behaviour/load?dir=jmeter/faults", timeout=10)
        s.get(f"{host}/behaviour/clean", timeout=10)

        # --- distributed trace ---
        s.get(f"{host}/traces/start", timeout=10)

        # --- course execution ---
        r = _check(
            s.post(
                f"{host}/executions/create",
                json={
                    "name": "BLCM",
                    "type": "TECNICO",
                    "acronym": "TESTBLCM",
                    "academicTerm": "2022/2023",
                    "endDate": "2023-09-20T15:37:58.003198Z",
                },
                timeout=10,
            ),
            "create execution",
        )
        course_execution_id = r["aggregateId"]
        course_id = r["courseAggregateId"]

        # --- users (3) ---
        user_ids = []
        for i in range(1, 4):
            r = _check(
                s.post(
                    f"{host}/users/create",
                    json={
                        "name": f"Student_Name_{i}",
                        "username": f"Student_Username_{i}",
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

        # --- topics (2) ---
        topic_ids = []
        for i in range(1, 3):
            r = _check(
                s.post(
                    f"{host}/courses/{course_id}/create",
                    json={"name": f"TOPIC{i}"},
                    timeout=10,
                ),
                f"create topic {i}",
            )
            topic_ids.append(r["aggregateId"])

        # --- questions (2, one per topic) ---
        for i in range(1, 3):
            s.post(
                f"{host}/courses/{course_id}/questions/create",
                json={
                    "name": f"TOPIC{i}",
                    "title": f"question title {i}",
                    "content": f"question content {i}",
                    "topicDto": [
                        {
                            "aggregateId": topic_ids[i - 1],
                            "courseId": course_id,
                        }
                    ],
                    "optionDtos": [
                        {"sequence": 1, "correct": "true", "content": "Option content 1"},
                        {"sequence": 2, "correct": "false", "content": "Option content 2"},
                    ],
                },
                timeout=10,
            )

        # --- tournament ---
        r = _check(
            s.post(
                f"{host}/executions/{course_execution_id}/tournaments/create",
                params={
                    "userId": user_ids[0],
                    "topicsId": topic_ids,
                },
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
                "user_ids": user_ids,
                "topic_ids": topic_ids,
                "tournament_id": r["aggregateId"],
            }
        )

    except Exception as exc:
        _setup_error = exc
        environment.runner.quit()
        raise


# ---------------------------------------------------------------------------
# Verify + cleanup (runs after all users are done)
# ---------------------------------------------------------------------------
@events.test_stop.add_listener
def on_test_stop(environment, **_kwargs):
    if _setup_error:
        return
    import requests

    host = environment.host.rstrip("/")
    s = requests.Session()
    s.headers.update(HEADERS)

    r = s.get(f"{host}/tournaments/get", timeout=10)
    if r.ok:
        print(f"[verify] GET /tournaments/get → {r.status_code}: {r.text[:300]}")
    else:
        print(f"[verify] GET /tournaments/get FAILED: {r.status_code} {r.text[:200]}")

    s.get(f"{host}/traces/end", timeout=10)
    s.get(f"{host}/traces/flush", timeout=10)


# ---------------------------------------------------------------------------
# User class – 3 instances, each joins as a different participant
# ---------------------------------------------------------------------------
class AddParticipantUser(HttpUser):
    """
    Each instance picks one of the 3 user slots, waits 1 s (mirrors the
    JMeter ConstantTimer), POSTs the join request once, then stops.
    """

    _slot_counter = 0
    _slot_lock = threading.Lock()

    def on_start(self):
        with AddParticipantUser._slot_lock:
            self._slot = AddParticipantUser._slot_counter % 3
            AddParticipantUser._slot_counter += 1

        if _setup_error:
            raise StopUser()

    @task
    def join_tournament(self):
        if _setup_error:
            raise StopUser()

        ctx = _ctx
        user_id = ctx["user_ids"][self._slot]

        # mirror JMeter 1 000 ms ConstantTimer
        time.sleep(1)

        self.client.post(
            f"/tournaments/{ctx['tournament_id']}/join",
            params={
                "executionAggregateId": ctx["course_execution_id"],
                "userAggregateId": user_id,
            },
            json={},
            headers=HEADERS,
            name="Add participant",
        )

        raise StopUser()
