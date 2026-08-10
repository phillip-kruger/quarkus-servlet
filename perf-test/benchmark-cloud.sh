#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=8080
BASE_URL="http://localhost:${PORT}"
THREADS=${THREADS:-50}
DURATION=${DURATION:-15}
RUNS=${RUNS:-3}
ENDPOINTS=("plaintext" "json" "cdi")

# Simulate 1 CPU / 512MB cloud pod
APP_CPU="0"
LOAD_CPU="1,2,3"
JVM_OPTS="-Xmx384m -Xms384m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=1"

RESULTS_DIR="$SCRIPT_DIR/results/cloud"
mkdir -p "$RESULTS_DIR"

javac -cp "$SCRIPT_DIR" "$SCRIPT_DIR/LoadTest.java" -d "$SCRIPT_DIR" 2>/dev/null || true

wait_for_ready() {
    local max_attempts=60
    local attempt=0
    while ! curl -sf "${BASE_URL}/plaintext" > /dev/null 2>&1; do
        attempt=$((attempt + 1))
        if [ $attempt -ge $max_attempts ]; then
            echo "ERROR: App failed to start within ${max_attempts}s"
            exit 1
        fi
        sleep 1
    done
}

stop_app() {
    local pid_file="$RESULTS_DIR/app.pid"
    if [ -f "$pid_file" ]; then
        local pid
        pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid"
            wait "$pid" 2>/dev/null || true
        fi
        rm -f "$pid_file"
    fi
}

run_benchmark() {
    local impl_name="$1"
    local profile_arg="$2"

    echo ""
    echo "================================================================"
    echo "  $impl_name"
    echo "  Simulated: 1 CPU (pinned to core $APP_CPU), 512MB heap"
    echo "  Load gen: ${THREADS} threads, ${DURATION}s, cores $LOAD_CPU"
    echo "================================================================"

    echo "Building..."
    mvn package -DskipTests $profile_arg -q 2>&1 | tail -3

    echo "Starting app (pinned to CPU $APP_CPU, max 384MB heap)..."
    taskset -c "$APP_CPU" java $JVM_OPTS -jar target/quarkus-app/quarkus-run.jar > /dev/null 2>&1 &
    echo $! > "$RESULTS_DIR/app.pid"
    wait_for_ready
    echo "App ready"

    # Warmup on separate cores
    echo "Warming up..."
    taskset -c "$LOAD_CPU" java -cp "$SCRIPT_DIR" LoadTest "${BASE_URL}/plaintext" "$THREADS" 5 > /dev/null 2>&1

    for endpoint in "${ENDPOINTS[@]}"; do
        echo ""
        echo "--- /${endpoint} (best of $RUNS runs) ---"
        local best_rps=0
        local best_output=""

        for run in $(seq 1 $RUNS); do
            local output
            output=$(taskset -c "$LOAD_CPU" java -cp "$SCRIPT_DIR" LoadTest "${BASE_URL}/${endpoint}" "$THREADS" "$DURATION" 2>&1)
            local rps
            rps=$(echo "$output" | grep "Requests/sec" | awk '{print $2}' | tr -d ',')
            local rps_int=${rps%%.*}

            if [ "$rps_int" -gt "$best_rps" ]; then
                best_rps=$rps_int
                best_output="$output"
            fi
            echo "  Run $run: $(echo "$output" | grep "Requests/sec")"
        done

        echo "$best_output" > "$RESULTS_DIR/${impl_name}_${endpoint}.txt"
    done

    echo ""
    echo "Stopping app..."
    stop_app
    sleep 2
}

print_comparison() {
    echo ""
    echo "================================================================"
    echo "  CLOUD SIMULATION: 1 CPU / 512MB"
    echo "  quarkus-servlet (Vert.x 5) vs quarkus-undertow"
    echo "================================================================"
    echo ""

    printf "%-12s | %-32s | %-32s\n" "Endpoint" "quarkus-servlet (Vert.x 5)" "quarkus-undertow"
    printf "%-12s-+-%-32s-+-%-32s\n" "------------" "--------------------------------" "--------------------------------"

    for endpoint in "${ENDPOINTS[@]}"; do
        local vf="$RESULTS_DIR/vertx_${endpoint}.txt"
        local uf="$RESULTS_DIR/undertow_${endpoint}.txt"

        local v_rps u_rps v_lat u_lat v_p99 u_p99

        v_rps=$(grep "Requests/sec" "$vf" 2>/dev/null | awk '{print $2}' || echo "N/A")
        u_rps=$(grep "Requests/sec" "$uf" 2>/dev/null | awk '{print $2}' || echo "N/A")
        v_lat=$(grep "Avg latency" "$vf" 2>/dev/null | awk '{print $3}' || echo "N/A")
        u_lat=$(grep "Avg latency" "$uf" 2>/dev/null | awk '{print $3}' || echo "N/A")
        v_p99=$(grep "p99 latency" "$vf" 2>/dev/null | awk '{print $3}' || echo "N/A")
        u_p99=$(grep "p99 latency" "$uf" 2>/dev/null | awk '{print $3}' || echo "N/A")

        printf "%-12s | %12s req/s %6s ms avg | %12s req/s %6s ms avg\n" \
            "/${endpoint}" "$v_rps" "$v_lat" "$u_rps" "$u_lat"
        printf "%-12s | %12s ms p99              | %12s ms p99\n" \
            "" "$v_p99" "$u_p99"
    done

    echo ""
    echo "Full results in: $RESULTS_DIR/"
}

trap stop_app EXIT

echo "Cloud Simulation Performance Comparison"
echo "========================================"
echo "Date: $(date)"
echo "Simulated: 1 CPU (core $APP_CPU), 512MB (384MB heap + 128MB metaspace)"
echo "Load gen: cores $LOAD_CPU, $THREADS threads"
echo "Java: $(java -version 2>&1 | head -1)"
echo ""

run_benchmark "vertx" ""
run_benchmark "undertow" "-Pundertow"

print_comparison
