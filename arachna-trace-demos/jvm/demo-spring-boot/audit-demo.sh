#!/usr/bin/env bash
#
# AI-code-audit demo: records the SAME scenario under TWO code versions of
# the restock appraisal (classic vs "AI-refactored", see RestockAppraiser),
# shipping both sessions through the centralised pipeline
# (collector → Kafka → processor → ClickHouse), then points you at the
# Behavior diff screen where the versions can be compared by evidence.
#
# Prerequisites (see arachna-trace-infra/):
#   docker compose up -d                          # Kafka + ClickHouse
#   java -jar record-collector-server.jar         # :8099
#   java -jar record-processor-server.jar
#   java -jar RecordQueryServer-...-shaded.jar    # :8082
#   (optional) npm run dev in arachna-trace-ui    # :5173
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT=8080
BASE_URL="http://localhost:$PORT"
QUERY_URL="http://localhost:8082"
STAMP=$(date +%H%M%S)
SESSION_A="audit-classic-$STAMP"
SESSION_B="audit-refactored-$STAMP"

# Full trace fidelity for the audit: default tags plus AX, so the
# narrative can show argument-mutation badges (AR vs AX hash).
EMIT_TAGS="SI,TN,RI,TS,CL,TI,AR,AX,RT,RE,TE,SQ"

run_version() {
    local policy=$1 session_id=$2
    echo ""
    echo ">>> Recording '$policy' as session $session_id"

    cd "$SCRIPT_DIR"
    mvn -q spring-boot:run \
        -Dspring-boot.run.jvmArguments="-javaagent:../../../arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar=config=./arachna-agent.cfg&destination=http&session_resolver=config&session_id=$session_id&code_version=restock-$policy&emit_tags=$EMIT_TAGS -Dlibrary.restock.policy=$policy" \
        > /dev/null 2>&1 &
    local mvn_pid=$!

    echo -n "    waiting for startup"
    for i in $(seq 1 45); do
        if curl -s "$BASE_URL/actuator/health" 2>/dev/null | grep -q "UP"; then
            echo " OK"; break
        fi
        echo -n "."; sleep 2
    done

    # --- The scenario. Identical inputs for both versions — determinism is
    # what lets the behavior diff align calls by argument hash. ---

    # 1. The standard demo scenario (identical in both versions — it shows
    #    up as 'unchanged' on the diff, which is the point: the comparison
    #    isolates what the code change touched).
    curl -s -X POST "$BASE_URL/api/library/demo-scenario" > /dev/null

    # 2. The restock catalog: one pre-ISBN legacy edition (the swallowed-
    #    exception + lost-vintage-premium case), one cost landing exactly on
    #    half a cent (the rounding-change case), one that both versions
    #    price identically.
    local author_id
    author_id=$(curl -s -X POST "$BASE_URL/api/authors?name=Karel%20Capek" | sed 's/.*"id":\([0-9]*\).*/\1/')
    curl -s -X POST "$BASE_URL/api/authors/$author_id/books?title=War%20with%20the%20Newts&isbn=LCCN-37-001954&year=1936" > /dev/null
    curl -s -X POST "$BASE_URL/api/authors/$author_id/books?title=Krakatit&isbn=978-80-000-1234-3&year=1985" > /dev/null
    curl -s -X POST "$BASE_URL/api/authors/$author_id/books?title=Tales%20from%20Two%20Pockets&isbn=978-80-000-5678-7&year=1962" > /dev/null

    # 3. The call under audit.
    echo "    restock quote ($policy):"
    curl -s "$BASE_URL/api/library/restock-quote?authorId=$author_id" | python -m json.tool 2>/dev/null | sed 's/^/      /'

    # --- Flush and shut down ---
    sleep 3
    curl -s -X POST "$BASE_URL/actuator/shutdown" > /dev/null 2>&1 || true
    sleep 3
    kill "$mvn_pid" 2>/dev/null || true
    wait "$mvn_pid" 2>/dev/null || true
}

run_version classic    "$SESSION_A"
run_version refactored "$SESSION_B"

# --- Give the pipeline a moment to land rows in ClickHouse ---
echo ""
echo ">>> Waiting for the processor to land both sessions in ClickHouse"
for i in $(seq 1 30); do
    COUNT=$(curl -s "$QUERY_URL/api/analysis/observed-signatures?session_id=$SESSION_B" | grep -c signature || true)
    if [ "$COUNT" -gt 0 ]; then echo "    OK"; break; fi
    echo -n "."; sleep 2
done

# --- Show the comparison ---
echo ""
echo ">>> Behavior diff summary ($SESSION_A vs $SESSION_B):"
curl -s "$QUERY_URL/api/analysis/behavior-diff?session_a=$SESSION_A&session_b=$SESSION_B" \
    | python -c "
import json, sys
d = json.load(sys.stdin)
print('   ', json.dumps(d['summary']))
for g in d['groups']:
    if g['status'] != 'unchanged':
        print('    %-15s %s' % (g['status'], g['signature']))
"

echo ""
echo ">>> Open the comparative screen:"
echo "      http://localhost:5173/diff        (pick $SESSION_A vs $SESSION_B)"
echo ">>> Read either version as a flow narrative:"
echo "      http://localhost:5173/sessions    (book icon on the restock request)"
