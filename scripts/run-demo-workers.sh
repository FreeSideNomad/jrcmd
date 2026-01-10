#!/usr/bin/env bash
#
# Run E2E Demo Workers (worker processes only, no web server)
#
# This starts one or more worker processes that process commands
# from the queue. Run alongside run-demo-ui.sh for full E2E testing.
#
# Usage:
#   ./scripts/run-demo-workers.sh              # Start 1 worker
#   ./scripts/run-demo-workers.sh 3            # Start 3 workers
#   ./scripts/run-demo-workers.sh 3 8          # Start 3 workers with concurrency 8
#
# Prerequisites:
#   - PostgreSQL must be running (start with run-demo-ui.sh first)
#   - Migrations must have been applied (run-demo-ui.sh handles this)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

# Parse arguments
NUM_WORKERS="${1:-1}"
CONCURRENCY="${2:-4}"

echo "Starting $NUM_WORKERS worker process(es) with concurrency=$CONCURRENCY each..."
echo ""

# Array to store PIDs
PIDS=()

# Trap to clean up workers on exit
cleanup() {
    echo ""
    echo "Stopping workers..."
    for pid in "${PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    wait
    echo "All workers stopped."
}
trap cleanup EXIT INT TERM

# Start workers
for i in $(seq 1 $NUM_WORKERS); do
    echo "Starting worker $i..."
    mvn spring-boot:test-run \
        -Dspring-boot.run.main-class=com.commandbus.e2e.E2ETestApplication \
        -Dspring-boot.run.profiles=e2e,worker \
        -Dcommandbus.worker.concurrency=$CONCURRENCY \
        -q &
    PIDS+=($!)
    sleep 2  # Stagger startup
done

echo ""
echo "All $NUM_WORKERS worker(s) started."
echo "Press Ctrl+C to stop all workers."
echo ""

# Wait for all workers
wait
