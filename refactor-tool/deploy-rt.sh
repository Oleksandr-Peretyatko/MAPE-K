#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Absolute: the redirect below is resolved by the caller's shell, before the subshell's cd.
LOG_FILE="$SCRIPT_DIR/deploy-rt.log"

usage() {
    cat <<'EOF'
usage: ./deploy-rt.sh [--ghost] [--seed-basic]

  --ghost        Run the Execute phase in ghost mode: each run creates its branch, waits for a
                 manual SUCCESS/FAILED verdict in the UI, then writes and commits the CLAUDE.md
                 refactor-log entry that verdict decides. No Claude mutation, no test gate, no
                 image build, no redeploy.

  --seed-basic   Load data/constraints-quizzes.sql: the refactoring constraints that hold before
                 any run — the aggregates that cannot be split and the merges that name `user`.
                 Idempotent, and this script leaves the databases alone, so it is the way to add
                 them to a stack that is already running.

This is the script to use for toggling ghost mode: it rebuilds rt-app only and leaves the
databases (and therefore your iterations and versions) alone.
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
# Shell environment wins over .env for compose interpolation; exporting `false` explicitly means
# a plain ./deploy-rt.sh always turns ghost mode back off.
export RT_EXECUTE_GHOST_MODE="$GHOST"
echo "rt-app redeploy — Execute ghost mode: $GHOST"

(
    cd "$SCRIPT_DIR"

    echo "==> Execute ghost mode: $GHOST"

    echo "==> Stopping rt-app (databases untouched)..."
    docker compose stop rt-app

    echo "==> Ensuring databases are running..."
    docker compose up -d rt-db locust-db

    # Applies data/constraints-quizzes.sql. The file carries its own CREATE TABLE, so it does not
    # need rt-app to have booted, and every INSERT is ON CONFLICT DO NOTHING, so re-running is a
    # no-op. ON_ERROR_STOP makes a bad statement fail the deploy instead of passing in silence.
    if [ "$SEED_BASIC" = true ]; then
        echo "==> Seeding baseline refactor constraints (quizzes)..."
        for _ in $(seq 1 30); do
            docker compose exec -T rt-db pg_isready -U rt -d refactordb >/dev/null 2>&1 && break
            sleep 2
        done
        docker compose exec -T rt-db psql -U rt -d refactordb -v ON_ERROR_STOP=1 -q \
            < data/constraints-quizzes.sql
        echo "==> Constraints in rt-db: $(docker compose exec -T rt-db psql -U rt -d refactordb \
            -tAc 'select count(*) from refactor_constraint' | tr -d '[:space:]')"
    fi

    # rt-app depends_on locust-service (service_healthy), so locust-service must
    # be up and healthy before docker compose will start rt-app.
    echo "==> Ensuring locust-service is running..."
    docker compose up -d locust-service

    echo "==> Building rt-app JAR (tests skipped)..."
    mvn clean package -DskipTests

    echo "==> Rebuilding and restarting rt-app container..."
    docker compose up --build -d --no-deps rt-app

    echo "==> Done. Logs in $LOG_FILE"
) > "$LOG_FILE" 2>&1 &

echo "rt-app redeploy running in background — tail $LOG_FILE to follow."