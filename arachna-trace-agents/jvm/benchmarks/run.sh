#!/usr/bin/env bash
# Agent-overhead benchmark driver. Run from this directory.
# Prereq: the agent is built (mvn clean install in arachna-trace-agents/jvm).
set -e

AGENT_JAR="../core/agent/target/arachna-trace-agent.jar"
[ -f "$AGENT_JAR" ] || { echo "Build the agent first: cd .. && mvn clean install"; exit 1; }

mkdir -p out dump

cat > out/bench.cfg <<EOF
session_dump_location=$(pwd)/dump
matchers_include=bench\\..*
destination=file
serialize_values=true
EOF

cat > out/bench-http.cfg <<EOF
matchers_include=bench\\..*
destination=http
http_server_url=http://localhost:18099/records
serialize_values=true
EOF

javac -g -d out src/bench/*.java
javac -g:none -cp out -d out src-stripped/bench/StrippedTarget.java
javac -g -cp out -d out src-run/benchrun/BenchRunner.java

run() { # label, agent-args-or-empty, mode, warmup, iters, rounds
  echo; echo "=== $1 ==="
  if [ -z "$2" ]; then
    java -cp out benchrun.BenchRunner "$3" "$4" "$5" "$6"
  else
    java -Xmx3g "-javaagent:$AGENT_JAR=$2" -cp out benchrun.BenchRunner "$3" "$4" "$5" "$6"
  fi
}

run "CONTROL simple (no agent)"            ""                                            normal   10000 3000 7
run "CONTROL business (no agent)"          ""                                            business 10000 3000 7
run "AGENT simple, full serialization"     "config=out/bench-http.cfg"                   normal   15000 3000 7
run "AGENT simple, structural"             "config=out/bench-http.cfg&serialize_values=false" normal 15000 3000 7
run "AGENT business, full serialization"   "config=out/bench-http.cfg"                   business 8000 3000 7
run "AGENT stripped class (-g:none)"       "config=out/bench-http.cfg"                   stripped 1000 500 5
