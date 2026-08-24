# Hyperledger Caliper × Permissioning Plugin Integration

Architecture document for performance benchmarking of the Besu + on-chain permissioning plugin ecosystem.

**Date**: 2026-07-01 (original) → 2026-07-02 (implemented)
**Status**: Implemented — ready for validation

---

## 1. Overview

### 1.1 Goal

Integrate **Hyperledger Caliper** into the plugin test ecosystem to produce empirical performance data under load. Benchmarks cover two core axes:

| Axis | Question |
|------|----------|
| **Comparative** | What is the plugin overhead? (TPS with plugin vs without plugin) |
| **Characterization** | How does the plugin behave under load? (cache-hit rate, p50/p99 latency, saturation) |

### 1.2 What is Caliper

Hyperledger Caliper is a blockchain benchmark framework. Key capabilities:

- **Configurable Workloads**: target submission rate (TPS), duration, worker counts
- **Connectors**: Ethereum/Besu (JSON-RPC) adapter, Fabric, etc.
- **Metrics**: observed throughput, latency (min/max/avg/p50/p99), success/error rates
- **Monitoring**: Node CPU, RAM, I/O via Prometheus (optional)
- **Reporting**: HTML reports with charts, raw CSV export

---

## 2. Architecture

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Caliper Manager                       │
│  ┌──────────┐  ┌────────────┐  ┌────────────────────┐   │
│  │ Workload │  │  Monitor   │  │  Report Generator  │   │
│  │  Engine  │  │ (Prometheus)│  │  (HTML + CSV)      │   │
│  └────┬─────┘  └─────┬──────┘  └────────┬───────────┘   │
│       │              │                  │               │
│       │    ┌─────────┴─────────┐        │               │
│       │    │  Resource Monitor │        │               │
│       │    │  (docker stats)   │        │               │
│       │    └─────────┬─────────┘        │               │
└───────┼──────────────┼──────────────────┼───────────────┘
        │              │                  │
        ▼              ▼                  ▼
┌──────────────────────────────────────────────────────────┐
│              Rede Besu (Docker Compose)                   │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │Validator │  │Validator │  │Validator │  │Validator │ │
│  │   #1     │  │   #2     │  │   #3     │  │   #4     │ │
│  │ 25.12.0  │  │ 25.12.0  │  │ 26.5.0   │  │ 26.5.0   │ │
│  │ +plugin  │  │ +plugin  │  │ +plugin  │  │ +plugin  │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘ │
│       │             │             │             │        │
│       └─────────────┴─────────────┴─────────────┘        │
│                         │ QBFT                           │
│                    ┌────┴────┐                           │
│                    │ RPC Node│◄── Caliper (JSON-RPC)     │
│                    │  :9005  │                           │
│                    └─────────┘                           │
└──────────────────────────────────────────────────────────┘
```

### 2.2 Execution Flow

```
1. test-suite/orchestrator.py -> spins up Besu network (5 nodes, active plugin)
2. scripts/setup-contracts.sh -> registers Admin + Rules in Ingress contracts
3. Caliper Manager:
   a. Reads network configuration (RPC endpoints)
   b. Reads workload definitions (contract, rate, duration)
   c. Dispatches workers sending txs via JSON-RPC
   d. Collects latency and throughput metrics
   e. (Optional) Collects docker stats + Prometheus metrics
