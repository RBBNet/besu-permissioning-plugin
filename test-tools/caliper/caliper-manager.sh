#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_SUITE_DIR="$(dirname "$SCRIPT_DIR")/test-suite"
REPORTS_DIR="$SCRIPT_DIR/reports"
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --network <json>        Caliper network config (default: networks/network-plugin-on.json)
  --workload <js>         Workload module (default: workloads/transfer-constant.js)
  --tx-per-sec <n>        Target TPS rate (default: 100)
  --duration <s>          Duration in seconds (default: 60)
  --output <dir>          Report directory (default: reports/)
  --suite <name>          Run pre-defined suite: full, compare, cache, carga, duracao
  --dry-run               Show commands without executing
  -h, --help              Show this help message

Examples:
  $(basename "$0") --suite compare
  $(basename "$0") --network networks/network-plugin-on.json --tx-per-sec 200 --duration 120
  $(basename "$0") --workload workloads/permissionCheck.js --tx-per-sec 50
EOF
    exit 0
}

log_info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

check_prereqs() {
    log_info "Checking prerequisites..."

    if ! command -v caliper &>/dev/null; then
        log_error "Caliper not found. Install with: npm install -g @hyperledger/caliper-cli@0.6.0"
        exit 1
    fi

    if ! command -v docker &>/dev/null; then
        log_error "Docker not found."
        exit 1
    fi

    if ! docker ps &>/dev/null; then
        log_error "Docker is not running."
        exit 1
    fi

    log_ok "Prerequisites OK"
}

check_network() {
    log_info "Checking if Besu network is active..."

    if ! curl -s -X POST --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        http://localhost:9005 &>/dev/null; then
        log_warn "Besu network not detected on port 9005."
        log_warn "Start the network before running benchmarks:"
        log_warn "  cd $TEST_SUITE_DIR && python3 orchestrator.py -c configs/scenario-full-network.json -a start"
        return 1
    fi

    local block_number
    block_number=$(curl -s -X POST --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        http://localhost:9005 | grep -o '"result":"[^"]*"' | cut -d'"' -f4)

    if [ -n "$block_number" ] && [ "$block_number" != "0x0" ] && [ "$block_number" != "0x" ]; then
        local decimal=$((16#${block_number#0x}))
        log_ok "Network active. Current block: $decimal"
        return 0
    else
        log_warn "Network appears inactive (blockNumber=$block_number). Waiting 10s..."
        sleep 10
        return 0
    fi
}

run_benchmark() {
    local network_config="$1"
    local bench_config="$2"
    local extra_args="${3:-}"

    local network_name
    network_name=$(basename "$network_config" .json)
    local bench_name
    bench_name=$(basename "$bench_config" .yaml)

    local output_dir="$REPORTS_DIR/${TIMESTAMP}_${network_name}_${bench_name}"
    mkdir -p "$output_dir"

    log_info "Executing benchmark: $bench_name on $network_name"
    log_info "  Network: $network_config"
    log_info "  Benchmark: $bench_config"
    log_info "  Output: $output_dir"

    local cmd="caliper launch manager \
        --caliper-workspace \"$SCRIPT_DIR\" \
        --caliper-benchconfig \"$bench_config\" \
        --caliper-networkconfig \"$network_config\" \
        --caliper-reportdirectory \"$output_dir\" \
        $extra_args"

    if [ "$DRY_RUN" = "true" ]; then
        log_warn "[DRY-RUN] $cmd"
        return 0
    fi

    log_info "Command: $cmd"

    local start_time
    start_time=$(date +%s)

    eval "$cmd" 2>&1 | tee "$output_dir/caliper-output.log"

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    log_ok "Benchmark completed in ${duration}s"
    log_info "Report: $output_dir/"

    # Collect docker stats if available
    if command -v docker &>/dev/null; then
        docker stats --no-stream --format \
            '{"container":"{{.Name}}","cpu":"{{.CPUPerc}}","mem":"{{.MemUsage}}","net":"{{.NetIO}}"}' \
            > "$output_dir/docker-stats.json" 2>/dev/null || true
    fi

    return 0
}

suite_compare() {
    log_info "=== Comparative Suite: Plugin ON vs OFF ==="

    local network_on="$SCRIPT_DIR/networks/network-plugin-on.json"
    local network_off="$SCRIPT_DIR/networks/network-plugin-off.json"
    local bench="$SCRIPT_DIR/configs/benchmark-comparativo.yaml"

    if [ ! -f "$network_on" ]; then
        log_error "Network config not found: $network_on"
        exit 1
    fi

    log_info "Running with plugin ON..."
    run_benchmark "$network_on" "$bench"

    log_info "Running with plugin OFF..."
    run_benchmark "$network_off" "$bench"

    log_ok "Comparative suite completed"
}

suite_cache() {
    log_info "=== Cache Saturation Suite ==="

    local network="$SCRIPT_DIR/networks/network-plugin-on.json"
    local bench="$SCRIPT_DIR/configs/benchmark-cache.yaml"

    run_benchmark "$network" "$bench"
}

suite_carga() {
    log_info "=== Sustained Load Suite ==="

    local network="$SCRIPT_DIR/networks/network-plugin-on.json"
    local bench="$SCRIPT_DIR/configs/benchmark-carga.yaml"

    run_benchmark "$network" "$bench"
}

suite_duracao() {
    log_info "=== 30-Minute Duration Suite ==="

    local network="$SCRIPT_DIR/networks/network-plugin-on.json"
    local bench="$SCRIPT_DIR/configs/benchmark-duracao.yaml"

    run_benchmark "$network" "$bench"
}

suite_full() {
    log_info "=== Full Suite: All Scenarios ==="
    suite_compare
    suite_cache
    suite_carga
    log_ok "Full suite completed"
}

# Defaults
NETWORK_CONFIG="$SCRIPT_DIR/networks/network-plugin-on.json"
BENCH_CONFIG="$SCRIPT_DIR/configs/benchmark-comparativo.yaml"
OUTPUT_DIR="$REPORTS_DIR"
SUIT=""
DRY_RUN="false"
CUSTOM_ARGS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --network)      NETWORK_CONFIG="$2"; shift 2 ;;
        --workload)     CUSTOM_ARGS="$CUSTOM_ARGS --caliper-workload-module $2"; shift 2 ;;
        --tx-per-sec)   CUSTOM_ARGS="$CUSTOM_ARGS --caliper-tps $2"; shift 2 ;;
        --duration)     CUSTOM_ARGS="$CUSTOM_ARGS --caliper-duration $2"; shift 2 ;;
        --output)       OUTPUT_DIR="$2"; shift 2 ;;
        --suite)        SUIT="$2"; shift 2 ;;
        --dry-run)      DRY_RUN="true"; shift ;;
        -h|--help)      usage ;;
        *)              log_error "Unknown option: $1"; usage ;;
    esac
done

echo "============================================"
echo "  Hyperledger Caliper — Permissioning Benchmark"
echo "  $(date)"
echo "============================================"
echo ""

check_prereqs
check_network || exit 1

if [ -n "$SUIT" ]; then
    case "$SUIT" in
        full)    suite_full ;;
        compare) suite_compare ;;
        cache)   suite_cache ;;
        carga)   suite_carga ;;
        duracao) suite_duracao ;;
        *)       log_error "Unknown suite: $SUIT"; usage ;;
    esac
else
    run_benchmark "$NETWORK_CONFIG" "$BENCH_CONFIG" "$CUSTOM_ARGS"
fi

log_ok "Execution complete."
