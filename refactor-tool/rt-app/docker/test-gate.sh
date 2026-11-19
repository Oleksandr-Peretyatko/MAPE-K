#!/usr/bin/env bash
# test-gate — functional test gate for the Claude mutator. Installed in the rt-app image as
# /usr/local/bin/test-gate, so it exists whatever branch is checked out in the project.
#
# Runs the same build rt-app uses as its Execute step 5a (ExecuteService.buildDockerTestCmdForProfile)
# and reduces the output to what is needed to fix failures; the full log stays on disk.
#
# Usage: test-gate --project-path <path> --profile <profile>
#   --project-path  application module, relative to the repository root (its Dockerfile is built)
#   --profile       Dockerfile PROFILE build arg; the test stage runs Maven profile test-<profile>
# Both are required: rt-app writes the concrete command, from its app-target and build-profile
# configuration, into the mutator's prompt. The repository root is the git top level of the cwd.
# Exit code: that of the build (0 = green); 2 on a usage error.
set -uo pipefail

usage() { sed -n '8,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

APP_PATH=""
PROFILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --project-path) APP_PATH="${2:-}"; shift 2 ;;
        --profile)      PROFILE="${2:-}"; shift 2 ;;
        -h|--help)      usage ;;
        *)              echo "test-gate: unknown argument: $1" >&2; usage ;;
    esac
done
[ -n "$APP_PATH" ] && [ -n "$PROFILE" ] || usage

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "test-gate: not inside a git repository (cwd: $PWD)" >&2; exit 2; }
DOCKERFILE="$REPO_ROOT/${APP_PATH%/}/Dockerfile"
[ -f "$DOCKERFILE" ] || { echo "test-gate: no Dockerfile at $DOCKERFILE" >&2; exit 2; }
LOG="${TMPDIR:-/tmp}/test-gate-$(date +%Y%m%d-%H%M%S).log"
MAX_DETAIL_LINES="${MAX_DETAIL_LINES:-400}"
# The default builder clips each step's output at 2 MiB, which drops surefire's final report on a
# full suite run. This dedicated builder has no log limit. rt-app's step 5a runs on it too
# (ExecuteService.TEST_GATE_BUILDER): keep the name and the create options below in sync with it.
BUILDER="rt-test-gate"

if ! docker buildx inspect "$BUILDER" >/dev/null 2>&1; then
    docker buildx create --name "$BUILDER" --driver docker-container \
        --driver-opt env.BUILDKIT_STEP_LOG_MAX_SIZE=-1 \
        --driver-opt env.BUILDKIT_STEP_LOG_MAX_SPEED=-1 >/dev/null || exit 1
fi

echo "[test-gate] running -Ptest-$PROFILE via docker build (several minutes)"
echo "[test-gate] full log: $LOG"

# --no-cache-filter test: always run the test stage; the base stage (simulator install,
# dependency download) stays cached.
docker buildx build --builder "$BUILDER" --progress=plain \
    --target test --no-cache-filter test \
    --build-arg "PROFILE=$PROFILE" \
    -f "$DOCKERFILE" "$REPO_ROOT" >"$LOG" 2>&1
status=$?

# Drop the BuildKit step/time prefix ("#12 174.7 ").
clean() { sed -E 's/^#[0-9]+ [0-9.]+ //' "$LOG"; }

compile_errors="$(clean | grep -E '^\[ERROR\] .*\.(java|groovy)' | sort -u)"
if [ -n "$compile_errors" ]; then
    echo
    echo "=== Compilation errors ==="
    echo "$compile_errors" | head -n "$MAX_DETAIL_LINES"
fi

# Surefire's final report: from "Results:" up to the totals line.
results="$(clean | awk '/^\[(INFO|ERROR|WARNING)\] Results:/ {on=1; buf=""} on {buf=buf $0 "\n"} on && /Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$/ {on=0; last=buf} END {printf "%s", last}')"
if [ "$status" -ne 0 ] && [ -n "$results" ]; then
    echo
    echo "=== Test results ==="
    echo "$results" | head -n "$MAX_DETAIL_LINES"
fi

totals="$(clean | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' | tail -n 1)"

echo
if [ "$status" -eq 0 ]; then
    echo "[test-gate] GREEN — ${totals:-no surefire totals found}"
else
    if [ -z "$compile_errors" ] && [ -z "$results" ]; then
        echo "=== Last 60 lines (no compile errors or test report found) ==="
        clean | tail -n 60
        echo
    fi
    echo "[test-gate] RED (exit=$status) — ${totals:-no surefire totals found}"
    echo "[test-gate] stack traces: grep -n -A30 '<TestClass>' $LOG"
fi
exit "$status"
