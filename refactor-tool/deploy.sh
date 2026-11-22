#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

usage() {
    cat <<'EOF'
usage: ./deploy.sh [--ghost] [--seed-basic]

  --ghost        Run the Execute phase in ghost mode: each run creates its branch, waits for a
                 manual SUCCESS/FAILED verdict in the UI, then writes and commits the CLAUDE.md
                 refactor-log entry that verdict decides. No Claude mutation, no test gate, no
                 image build, no redeploy. For frontend work, where a real iteration is too slow.

  --seed-basic   Load data/constraints-quizzes.sql: the refactoring constraints that hold before
                 any run — the aggregates that cannot be split and the merges that name `user`.
                 Idempotent, so it is safe on every deploy. Because this script wipes rt-db, the
                 constraints are lost on a plain ./deploy.sh: pass the flag to put them back.

NOTE: this script runs `docker compose down -v`, which wipes rt-db. To only flip ghost mode
      without losing your iterations, use ./deploy-rt.sh --ghost instead.
EOF
}

GHOST=false
SEED_BASIC=false
for arg in "$@"; do
    case "$arg" in
        --ghost)      GHOST=true ;;
        --seed-basic) SEED_BASIC=true ;;
        -h|--help)    usage; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; usage >&2; exit 1 ;;
    esac
done
# The shell environment wins over .env for compose interpolation, so exporting is enough — and
# exporting `false` explicitly means plain ./deploy.sh always turns ghost mode back off.
export RT_EXECUTE_GHOST_MODE="$GHOST"
echo "==> Execute ghost mode: $GHOST"

# Applies data/constraints-quizzes.sql to rt-db. The file carries its own CREATE TABLE, so it does
# not need rt-app to have booted, and every INSERT is ON CONFLICT DO NOTHING, so re-running is a
# no-op. ON_ERROR_STOP makes a bad statement fail the deploy instead of passing in silence.
seed_basic() {
    docker compose up -d rt-db
    for _ in $(seq 1 30); do
        docker compose exec -T rt-db pg_isready -U rt -d refactordb >/dev/null 2>&1 && break
        sleep 2
    done
    docker compose exec -T rt-db psql -U rt -d refactordb -v ON_ERROR_STOP=1 -q \
        < data/constraints-quizzes.sql
    local count
    count=$(docker compose exec -T rt-db psql -U rt -d refactordb \
        -tAc 'select count(*) from refactor_constraint' | tr -d '[:space:]')
    echo "==> Constraints in rt-db: $count"
}

echo "==> Stopping all containers and removing volumes (full reset)..."
docker compose down -v

echo "==> Building rt-app JAR (Java 17, tests skipped)..."
mvn clean package -DskipTests

echo "==> Rebuilding all images and starting stack..."
# Builds two custom images:
#   rt-app        — ./rt-app/Dockerfile  (Java, copies JAR built above)
#   locust-service — ./utils/locust/Dockerfile  (Python FastAPI)
# Start order enforced by depends_on:
#   rt-db, locust-db → locust-service (healthy) → rt-app
docker compose up --build -d

if [ "$SEED_BASIC" = true ]; then
    echo "==> Seeding baseline refactor constraints (quizzes)..."
    seed_basic
fi

echo "==> Deployment complete."