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
Uso: $(basename "$0") [OPÇÕES]

Opções:
  --network <json>        Network config do Caliper (default: networks/network-plugin-on.json)
  --workload <js>         Workload module (default: workloads/transfer-constant.js)
  --tx-per-sec <n>        Taxa alvo de TPS (default: 100)
  --duration <s>          Duração em segundos (default: 60)
  --output <dir>          Diretório de relatórios (default: reports/)
  --suite <nome>          Rodar suite pré-definida: full, compare, cache, carga, duracao
  --dry-run               Mostrar comandos sem executar
  -h, --help              Mostrar esta ajuda

Exemplos:
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
    log_info "Verificando pré-requisitos..."

    if ! command -v caliper &>/dev/null; then
        log_error "Caliper não encontrado. Instale com: npm install -g @hyperledger/caliper-cli@0.6.0"
        exit 1
    fi

    if ! command -v docker &>/dev/null; then
        log_error "Docker não encontrado."
        exit 1
    fi

    if ! docker ps &>/dev/null; then
        log_error "Docker não está rodando."
        exit 1
    fi

    log_ok "Pré-requisitos OK"
}

check_network() {
    log_info "Verificando se a rede Besu está ativa..."

    if ! curl -s -X POST --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        http://localhost:9005 &>/dev/null; then
        log_warn "Rede Besu não detectada na porta 9005."
        log_warn "Inicie a rede antes de rodar benchmarks:"
        log_warn "  cd $TEST_SUITE_DIR && python3 orchestrator.py -c configs/scenario-full-network.json -a start"
        return 1
    fi

    local block_number
    block_number=$(curl -s -X POST --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        http://localhost:9005 | grep -o '"result":"[^"]*"' | cut -d'"' -f4)

    if [ -n "$block_number" ] && [ "$block_number" != "0x0" ] && [ "$block_number" != "0x" ]; then
        local decimal=$((16#${block_number#0x}))
        log_ok "Rede ativa. Bloco atual: $decimal"
        return 0
    else
        log_warn "Rede parece inativa (blockNumber=$block_number). Aguardando 10s..."
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

    log_info "Executando benchmark: $bench_name em $network_name"
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

    log_info "Comando: $cmd"

    local start_time
    start_time=$(date +%s)

    eval "$cmd" 2>&1 | tee "$output_dir/caliper-output.log"

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    log_ok "Benchmark concluído em ${duration}s"
    log_info "Relatório: $output_dir/"

    # Coleta docker stats se disponível
    if command -v docker &>/dev/null; then
        docker stats --no-stream --format \
            '{"container":"{{.Name}}","cpu":"{{.CPUPerc}}","mem":"{{.MemUsage}}","net":"{{.NetIO}}"}' \
            > "$output_dir/docker-stats.json" 2>/dev/null || true
    fi

    return 0
}

suite_compare() {
    log_info "=== Suite Comparativa: Plugin ON vs OFF ==="

    local network_on="$SCRIPT_DIR/networks/network-plugin-on.json"
    local network_off="$SCRIPT_DIR/networks/network-plugin-off.json"
    local bench="$SCRIPT_DIR/configs/benchmark-comparativo.yaml"

    if [ ! -f "$network_on" ]; then
        log_error "Network config não encontrada: $network_on"
        exit 1
    fi

    log_info "Rodando com plugin ON..."
    run_benchmark "$network_on" "$bench"

    log_info "Rodando com plugin OFF..."
    run_benchmark "$network_off" "$bench"

    log_ok "Suite comparativa concluída"
}

suite_cache() {
    log_info "=== Suite Saturação de Cache ==="

    local network="$SCRIPT_DIR/networks/network-plugin-on.json"
    local bench="$SCRIPT_DIR/configs/benchmark-cache.yaml"

    run_benchmark "$network" "$bench"
}

suite_carga() {
    log_info "=== Suite Carga Sustentada ==="

    local network="$SCRIPT_DIR/networks/network-plugin-on.json"
    local bench="$SCRIPT_DIR/configs/benchmark-carga.yaml"

    run_benchmark "$network" "$bench"
}

suite_duracao() {
    log_info "=== Suite Duração 30min ==="

    local network="$SCRIPT_DIR/networks/network-plugin-on.json"
    local bench="$SCRIPT_DIR/configs/benchmark-duracao.yaml"

    run_benchmark "$network" "$bench"
}

suite_full() {
    log_info "=== Suite Full: Todos os cenários ==="
    suite_compare
    suite_cache
    suite_carga
    log_ok "Suite full concluída"
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
        *)              log_error "Opção desconhecida: $1"; usage ;;
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
        *)       log_error "Suite desconhecida: $SUIT"; usage ;;
    esac
else
    run_benchmark "$NETWORK_CONFIG" "$BENCH_CONFIG" "$CUSTOM_ARGS"
fi

log_ok "Execução finalizada."
