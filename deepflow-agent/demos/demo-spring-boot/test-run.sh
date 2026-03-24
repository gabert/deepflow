#!/usr/bin/env bash
#
# Starts the Spring Boot demo with the agent, exercises the API with
# two separate users (two HTTP sessions), prints the trace output, and
# tears down.  Runs one pass per config file listed in CONFIGS.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DUMP_DIR="D:/temp"
PORT=8080
BASE_URL="http://localhost:$PORT"

# --- Configs to test (edit this list to choose scenarios) ---
CONFIGS=(
    "deepagent-serialize-on.cfg"
    "deepagent-no-hibernate.cfg"
    "deepagent-no-session.cfg"
)

run_pass() {
    local CFG="$1"
    echo ""
    echo "================================================================"
    echo "=== CONFIG: $CFG"
    echo "================================================================"
    cat "$SCRIPT_DIR/$CFG"
    echo ""

    # Cookie jars — each simulates a distinct user / HTTP session
    local ALICE_JAR; ALICE_JAR=$(mktemp)
    local BOB_JAR;   BOB_JAR=$(mktemp)

    # Note existing session dirs so we can find the new one
    local BEFORE_DIRS; BEFORE_DIRS=$(ls -1d "$DUMP_DIR"/SESSION-*/ 2>/dev/null || true)

    # Start Spring Boot
    echo ">>> Starting Spring Boot demo..."
    cd "$SCRIPT_DIR"
    mvn spring-boot:run \
        -Dspring-boot.run.jvmArguments="-javaagent:../../core/agent/target/deepflow-agent.jar=config=./$CFG" \
        > /dev/null 2>&1 &
    local MVN_PID=$!

    # Wait for startup
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

    # Let the agent flush
    sleep 3

    # Shutdown
    echo ""
    echo ">>> Shutting down Spring Boot..."
    curl -s -X POST "$BASE_URL/actuator/shutdown" > /dev/null
    sleep 5
    kill "$MVN_PID" 2>/dev/null || true
    wait "$MVN_PID" 2>/dev/null || true
    rm -f "$ALICE_JAR" "$BOB_JAR"

    # Find the new session dir
    local AFTER_DIRS; AFTER_DIRS=$(ls -1d "$DUMP_DIR"/SESSION-*/ 2>/dev/null || true)
    local NEW_DIR; NEW_DIR=$(comm -13 <(echo "$BEFORE_DIRS") <(echo "$AFTER_DIRS") | tail -1)

    if [ -z "$NEW_DIR" ]; then
        echo "!!! No new session directory found in $DUMP_DIR"
        return 1
    fi

    echo ">>> Session dir: $NEW_DIR"

    # --- Summary ---
    echo ""
    echo "--- Trace files ---"
    ls -1 "$NEW_DIR"/*.dft

    echo ""
    echo "--- Unique session IDs ---"
    grep "^SI;" "$NEW_DIR"/*.dft | sed 's/.*SI;//' | sort -u || echo "(none)"

    echo ""
    echo "--- Sample: first AuthorEntity argument (hibernate proxy test) ---"
    # Show the first AR block that contains AuthorEntity to see if proxy was resolved
    for dft in "$NEW_DIR"/*.dft; do
        if grep -q "AuthorEntity" "$dft" 2>/dev/null; then
            # Print from the AR line containing AuthorEntity through the next record marker
            awk '/^AR;.*AuthorEntity/,/^[A-Z]{2};/' "$dft" | head -30
            break
        fi
    done
    # If no AuthorEntity found in AR, check if it appears anywhere
    if ! grep -q "AuthorEntity" "$NEW_DIR"/*.dft 2>/dev/null; then
        echo "(AuthorEntity not found in traces — proxy was NOT resolved)"
    fi

    echo ""
    echo "--- Full trace output ---"
    for dft in "$NEW_DIR"/*.dft; do
        echo ""
        echo "=== $(basename "$dft") ==="
        cat "$dft"
    done
}

# --- Main ---
for CFG in "${CONFIGS[@]}"; do
    run_pass "$CFG"
done

echo ""
echo ">>> All passes done."
