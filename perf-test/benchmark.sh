#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT=8080
BASE_URL="http://localhost:${PORT}"
REQUESTS=${REQUESTS:-100000}
CONCURRENCY=${CONCURRENCY:-100}
WARMUP_REQUESTS=10000
WARMUP_CONCURRENCY=50
ENDPOINTS=("plaintext" "json" "cdi")

RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

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
    echo "  Requests: $REQUESTS  Concurrency: $CONCURRENCY"
    echo "================================================================"

    echo "Building with profile: ${profile_arg:-default}..."
    mvn package -DskipTests $profile_arg -q 2>&1 | tail -3

    echo "Starting app..."
    java -jar target/quarkus-app/quarkus-run.jar &
    echo $! > "$RESULTS_DIR/app.pid"
    wait_for_ready
    echo "App ready on port $PORT"

    echo "Warming up (${WARMUP_REQUESTS} requests, ${WARMUP_CONCURRENCY} concurrency)..."
    ab -n "$WARMUP_REQUESTS" -c "$WARMUP_CONCURRENCY" "${BASE_URL}/plaintext" > /dev/null 2>&1

    for endpoint in "${ENDPOINTS[@]}"; do
        local result_file="$RESULTS_DIR/${impl_name}_${endpoint}.txt"
        echo ""
        echo "--- /${endpoint} ---"
        ab -n "$REQUESTS" -c "$CONCURRENCY" "${BASE_URL}/${endpoint}" 2>&1 | tee "$result_file"
    done

    echo ""
    echo "Stopping app..."
    stop_app
    sleep 2
}

extract_metric() {
    local file="$1"
    local pattern="$2"
    grep "$pattern" "$file" | head -1 | awk '{print $(NF-1), $NF}' | sed 's/ \[.*//; s/^ *//'
}

extract_rps() {
    grep "Requests per second:" "$1" | awk '{print $4}'
}

extract_mean_latency() {
    grep "Time per request:" "$1" | head -1 | awk '{print $4}'
}

extract_p99() {
    grep "^ *99%" "$1" | awk '{print $2}'
}

extract_transfer() {
    grep "Transfer rate:" "$1" | awk '{print $3}'
}

print_comparison() {
    echo ""
    echo "================================================================"
    echo "  COMPARISON: quarkus-servlet (Vert.x) vs quarkus-undertow"
    echo "  Requests: $REQUESTS  Concurrency: $CONCURRENCY"
    echo "================================================================"
    echo ""

    printf "%-12s | %-28s | %-28s\n" "Endpoint" "quarkus-servlet (Vert.x)" "quarkus-undertow"
    printf "%-12s-+-%-28s-+-%-28s\n" "------------" "----------------------------" "----------------------------"

    for endpoint in "${ENDPOINTS[@]}"; do
        local vf="$RESULTS_DIR/vertx_${endpoint}.txt"
        local uf="$RESULTS_DIR/undertow_${endpoint}.txt"

        local v_rps u_rps v_lat u_lat v_p99 u_p99

        v_rps=$(extract_rps "$vf" 2>/dev/null || echo "N/A")
        u_rps=$(extract_rps "$uf" 2>/dev/null || echo "N/A")
        v_lat=$(extract_mean_latency "$vf" 2>/dev/null || echo "N/A")
        u_lat=$(extract_mean_latency "$uf" 2>/dev/null || echo "N/A")
        v_p99=$(extract_p99 "$vf" 2>/dev/null || echo "N/A")
        u_p99=$(extract_p99 "$uf" 2>/dev/null || echo "N/A")

        printf "%-12s | %10s req/s %6s ms avg | %10s req/s %6s ms avg\n" \
            "/${endpoint}" "$v_rps" "$v_lat" "$u_rps" "$u_lat"
        printf "%-12s | %10s ms p99              | %10s ms p99\n" \
            "" "$v_p99" "$u_p99"
    done

    echo ""
    echo "Full results in: $RESULTS_DIR/"
}

trap stop_app EXIT

echo "Quarkus Servlet Performance Comparison"
echo "======================================="
echo "Date: $(date)"
echo "System: $(nproc) cores, $(free -h | awk '/Mem:/{print $2}') RAM"
echo "Java: $(java -version 2>&1 | head -1)"
echo ""

run_benchmark "vertx" ""
run_benchmark "undertow" "-Pundertow"

print_comparison
