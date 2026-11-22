"""
Locust scenario: Full Tournament Lifecycle (Flows 3 + 4)

3 students create and join a tournament, solve the quiz, and view results.
Measures the end-to-end tournament flow spanning 5 aggregates:
CourseExecution, Tournament, Quiz, QuizAnswer, User.

Setup creates the tournament with startTime = now+20s so students can still
join, then sleeps until the window opens before the task phase begins.

Usage (run by locust-service; by hand, from utils/locust):
    locust -f daily/tournament_lifecycle.py --headless -u 10 -r 2 -t 60s --host http://localhost:8080
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

# Seconds from now when the tournament starts; must be long enough
# for the setup requests above to complete before the window closes.
_START_OFFSET_SECONDS = 20


def _isodate(days_offset: int = 0, seconds_offset: int = 0) -> str:
    dt = datetime.now(timezone.utc) + timedelta(days=days_offset, seconds=seconds_offset)
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
    print(f"[SETUP] tournament_lifecycle: starting on host={host}")
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

        # --- course execution ---
        r = _check(
            s.post(
                f"{host}/executions/create",
                json={
                    "name": "BLCM",
                    "type": "TECNICO",
                    "acronym": f"TUR{_RUN_ID}",
                    "academicTerm": "2025/2026",
                    "endDate": _isodate(days_offset=365),
                },
                timeout=10,
            ),
            "create execution",
        )
        course_execution_id = r["aggregateId"]
        course_id = r["courseAggregateId"]

        # --- topics (3) ---
        topic_ids = []
        for i in range(1, 4):
            r = _check(
                s.post(
                    f"{host}/courses/{course_id}/create",
                    json={"name": f"TourTopic{i}"},
                    timeout=10,
                ),
                f"create topic {i}",
            )
            topic_ids.append(r["aggregateId"])

        # --- questions (5, distributed across topics) ---
        for i in range(1, 6):
            s.post(
                f"{host}/courses/{course_id}/questions/create",
                json={
                    "name": f"TourTopic{((i - 1) % 3) + 1}",
                    "title": f"Tour question {i}",
                    "content": f"Tour question content {i}",
                    "topicDto": [
                        {
                            "aggregateId": topic_ids[(i - 1) % 3],
                            "courseId": course_id,
                        }
                    ],
                    "optionDtos": [
                        {"sequence": 1, "correct": "true", "content": "Option A"},
                        {"sequence": 2, "correct": "false", "content": "Option B"},
                        {"sequence": 3, "correct": "false", "content": "Option C"},
                        {"sequence": 4, "correct": "false", "content": "Option D"},
                    ],
                },
                timeout=10,
            )

        # --- students (3) ---
        user_ids = []
        for i in range(1, 4):
            r = _check(
                s.post(
                    f"{host}/users/create",
                    json={
                        "name": f"TourStudent{i}",
                        "username": f"tourstudent{i}",
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

        # --- tournament: startTime = now + _START_OFFSET_SECONDS ---
        r = _check(
            s.post(
                f"{host}/executions/{course_execution_id}/tournaments/create",
                params={"userId": user_ids[0], "topicsId": topic_ids},
                json={
                    "startTime": _isodate(seconds_offset=_START_OFFSET_SECONDS),
                    "endTime": _isodate(days_offset=1),
                    "numberOfQuestions": 2,
                },
                timeout=10,
            ),
            "create tournament",
        )
        tournament_id = r["aggregateId"]

        # --- all 3 students join before startTime ---
        for uid in user_ids:
            s.post(
                f"{host}/tournaments/{tournament_id}/join",
                params={
                    "executionAggregateId": course_execution_id,
                    "userAggregateId": uid,
                },
                json={},
                timeout=10,
            )

        # Wait until the quiz window opens (5 s margin past startTime)
        time.sleep(_START_OFFSET_SECONDS + 5)

        _ctx.update(
            {
                "course_execution_id": course_execution_id,
                "course_id": course_id,
                "user_ids": user_ids,
                "tournament_id": tournament_id,
            }
        )
        print(f"[SETUP OK] tournament_lifecycle: tournament_id={tournament_id}, users={user_ids}")

    except Exception as exc:
        _setup_error = exc
        print(f"[SETUP ERROR] tournament_lifecycle on_test_start failed: {exc}")
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


class TournamentLifecycleUser(HttpUser):
    """
    Each instance discovers open tournaments, solves the quiz, and views results.
    One-shot: raises StopUser after the full lifecycle flow.
    """

    _slot_counter = 0
    _slot_lock = threading.Lock()

    def on_start(self):
        with TournamentLifecycleUser._slot_lock:
            self._slot = TournamentLifecycleUser._slot_counter % 3
            TournamentLifecycleUser._slot_counter += 1

        if _setup_error:
            raise StopUser()

    @task
    def solve_tournament_quiz(self):
        if _setup_error:
            print(f"[TASK SKIP] TournamentLifecycleUser slot={self._slot}: setup failed, stopping user")
            raise StopUser()

        print(f"[TASK START] TournamentLifecycleUser slot={self._slot}: starting requests")
        ctx = _ctx
        eid = ctx["course_execution_id"]
        tid = ctx["tournament_id"]
        uid = ctx["user_ids"][self._slot]

        self.client.get(
            f"/executions/{eid}/tournaments/opened",
            headers=HEADERS,
            name="List Open Tournaments",
        )

        self.client.get(
            f"/tournaments/{tid}",
            headers=HEADERS,
            name="Get Tournament",
        )

        r = self.client.post(
            f"/tournaments/{tid}/solveQuiz",
            params={"userAggregateId": uid},
            json={},
            headers=HEADERS,
            name="Solve Quiz",
        )
        if not r.ok:
            raise StopUser()

        quiz = r.json()
        quiz_id = quiz["aggregateId"]
        questions = quiz.get("questionDtos", [])

        for seq, question in enumerate(questions, start=1):
            question_id = question["aggregateId"]
            options = question.get("optionDtos", [])
            option_key = options[0]["key"] if options else 1

            self.client.post(
                f"/quizzes/{quiz_id}/answer",
                params={"userAggregateId": uid},
                json={
                    "sequence": seq,
                    "questionAggregateId": question_id,
                    "timeTaken": 10,
                    "optionKey": option_key,
                },
                headers=HEADERS,
                name="Answer Question",
            )

        self.client.get(
            f"/tournaments/{tid}",
            headers=HEADERS,
            name="Get Tournament Results",
        )

        raise StopUser()
