#!/usr/bin/env bash
#
# Throughput comparison between quarkus-servlet (Vert.x) and quarkus-undertow, using wrk.
#
# Methodology (all of it visible here, so the README numbers can be reproduced):
#   - each endpoint gets a discarded warm-up run, then N measured runs; the best is reported
#   - server and load generator are pinned to disjoint CPU sets so they do not fight each other
#   - both implementations are built from the same source tree with identical JVM options
#
# wrk runs in a container because it needs compiling and this machine has no C toolchain:
#   podman run --rm --network=host williamyeh/wrk ...
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=8080
BASE_URL="http://localhost:${PORT}"

# wrk parameters - override from the environment to explore other shapes
CONNECTIONS=${CONNECTIONS:-100}
WRK_THREADS=${WRK_THREADS:-4}
DURATION=${DURATION:-15}
WARMUP=${WARMUP:-10}
RUNS=${RUNS:-3}

# CPU pinning: the server gets one set of cores and wrk another, so the load generator
# cannot steal cycles from the thing being measured.
SERVER_CPUS=${SERVER_CPUS:-0-7}
WRK_CPUS=${WRK_CPUS:-8-11}

JVM_OPTS=${JVM_OPTS:--Xms512m -Xmx512m}
WRK_IMAGE=${WRK_IMAGE:-docker.io/williamyeh/wrk:latest}

ENDPOINTS=("plaintext" "json" "cdi")
RESULTS_DIR="$SCRIPT_DIR/results/wrk"
mkdir -p "$RESULTS_DIR"

require() {
    command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 is required but not installed"; exit 1; }
}
require podman
require taskset

wait_for_ready() {
    for _ in $(seq 1 60); do
        if curl -sf "${BASE_URL}/plaintext" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "ERROR: app failed to start within 60s"
    exit 1
}

stop_app() {
    local pid_file="$RESULTS_DIR/app.pid"
    [ -f "$pid_file" ] || return 0
    local pid
    pid=$(cat "$pid_file")
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 20); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.5
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file"
}
trap stop_app EXIT

# Runs wrk once and echoes "<requests/sec> <p50> <p90> <p99>".
run_wrk() {
    local url="$1" duration="$2" out="$3"
    taskset -c "$WRK_CPUS" podman run --rm --network=host "$WRK_IMAGE" \
        -t"$WRK_THREADS" -c"$CONNECTIONS" -d"${duration}s" --latency "$url" > "$out" 2>&1 || true

    local rps p50 p90 p99
    rps=$(grep -E "^Requests/sec:" "$out" | awk '{print $2}')
    p50=$(grep -E "^[[:space:]]+50%" "$out" | awk '{print $2}')
    p90=$(grep -E "^[[:space:]]+90%" "$out" | awk '{print $2}')
    p99=$(grep -E "^[[:space:]]+99%" "$out" | awk '{print $2}')
    echo "${rps:-0} ${p50:-n/a} ${p90:-n/a} ${p99:-n/a}"
}

benchmark_impl() {
    local impl_name="$1" profile_arg="$2"

    echo ""
    echo "================================================================"
    echo "  $impl_name"
    echo "================================================================"
    echo "Building..."
    # shellcheck disable=SC2086
    mvn package -DskipTests $profile_arg -q 2>&1 | tail -3

    echo "Starting app (cores $SERVER_CPUS, $JVM_OPTS)..."
    # shellcheck disable=SC2086
    taskset -c "$SERVER_CPUS" java $JVM_OPTS -jar target/quarkus-app/quarkus-run.jar \
        >"$RESULTS_DIR/${impl_name}-app.log" 2>&1 &
    echo $! > "$RESULTS_DIR/app.pid"
    wait_for_ready

    for endpoint in "${ENDPOINTS[@]}"; do
        local url="${BASE_URL}/${endpoint}"
        echo ""
        echo "--- /${endpoint} ---"

        echo "warm-up (${WARMUP}s, discarded)"
        run_wrk "$url" "$WARMUP" "$RESULTS_DIR/${impl_name}_${endpoint}_warmup.txt" >/dev/null

        local best_rps=0 best_line=""
        for run in $(seq 1 "$RUNS"); do
            local out="$RESULTS_DIR/${impl_name}_${endpoint}_run${run}.txt"
            local line rps
            line=$(run_wrk "$url" "$DURATION" "$out")
            rps=$(echo "$line" | awk '{print $1}')
            printf "  run %s: %s req/s\n" "$run" "$rps"
            if awk "BEGIN{exit !($rps > $best_rps)}"; then
                best_rps=$rps
                best_line=$line
            fi
        done
        echo "$best_line" > "$RESULTS_DIR/${impl_name}_${endpoint}_best.txt"
        printf "  best: %s\n" "$best_line"
    done

    echo "Stopping app..."
    stop_app
    sleep 2
}

echo "quarkus-servlet vs quarkus-undertow"
echo "==================================="
echo "Date:        $(date -Is)"
echo "System:      $(nproc) cores, $(free -h | awk '/Mem:/{print $2}') RAM"
echo "Java:        $(java -version 2>&1 | head -1)"
echo "wrk:         $WRK_IMAGE (container)"
echo "Load:        ${CONNECTIONS} connections, ${WRK_THREADS} wrk threads, ${DURATION}s x ${RUNS} runs, best of ${RUNS}"
echo "Warm-up:     ${WARMUP}s per endpoint (discarded)"
echo "Server CPUs: $SERVER_CPUS      wrk CPUs: $WRK_CPUS"
echo "JVM:         $JVM_OPTS"

benchmark_impl "vertx" "-Pvertx"
benchmark_impl "undertow" "-Pundertow"

echo ""
echo "================================================================"
echo "  Summary (best of $RUNS runs, requests/sec)"
echo "================================================================"
printf "%-12s %16s %16s %10s\n" "endpoint" "quarkus-servlet" "quarkus-undertow" "diff"
for endpoint in "${ENDPOINTS[@]}"; do
    v=$(awk '{print $1}' "$RESULTS_DIR/vertx_${endpoint}_best.txt" 2>/dev/null || echo 0)
    u=$(awk '{print $1}' "$RESULTS_DIR/undertow_${endpoint}_best.txt" 2>/dev/null || echo 0)
    diff=$(awk "BEGIN{ if ($u > 0) printf \"%+.1f%%\", (($v-$u)/$u)*100; else print \"n/a\" }")
    printf "%-12s %16s %16s %10s\n" "/$endpoint" "$v" "$u" "$diff"
done
echo ""
echo "Latency percentiles are in $RESULTS_DIR/<impl>_<endpoint>_best.txt (rps p50 p90 p99)"