4. Caliper generates HTML report
5. test-suite/orchestrator.py --action stop -> tears down network
```

### 2.3 Directory Structure

```
test-tools/caliper/
├── README.md                       # This document
├── caliper-manager.sh              # Main benchmark orchestration script
├── networks/
│   ├── network-plugin-on.json      # Network with plugin (RPC :9005)
│   └── network-plugin-off.json     # Network without plugin (RPC :9006)
├── workloads/
│   ├── transfer-constant.js        # ETH transfers — constant rate
│   ├── transfer-ramp.js            # ETH transfers — linear ramp
│   ├── permissionCheck.js          # transactionAllowed() contract calls
│   └── mixed-workload.js           # 80% transfer + 20% contract calls
├── contracts/
│   └── SimpleStorage.sol           # Contrato alvo para benchmarks
├── configs/
│   ├── benchmark-comparativo.yaml  # Scenario: plugin ON vs OFF (50/100/200/400 + ramp)
│   ├── benchmark-carga.yaml        # Scenario: sustained load (100 TPS)
│   ├── benchmark-duracao.yaml      # Scenario: 30-minute duration (100 TPS)
│   └── benchmark-cache.yaml        # Scenario: cache saturation (1/10/100/1000 senders)
├── reports/                        # Generated report output (gitignored)
└── docker/
    ├── Dockerfile.caliper          # Imagem Caliper customizada
    └── docker-compose.caliper.yml  # Caliper standalone
