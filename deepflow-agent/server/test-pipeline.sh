#!/usr/bin/env bash
#
# End-to-end pipeline test:
#   Agent (HTTP dest) → Collector server → Kafka → Processor server (stdout)
#
# Prerequisites: docker, mvn clean install from root already done.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COLLECTOR_JAR="$SCRIPT_DIR/record-collector-server/target/record-collector-server.jar"
PROCESSOR_JAR="$SCRIPT_DIR/record-processor-server/target/record-processor-server.jar"
DEMO_DIR="$ROOT_DIR/demos/demo-spring-boot"
AGENT_JAR="$ROOT_DIR/core/agent/target/deepflow-agent.jar"

APP_PORT=8080
COLLECTOR_PORT=8099
BASE_URL="http://localhost:$APP_PORT"

COLLECTOR_PID=""
PROCESSOR_PID=""
MVN_PID=""

cleanup() {
    echo ""
    echo ">>> Cleaning up..."

    # Shutdown Spring Boot
    curl -s -X POST "$BASE_URL/actuator/shutdown" > /dev/null 2>&1 || true
    sleep 2
    [ -n "$MVN_PID" ] && kill "$MVN_PID" 2>/dev/null || true

    # Stop processor and collector
    [ -n "$PROCESSOR_PID" ] && kill "$PROCESSOR_PID" 2>/dev/null || true
    [ -n "$COLLECTOR_PID" ] && kill "$COLLECTOR_PID" 2>/dev/null || true

    # Stop docker
    echo "    Stopping docker services..."
    cd "$SCRIPT_DIR"
    docker compose down > /dev/null 2>&1 || true

    wait "$MVN_PID" 2>/dev/null || true
    wait "$PROCESSOR_PID" 2>/dev/null || true
    wait "$COLLECTOR_PID" 2>/dev/null || true

    echo "    Done."
}
trap cleanup EXIT

# --- 1. Check JARs exist ---
for jar in "$COLLECTOR_JAR" "$PROCESSOR_JAR" "$AGENT_JAR"; do
    if [ ! -f "$jar" ]; then
        echo "!!! Missing JAR: $jar"
        echo "    Run 'mvn clean install' from $ROOT_DIR first."
        exit 1
    fi
done

# --- 2. Start Kafka ---
echo ">>> Starting Kafka via docker compose..."
cd "$SCRIPT_DIR"
docker compose up -d

echo -n "    Waiting for Kafka"
for i in $(seq 1 30); do
    if docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 --list > /dev/null 2>&1; then
        echo " OK"
        break
    fi
    echo -n "."
    sleep 2
done

# --- 3. Start collector server (Netty HTTP → Kafka) ---
echo ">>> Starting collector server on port $COLLECTOR_PORT..."
java -jar "$COLLECTOR_JAR" \
    "config=$SCRIPT_DIR/record-collector-server/deepserver.cfg" \
    > /dev/null 2>&1 &
COLLECTOR_PID=$!
sleep 2
echo "    Collector PID: $COLLECTOR_PID"

# --- 4. Start processor server (Kafka → stdout) ---
echo ">>> Starting processor server (Kafka consumer → stdout)..."
java -jar "$PROCESSOR_JAR" \
    "config=$SCRIPT_DIR/record-processor-server/deepprocessor.cfg" \
    &
PROCESSOR_PID=$!
sleep 2
echo "    Processor PID: $PROCESSOR_PID"

# --- 5. Start Spring Boot demo with destination=http ---
echo ">>> Starting Spring Boot demo (destination=http)..."
cd "$DEMO_DIR"
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:$AGENT_JAR=config=./deepagent.cfg&destination=http" \
    > /dev/null 2>&1 &
MVN_PID=$!

echo -n "    Waiting for Spring Boot"
for i in $(seq 1 30); do
    if curl -s "$BASE_URL/actuator/health" 2>/dev/null | grep -q "UP"; then
        echo " OK"
        break
    fi
    echo -n "."
    sleep 2
done

# --- 6. Exercise the API ---
COOKIE_JAR=$(mktemp)

echo ""
echo ">>> Calling API..."

echo -n "    POST /api/authors (Tolkien) ... "
curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -X POST "$BASE_URL/api/authors?name=Tolkien"
echo ""

echo -n "    POST /api/authors/1/books (The Hobbit) ... "
curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -X POST "$BASE_URL/api/authors/1/books?title=The+Hobbit&isbn=978-0-618-00221-3&year=1937"
echo ""

echo -n "    GET /api/books ... "
curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/api/books"
echo ""

rm -f "$COOKIE_JAR"

# --- 7. Let the pipeline flush ---
echo ""
echo ">>> Waiting for pipeline to flush (5s)..."
sleep 5

echo ""
echo ">>> If you see trace lines above (SI;, MS;, CD;, ...) the pipeline works!"
echo ">>> Press Ctrl+C to tear down."

# Keep running so user can see processor output
wait "$PROCESSOR_PID" 2>/dev/null || true
