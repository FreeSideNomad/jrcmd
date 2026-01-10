#!/usr/bin/env bash
#
# Run the E2E Demo UI application
#
# This starts the Thymeleaf-based test application for E2E testing
# of the command bus library.
#
# Usage:
#   ./scripts/run-demo-ui.sh
#
# The application will be available at http://localhost:8080
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

echo "Starting E2E Demo UI..."
echo "Application will be available at http://localhost:8080"
echo ""

# Run with the e2e profile
# The test-compile ensures E2E classes are compiled
mvn test-compile spring-boot:run \
    -Dspring-boot.run.profiles=e2e \
    -Dspring-boot.run.mainClass=com.commandbus.e2e.E2ETestApplication \
    -Dspring-boot.run.directories=target/test-classes
