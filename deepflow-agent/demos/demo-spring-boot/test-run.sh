#!/usr/bin/env bash
#
# Starts the Spring Boot demo with the agent, runs the demo scenario
# (one request that exercises create, rename, merge, transfer), prints
# the trace output, and tears down.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DUMP_DIR="D:/temp"
PORT=8080
BASE_URL="http://localhost:$PORT"

# --- Note existing session dirs so we can find the new one ---
BEFORE_DIRS=$(ls -1d "$DUMP_DIR"/SESSION-*/ 2>/dev/null || true)

# --- Start Spring Boot ---
echo ">>> Starting Spring Boot demo..."
cd "$SCRIPT_DIR"
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:../../core/agent/target/deepflow-agent.jar=config=./deepagent.cfg" \
    > /dev/null 2>&1 &
MVN_PID=$!

cleanup() {
    echo ">>> Cleaning up..."
    curl -s -X POST "$BASE_URL/actuator/shutdown" > /dev/null 2>&1 || true
    sleep 3
    kill "$MVN_PID" 2>/dev/null || true
    wait "$MVN_PID" 2>/dev/null || true
    echo "    Done"
}
trap cleanup EXIT

# --- Wait for startup ---
echo -n "    Waiting for startup"
for i in $(seq 1 30); do
    if curl -s "$BASE_URL/actuator/health" 2>/dev/null | grep -q "UP"; then
        echo " OK"
        break
    fi
    echo -n "."
    sleep 2
done

# --- Run the demo scenario (single request, full trace) ---
echo ""
echo ">>> Running demo scenario (create, rename, merge, transfer)..."
echo -n "    POST /api/library/demo-scenario ... "
curl -sf -X POST "$BASE_URL/api/library/demo-scenario" | python -m json.tool 2>/dev/null || true
echo ""

# --- Let the agent flush ---
sleep 3

# --- Shutdown ---
echo ""
echo ">>> Shutting down Spring Boot..."
curl -s -X POST "$BASE_URL/actuator/shutdown" > /dev/null
sleep 5

# --- Find the new session dir ---
AFTER_DIRS=$(ls -1d "$DUMP_DIR"/SESSION-*/ 2>/dev/null || true)
NEW_DIR=$(comm -13 <(echo "$BEFORE_DIRS") <(echo "$AFTER_DIRS") | tail -1)

if [ -z "$NEW_DIR" ]; then
    echo "!!! No new session directory found in $DUMP_DIR"
    exit 1
fi

echo ">>> Session dir: $NEW_DIR"

# --- Show trace contents ---
for dft in "$NEW_DIR"/*.dft; do
    echo ""
    echo "=== $(basename "$dft") ==="
    cat "$dft"
done

echo ""
echo ">>> All done."