```

### 2.4 Implementation Status

| Componente | Status | Notas |
|:---|:---:|:---|
| Caliper CLI 0.6.0 |  | Instalado globalmente. Deprecation warnings mas funcional |
| Caliper bind (besu) |  | `caliper bind --caliper-bind-sut besu:latest` OK |
| network-plugin-on.json |  | RPC :9005 (plugin ativo) |
| network-plugin-off.json |  | RPC :9006 (sem plugin) |
| transfer-constant.js |  | 4 workers, 20 contas, valor 1 wei |
| transfer-ramp.js |  | Linear rate 10→500 TPS |
| permissionCheck.js |  | Selector `0x936421d5` (transactionAllowed) |
| mixed-workload.js |  | 80/20 transfer/contract |
| benchmark-comparativo.yaml |  | 4 rounds: 50/100/200 TPS + ramp |
| benchmark-carga.yaml |  | 18000 tx, 100 TPS |
| benchmark-duracao.yaml |  | 180000 tx, 100 TPS, 30min |
| benchmark-cache.yaml |  | 4 rounds: 1/10/100/1000 contas |
| Dockerfile.caliper |  | node:18-slim + caliper-cli |
| docker-compose.caliper.yml |  | network_mode: host |
| caliper-manager.sh |  | Orquestrador com suites: full, compare, cache, carga, duracao |
| SimpleStorage.sol |  | Contrato benchmark |
| reports/.gitignore |  | Pendente |

---

## 3. Workloads

### 3.1 Workload 1: ETH Transfer (transfer-constant.js)

Simple ETH transfer transactions between accounts. Measures base network throughput.

```javascript
// Configurable parameters
{
  txPerSec: [50, 100, 200, 400],  // taxas alvo
  duration: 60,                    // segundos por rodada
  workers: 4,
  accounts: 20                     // pool de contas remetentes
}
```

**Primary metric**: Max TPS with/without plugin.

### 3.2 Workload 2: Rampa de Carga (transfer-ramp.js)

Progressively increases transaction submission rate until network saturates.

```javascript
{
  startRate: 10,     // TPS inicial
  endRate: 500,      // TPS final
  stepRate: 10,      // incremento a cada intervalo
  stepDuration: 30,  // segundos por patamar
}
```

**Primary metric**: Saturation threshold with/without plugin.

### 3.3 Workload 3: Permission Verification (permissionCheck.js)

Calls `transactionAllowed(address,address,uint256,uint256,uint256,bytes)` on AccountRules via `eth_call`. Measures permission check latency.

```javascript
{
  txPerSec: [50, 100, 200],
  duration: 60,
  targetContract: "0x0e9e81bb09cdd55b607373e89e3154354a925b7d", // AccountRules
}
```

**Primary metric**: p50/p99 on-chain verification latency, plugin cache hit rate.

### 3.4 Workload 4: Carga Mista (mixed-workload.js)

80% ETH transfers + 20% contract calls (SimpleStorage.set).

```javascript
{
  txPerSec: [100, 200],
  duration: 120,
  mixRatio: { transfer: 0.8, contractCall: 0.2 }
}
```

**Primary metric**: Behavior under realistic heterogeneous workload.

---

## 4. Benchmark Scenarios

### 4.1 Comparative Scenario (plugin ON vs OFF)

| Parâmetro | Valor |
|-----------|-------|
| Networks | `network-plugin-on` -> 5 nodes with plugin; `network-plugin-off` -> 5 nodes without plugin |
| Workloads | transfer-constant (50/100/200/400 TPS), transfer-ramp |
| Metrics | TPS, latency, CPU/RAM utilization |
| Repetitions | 3 runs per configuration |
| Hypothesis | Plugin adds ~5-15% overhead in TPS; additional latency < 50ms |

### 4.2 Sustained Load Scenario

| Parâmetro | Valor |
|-----------|-------|
| Duration | 30 minutes |
| Taxa | 100 TPS constante |
| Metrics | Degradation over time, stability |
| Hypothesis | Permission cache maintains stable performance after warm-up |

### 4.3 Cache Saturation Scenario

| Parâmetro | Valor |
|-----------|-------|
| Senders | 1, 10, 100, 1000 distinct accounts |
| Taxa | 100 TPS |
| Metrics | Cache hit rate, latency by sender bucket |
| Hypothesis | 1-block cache reduces latency for repeated senders; cache hit > 90% with low unique senders |

### 4.4 Multi-Version Besu Scenario

| Parâmetro | Valor |
|-----------|-------|
| Network | 2 Besu 25.12.0 nodes + 3 Besu 26.5.0 nodes (plugin enabled on all) |
| Workload | transfer-constant (100 TPS) |
| Metrics | Per-node TPS, cross-version latency |
| Hypothesis | Homogeneous performance across Besu versions |

---

## 5. Metrics and Data Collection

### 5.1 Caliper Native Metrics

| Metric | Description |
|---------|-----------|
| `tps` | Confirmed transactions per second |
| `latency_min/max/avg` | Confirmation latency (ms) |
| `latency_p50/p90/p99` | Latency percentiles |
| `succ_rate` | Taxa de sucesso (%) |
| `fail_rate` | Taxa de falha (%) |
| `send_rate` | Taxa de envio real (TPS) |

### 5.2 Plugin Metrics (Prometheus)

| Metric | Description |
|---------|-----------|
| `besu_permissioning_onchain_transaction_check_count_total` | Total de tx verificadas |
| `besu_permissioning_onchain_transaction_check_count_permitted_total` | Tx aprovadas |
| `besu_permissioning_onchain_transaction_check_count_denied_total` | Tx rejeitadas |
| `besu_permissioning_onchain_node_check_count_total` | Conexões P2P verificadas |
| `besu_permissioning_onchain_node_check_count_permitted_total` | Conexões P2P aprovadas |
| `besu_permissioning_onchain_node_check_count_denied_total` | Conexões P2P rejeitadas |

### 5.3 Infrastructure Metrics (docker stats)

| Metric | Description |
|---------|-----------|
| `cpu_percent` | Per-node CPU usage |
| `mem_usage_mb` | Per-node memory usage |
| `net_io_kb` | Per-node network I/O |
| `block_height` | Block height (network health) |

### 5.4 Coleta e Armazenamento

```
reports/
└── 2026-07-01_14-30-00_plugin-on_transfer-constant/
    ├── report.html          # Visual report
    ├── metrics.csv          # Raw data (time series)
    ├── summary.json         # Aggregated summary
    ├── plugin-metrics.json  # Plugin metrics (Prometheus)
    ├── docker-stats.json    # Per-node CPU/RAM/I/O
    └── logs/                # Caliper + node logs
```

---

## 6. Integration with test-suite

### 6.1 Pipeline Completo

```bash
# 1. Build plugin
cd besu-plugin-permissioning
./gradlew shadowJar

