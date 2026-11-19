#!/usr/bin/env bash
set -euo pipefail

# Fast redeploy of the locust-service only.
# Use this when iterating on the Python FastAPI code in utils/locust/api/.
# Does NOT rebuild rt-app or touch any database volumes.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Absolute: the redirect below is resolved by the caller's shell, before the subshell's cd.
LOG_FILE="$SCRIPT_DIR/deploy-locust.log"

(
    cd "$SCRIPT_DIR"

    echo "==> Stopping locust-service..."
    docker compose stop locust-service

    echo "==> Ensuring locust-db is running..."
    docker compose up -d locust-db

    echo "==> Rebuilding and restarting locust-service..."
    docker compose up --build -d --no-deps locust-service

    echo "==> Smoke-testing locust-service health..."
    for i in 1 2 3 4 5; do
        sleep 4
        STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8100/health || true)
        if [ "$STATUS" = "200" ]; then
            echo "==> locust-service is healthy (HTTP 200)."
            break
        fi
        echo "    Waiting for locust-service... attempt $i/5 (last status: $STATUS)"
    done

    echo "==> Done. Logs in $LOG_FILE"
) > "$LOG_FILE" 2>&1 &

echo "locust-service redeploy running in background — tail $LOG_FILE to follow."
