#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=8080
BASE_URL="http://localhost:${PORT}"
THREADS=${THREADS:-200}
DURATION=${DURATION:-15}
ENDPOINTS=("plaintext" "json" "cdi")

RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

# Compile the load tester
echo "Compiling LoadTest.java..."
javac LoadTest.java

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
    echo "  Benchmarking: $impl_name"
    echo "  Threads: $THREADS  Duration: ${DURATION}s"
    echo "================================================================"

    echo "Building with profile: ${profile_arg:-default}..."
    mvn package -DskipTests $profile_arg -q 2>&1 | tail -3

    echo "Starting app..."
    java -jar target/quarkus-app/quarkus-run.jar > /dev/null 2>&1 &
    echo $! > "$RESULTS_DIR/app.pid"
    wait_for_ready
    echo "App ready on port $PORT"
    echo ""

    for endpoint in "${ENDPOINTS[@]}"; do
        local result_file="$RESULTS_DIR/${impl_name}_${endpoint}_wrk.txt"
        echo "--- /${endpoint} ---"
        java -cp "$SCRIPT_DIR" LoadTest "${BASE_URL}/${endpoint}" "$THREADS" "$DURATION" 2>&1 | tee "$result_file"
        echo ""
    done

    echo "Stopping app..."
    stop_app
    sleep 2
}

trap stop_app EXIT

echo "Quarkus Servlet Performance Comparison (Java LoadTest)"
echo "======================================================="
echo "Date: $(date)"
echo "System: $(nproc) cores, $(free -h | awk '/Mem:/{print $2}') RAM"
echo "Java: $(java -version 2>&1 | head -1)"
echo ""

run_benchmark "vertx" ""
run_benchmark "undertow" "-Pundertow"

echo ""
echo "================================================================"
echo "  Full results in: $RESULTS_DIR/"
echo "================================================================"