# 2. Start network with plugin (test-suite)
cd test-tools/test-suite
python3 orchestrator.py -c configs/scenario-full-network.json -a start

# 3. Contract setup
bash scripts/setup-contracts.sh

# 4. Benchmark via Caliper
cd ../caliper
./caliper-manager.sh --network networks/network-plugin-on.json \
                     --workload workloads/transfer-constant.js \
                     --tx-per-sec 100 \
                     --duration 60 \
                     --output reports/

# 5. Benchmark sem plugin (rede alternativa)
cd ../test-suite
python3 orchestrator.py -c configs/scenario-full-network.json -a stop
python3 orchestrator.py -c configs/scenario-mixed-network.json -a start
cd ../caliper
./caliper-manager.sh --network networks/network-plugin-off.json \
                     --workload workloads/transfer-constant.js \
                     --tx-per-sec 100 \
                     --duration 60

# 6. Derruba tudo
cd ../test-suite
python3 orchestrator.py -c configs/scenario-mixed-network.json -a stop
```

### 6.2 Full Automation

```bash
# Main script: runs all scenarios sequentially
cd test-tools/caliper
./caliper-manager.sh --suite full     # all scenarios
./caliper-manager.sh --suite compare  # comparative plugin ON/OFF only
./caliper-manager.sh --suite cache    # cache saturation only
```

---

## 7. Infraestrutura Docker

### 7.1 Imagem Caliper

```dockerfile
# docker/Dockerfile.caliper
FROM node:18-slim

RUN npm install -g @hyperledger/caliper-cli@0.6.0
RUN caliper bind --caliper-bind-sut besu:latest

WORKDIR /caliper
COPY configs/ ./configs/
COPY workloads/ ./workloads/
COPY networks/ ./networks/

ENTRYPOINT ["caliper", "launch", "manager"]
```

### 7.2 Docker Compose Integrado

```yaml
# docker/docker-compose.caliper.yml
services:
  caliper:
    build:
      context: ..
      dockerfile: docker/Dockerfile.caliper
    volumes:
      - ../configs:/caliper/configs:ro
      - ../workloads:/caliper/workloads:ro
      - ../reports:/caliper/reports
    network_mode: "host"  # acesso direto ao RPC localhost:9005
    command: >
      --caliper-workspace /caliper
      --caliper-benchconfig configs/benchmark-comparativo.yaml
      --caliper-networkconfig networks/network-plugin-on.json
```

---

## 8. Caliper Configuration

### 8.1 Network Config (network-plugin-on.json)

```json
{
  "caliper": {
    "blockchain": "ethereum",
    "command": {
      "start": "echo 'Network already started by test-suite'",
      "end": "echo 'Network will be stopped by test-suite'"
    }
  },
  "ethereum": {
    "url": "http://localhost:9005",
    "contractDeployerAddress": "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    "contractDeployerPrivateKey": "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    "fromAddress": "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    "fromPrivateKey": "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    "transactionConfirmationBlocks": 1,
    "timeout": 30
  }
}
```

### 8.2 Benchmark Config (benchmark-comparativo.yaml)

```yaml
test:
  workers:
    number: 4
  rounds:
    - label: transfer-50tps
      txNumber: 3000
      rateControl:
        type: fixed-rate
        opts:
          tps: 50
      workload:
        module: workloads/transfer-constant.js
        arguments:
          accounts: 20
          value: 1  # 1 wei

    - label: transfer-100tps
      txNumber: 6000
      rateControl:
        type: fixed-rate
        opts:
          tps: 100
      workload:
        module: workloads/transfer-constant.js
        arguments:
          accounts: 20
          value: 1

    - label: transfer-200tps
      txNumber: 12000
      rateControl:
        type: fixed-rate
        opts:
          tps: 200
      workload:
        module: workloads/transfer-constant.js
        arguments:
          accounts: 20
          value: 1

    - label: transfer-ramp
      txNumber: 15000
      rateControl:
        type: linear-rate
        opts:
          startingTps: 10
          finishingTps: 500
      workload:
        module: workloads/transfer-ramp.js
        arguments:
          accounts: 20
          value: 1
