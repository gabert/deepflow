#!/usr/bin/env bash
#
# Starts the Spring Boot demo with the agent, exercises the API with
# two separate users (two HTTP sessions), prints the trace output, and
# tears down.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DUMP_DIR="D:/temp"
PORT=8080
BASE_URL="http://localhost:$PORT"

# Cookie jars — each simulates a distinct user / HTTP session
ALICE_JAR=$(mktemp)
BOB_JAR=$(mktemp)

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
    rm -f "$ALICE_JAR" "$BOB_JAR"
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

# --- Alice: creates Tolkien and The Hobbit ---
echo ""
echo ">>> Alice (session 1)..."

echo -n "    POST /api/authors (Tolkien) ... "
curl -sf -b "$ALICE_JAR" -c "$ALICE_JAR" \
    -X POST "$BASE_URL/api/authors?name=Tolkien"
echo ""

echo -n "    POST /api/authors/1/books (The Hobbit) ... "
curl -sf -b "$ALICE_JAR" -c "$ALICE_JAR" \
    -X POST "$BASE_URL/api/authors/1/books?title=The+Hobbit&isbn=978-0-618-00221-3&year=1937"
echo ""

# --- Bob: creates Asimov and Foundation ---
echo ""
echo ">>> Bob (session 2)..."

echo -n "    POST /api/authors (Asimov) ... "
curl -sf -b "$BOB_JAR" -c "$BOB_JAR" \
    -X POST "$BASE_URL/api/authors?name=Asimov"
echo ""

echo -n "    POST /api/authors/2/books (Foundation) ... "
curl -sf -b "$BOB_JAR" -c "$BOB_JAR" \
    -X POST "$BASE_URL/api/authors/2/books?title=Foundation&isbn=978-0-553-29335-7&year=1951"
echo ""

# --- Both users list everything ---
echo ""
echo ">>> Alice lists books..."
echo -n "    GET  /api/books ... "
curl -sf -b "$ALICE_JAR" -c "$ALICE_JAR" "$BASE_URL/api/books"
echo ""

echo ""
echo ">>> Bob lists authors..."
echo -n "    GET  /api/authors ... "
curl -sf -b "$BOB_JAR" -c "$BOB_JAR" "$BASE_URL/api/authors"
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
