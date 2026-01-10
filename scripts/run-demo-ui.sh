#!/usr/bin/env bash
#
# Run the E2E Demo UI application (UI only, no worker)
#
# This starts the Thymeleaf-based test application for E2E testing
# of the command bus library. Workers should be started separately
# using run-demo-workers.sh.
#
# Usage:
#   ./scripts/run-demo-ui.sh           # UI only (default)
#   ./scripts/run-demo-ui.sh --full    # UI + embedded worker
#
# The application will be available at http://localhost:8080
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

# Parse arguments
PROFILES="e2e,ui"
if [ "${1:-}" = "--full" ]; then
    PROFILES="e2e"
    echo "Running in full mode (UI + worker)"
fi

# Start PostgreSQL with PGMQ
echo "Starting PostgreSQL with PGMQ..."
docker rm -f java-commandbus-postgres 2>/dev/null || true
docker compose up -d --wait

# Kill any process running on port 8080
PORT=8080
PID=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$PID" ]; then
    echo "Killing process $PID on port $PORT..."
    kill -9 $PID 2>/dev/null || true
    sleep 1
fi

echo ""
echo "Starting E2E Demo UI (profiles: $PROFILES)..."
echo "Application will be available at http://localhost:8080"
echo "Flyway will run migrations automatically on startup."
echo ""
echo "To start workers separately, run: ./scripts/run-demo-workers.sh"
echo ""

# Run the E2E application using spring-boot:test-run (includes test classpath)
mvn spring-boot:test-run \
    -Dspring-boot.run.main-class=com.commandbus.e2e.E2ETestApplication \
    -Dspring-boot.run.profiles=$PROFILES