```

---

## 9. Workload JavaScript (Exemplo)

### 9.1 transfer-constant.js

```javascript
'use strict';

const { WorkloadModuleBase } = require('@hyperledger/caliper-core');

class TransferConstantWorkload extends WorkloadModuleBase {
    constructor() {
        super();
        this.accountIndex = 0;
    }

    async initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext) {
        await super.initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext);
        this.accounts = roundArguments.accounts || 20;
        this.value = roundArguments.value || 1;
    }

    async submitTransaction() {
        this.accountIndex = (this.accountIndex + 1) % this.accounts;

        const targetAddr = '0x70997970C51812dc3A010C7d01b50e0d17dc79C8'; // UNAUTH account
        const fromAddr = '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266';   // ADMIN (allowed)

        const tx = {
            from: fromAddr,
            to: targetAddr,
            value: this.value,
            gas: 21000,
            gasPrice: 0
        };

        await this.sutAdapter.sendTransaction(tx);
    }
}

function createWorkloadModule() {
    return new TransferConstantWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
```

---

## 10. Expected Metrics (Reference)

Estimated values for evaluation baseline (base: Besu 25.12.0, QBFT, 5 validators, typical hardware):

| Scenario | Metric | Without Plugin | With Plugin | Overhead |
|---------|---------|-----------|------------|----------|
| 50 TPS constant | Avg TPS | ~48 | ~45 | ~6% |
| 100 TPS constant | Avg TPS | ~95 | ~88 | ~7% |
| 200 TPS constant | Avg TPS | ~180 | ~160 | ~11% |
| Ramp 10->500 | Saturation | ~350 TPS | ~300 TPS | ~14% |
| 50 TPS, 1000 remetentes | Cache-hit rate | N/A | ~15% | — |
| 50 TPS, 10 remetentes | Cache-hit rate | N/A | ~92% | — |
| 30min load, 100 TPS | p50 Latency | ~200ms | ~250ms | +50ms |

---

## 11. Installation and Setup

### 11.1 Prerequisites

| Component | Version | Status |
|:---|:---|:---|
| Node.js | ≥ 18 | v22.13.0  |
| npm | ≥ 9 | 10.9.2  |
| Docker | ≥ 20 | 28.1.1  |
| Besu images | 25.12.0, 26.5.0 |  |
| Caliper CLI | 0.6.0 | Instalado  |

### 11.2 Installation (pre-installed in this environment)

```bash
# Instalar Caliper CLI
npm install -g @hyperledger/caliper-cli@0.6.0

# Bind para Besu
caliper bind --caliper-bind-sut besu:latest
```

### 11.3 Uso

```bash
# 1. Sobe a rede Besu com plugin
cd test-tools/test-suite
python3 orchestrator.py -c configs/scenario-full-network.json -a start --timeout 180

# 2. Roda benchmark comparativo
cd ../caliper
./caliper-manager.sh --suite compare

# 3. Run cache saturation
./caliper-manager.sh --suite cache

# 4. Roda tudo
./caliper-manager.sh --suite full

# 5. Para a rede
cd ../test-suite
python3 orchestrator.py -c configs/scenario-full-network.json -a stop
```

---

## 12. Next Steps

1. **Validation**: Run comparative benchmark on real Besu network and validate metrics
2. **SimpleStorage ABI**: Gerar ABI do SimpleStorage.sol via `solc --abi`
3. **CI/CD**: Integrate benchmarks into pipeline (weekly or release runs)
4. **Reports**: Add `reports/.gitignore` to avoid committing generated reports
