# Integração Hyperledger Caliper × Plugin de Permissionamento

Documento de arquitetura para benchmark de performance do ecossistema Besu + plugin de permissionamento on-chain.

**Data**: 2026-07-01 (original) → 02/07/2026 (implementado)
**Status**: Implementado — pronto para validação

---

## 1. Visão Geral

### 1.1 Objetivo

Integrar o **Hyperledger Caliper** ao ecossistema de testes do plugin para gerar dados reais de performance sob carga. Os benchmarks cobrem dois eixos:

| Eixo | Pergunta |
|------|----------|
| **Comparativo** | Qual o overhead do plugin? (TPS com plugin vs sem plugin) |
| **Caracterização** | Como o plugin se comporta sob carga? (cache-hit, latência p50/p99, saturação) |

### 1.2 O que é o Caliper

Hyperledger Caliper é uma ferramenta de benchmark para blockchains. Principais capacidades:

- **Workloads configuráveis**: taxa de envio (TPS alvo), duração, número de workers
- **Connectors**: adaptadores para Besu/Ethereum (JSON-RPC), Fabric, etc.
- **Métricas**: throughput real, latência (min/max/avg/p50/p99), taxa de sucesso/erro
- **Monitoramento**: CPU, RAM, I/O dos nós via Prometheus (opcional)
- **Relatório**: HTML com gráficos, CSV para análise

---

## 2. Arquitetura

### 2.1 Diagrama de Componentes

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

### 2.2 Fluxo de Execução

```
1. test-suite/orchestrator.py → sobe rede Besu (5 nós, plugin ativo)
2. scripts/setup-contracts.sh → registra Admin + Rules nos Ingresses
3. Caliper Manager:
   a. Lê configuração de rede (endpoints RPC)
   b. Lê definições de workload (contrato, taxa, duração)
   c. Dispara workers que enviam tx via JSON-RPC
   d. Coleta métricas de latência e throughput
   e. (Opcional) Coleta docker stats + métricas Prometheus
4. Caliper gera relatório HTML
5. test-suite/orchestrator.py --action stop → derruba rede
```

### 2.3 Estrutura de Diretórios

```
test-tools/caliper/
├── README.md                       # Este documento
├── caliper-manager.sh              # Script principal (orquestra benchmarks)
├── networks/
│   ├── network-plugin-on.json      # Rede com plugin (RPC :9005)
│   └── network-plugin-off.json     # Rede sem plugin (RPC :9006)
├── workloads/
│   ├── transfer-constant.js        # Transferências ETH — taxa constante
│   ├── transfer-ramp.js            # Transferências ETH — rampa crescente
│   ├── permissionCheck.js          # Chamadas transactionAllowed() via contrato
│   └── mixed-workload.js           # 80% transfer + 20% contract call
├── contracts/
│   └── SimpleStorage.sol           # Contrato alvo para benchmarks
├── configs/
│   ├── benchmark-comparativo.yaml  # Cenário: plugin ON vs OFF (50/100/200/400 + ramp)
│   ├── benchmark-carga.yaml        # Cenário: carga sustentada (100 TPS)
│   ├── benchmark-duracao.yaml      # Cenário: duração 30min (100 TPS)
│   └── benchmark-cache.yaml        # Cenário: saturação de cache (1/10/100/1000 remetentes)
├── reports/                        # Relatórios gerados (gitignored)
└── docker/
    ├── Dockerfile.caliper          # Imagem Caliper customizada
    └── docker-compose.caliper.yml  # Caliper standalone
```

### 2.4 Status da Implementação

| Componente | Status | Notas |
|:---|:---:|:---|
| Caliper CLI 0.6.0 | ✅ | Instalado globalmente. Deprecation warnings mas funcional |
| Caliper bind (besu) | ✅ | `caliper bind --caliper-bind-sut besu:latest` OK |
| network-plugin-on.json | ✅ | RPC :9005 (plugin ativo) |
| network-plugin-off.json | ✅ | RPC :9006 (sem plugin) |
| transfer-constant.js | ✅ | 4 workers, 20 contas, valor 1 wei |
| transfer-ramp.js | ✅ | Linear rate 10→500 TPS |
| permissionCheck.js | ✅ | Selector `0x936421d5` (transactionAllowed) |
| mixed-workload.js | ✅ | 80/20 transfer/contract |
| benchmark-comparativo.yaml | ✅ | 4 rounds: 50/100/200 TPS + ramp |
| benchmark-carga.yaml | ✅ | 18000 tx, 100 TPS |
| benchmark-duracao.yaml | ✅ | 180000 tx, 100 TPS, 30min |
| benchmark-cache.yaml | ✅ | 4 rounds: 1/10/100/1000 contas |
| Dockerfile.caliper | ✅ | node:18-slim + caliper-cli |
| docker-compose.caliper.yml | ✅ | network_mode: host |
| caliper-manager.sh | ✅ | Orquestrador com suites: full, compare, cache, carga, duracao |
| SimpleStorage.sol | ✅ | Contrato benchmark |
| reports/.gitignore | ❌ | Pendente |

---

## 3. Workloads

### 3.1 Workload 1: Transferência ETH (transfer-constant.js)

Transações simples de transferência de ETH entre contas. Mede o throughput base da rede.

```javascript
// Parâmetros configuráveis
{
  txPerSec: [50, 100, 200, 400],  // taxas alvo
  duration: 60,                    // segundos por rodada
  workers: 4,
  accounts: 20                     // pool de contas remetentes
}
```

**Métrica principal**: TPS máximo com/sem plugin.

### 3.2 Workload 2: Rampa de Carga (transfer-ramp.js)

Aumenta progressivamente a taxa de envio até saturar a rede.

```javascript
{
  startRate: 10,     // TPS inicial
  endRate: 500,      // TPS final
  stepRate: 10,      // incremento a cada intervalo
  stepDuration: 30,  // segundos por patamar
}
```

**Métrica principal**: Ponto de saturação com/sem plugin.

### 3.3 Workload 3: Verificação de Permissão (permissionCheck.js)

Chama `transactionAllowed(address,address,uint256,uint256,uint256,bytes)` no contrato AccountRules via `eth_call`. Mede latência da verificação de permissão.

```javascript
{
  txPerSec: [50, 100, 200],
  duration: 60,
  targetContract: "0x0e9e81bb09cdd55b607373e89e3154354a925b7d", // AccountRules
}
```

**Métrica principal**: Latência p50/p99 da verificação on-chain, taxa de cache-hit do plugin.

### 3.4 Workload 4: Carga Mista (mixed-workload.js)

80% transferências ETH + 20% chamadas a contrato (SimpleStorage.set).

```javascript
{
  txPerSec: [100, 200],
  duration: 120,
  mixRatio: { transfer: 0.8, contractCall: 0.2 }
}
```

**Métrica principal**: Comportamento sob carga heterogênea realista.

---

## 4. Cenários de Benchmark

### 4.1 Cenário Comparativo (plugin ON vs OFF)

| Parâmetro | Valor |
|-----------|-------|
| Redes | `network-plugin-on` → 5 nós com plugin; `network-plugin-off` → 5 nós sem plugin |
| Workloads | transfer-constant (50/100/200/400 TPS), transfer-ramp |
| Métricas | TPS, latência, uso CPU/RAM |
| Repetições | 3 por configuração |
| Hipótese | Plugin adiciona ~5-15% overhead em TPS; latência adicional < 50ms |

### 4.2 Cenário Carga Sustentada

| Parâmetro | Valor |
|-----------|-------|
| Duração | 30 minutos |
| Taxa | 100 TPS constante |
| Métricas | Degradação ao longo do tempo, estabilidade |
| Hipótese | Cache de permissão mantém performance estável após warm-up |

### 4.3 Cenário Saturação de Cache

| Parâmetro | Valor |
|-----------|-------|
| Remetentes | 1, 10, 100, 1000 endereços distintos |
| Taxa | 100 TPS |
| Métricas | Taxa de cache-hit, latência por bucket de remetentes |
| Hipótese | Cache de 1 bloco reduz latência para remetentes repetidos; cache-hit > 90% com poucos remetentes |

### 4.4 Cenário Multi-Versão Besu

| Parâmetro | Valor |
|-----------|-------|
| Rede | 2 nós 25.12.0 + 3 nós 26.5.0 (plugin em todos) |
| Workload | transfer-constant (100 TPS) |
| Métricas | TPS por nó, latência cross-version |
| Hipótese | Performance homogênea entre versões Besu |

---

## 5. Métricas e Coleta

### 5.1 Métricas Caliper (nativas)

| Métrica | Descrição |
|---------|-----------|
| `tps` | Transações confirmadas por segundo |
| `latency_min/max/avg` | Latência de confirmação (ms) |
| `latency_p50/p90/p99` | Percentis de latência |
| `succ_rate` | Taxa de sucesso (%) |
| `fail_rate` | Taxa de falha (%) |
| `send_rate` | Taxa de envio real (TPS) |

### 5.2 Métricas do Plugin (Prometheus)

| Métrica | Descrição |
|---------|-----------|
| `onchain_transaction_check_count` | Total de tx verificadas |
| `onchain_transaction_check_count_permitted` | Tx aprovadas |
| `onchain_transaction_check_count_denied` | Tx rejeitadas |
| `onchain_transaction_cache_hit_count` | Cache hits |
| `onchain_node_check_count` | Conexões P2P verificadas |

### 5.3 Métricas de Infra (docker stats)

| Métrica | Descrição |
|---------|-----------|
| `cpu_percent` | Uso de CPU por nó |
| `mem_usage_mb` | Uso de memória por nó |
| `net_io_kb` | I/O de rede por nó |
| `block_height` | Altura do bloco (saúde da rede) |

### 5.4 Coleta e Armazenamento

```
reports/
└── 2026-07-01_14-30-00_plugin-on_transfer-constant/
    ├── report.html          # Relatório visual
    ├── metrics.csv          # Dados brutos (série temporal)
    ├── summary.json         # Resumo agregado
    ├── plugin-metrics.json  # Métricas do plugin (Prometheus)
    ├── docker-stats.json    # CPU/RAM/I/O por nó
    └── logs/                # Logs do Caliper + nós
```

---

## 6. Integração com test-suite

### 6.1 Pipeline Completo

```bash
# 1. Build do plugin
cd plugin-permissioned-rbb-integra
./gradlew shadowJar

# 2. Sobe rede com plugin (test-suite)
cd test-tools/test-suite
python3 orchestrator.py -c configs/cenario-valioso.json -a start

# 3. Setup de contratos
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
python3 orchestrator.py -c configs/cenario-valioso.json -a stop
python3 orchestrator.py -c configs/cenario-rede-mista.json -a start
cd ../caliper
./caliper-manager.sh --network networks/network-plugin-off.json \
                     --workload workloads/transfer-constant.js \
                     --tx-per-sec 100 \
                     --duration 60

# 6. Derruba tudo
cd ../test-suite
python3 orchestrator.py -c configs/cenario-rede-mista.json -a stop
```

### 6.2 Automação Completa

```bash
# Script principal: roda todos os cenários em sequência
cd test-tools/caliper
./caliper-manager.sh --suite full     # todos os cenários
./caliper-manager.sh --suite compare  # só comparativo plugin ON/OFF
./caliper-manager.sh --suite cache    # só saturação de cache
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

## 8. Configuração Caliper

### 8.1 Network Config (network-plugin-on.json)

```json
{
  "caliper": {
    "blockchain": "ethereum",
    "command": {
      "start": "echo 'Rede já iniciada pelo test-suite'",
      "end": "echo 'Rede será derrubada pelo test-suite'"
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

## 10. Métricas Esperadas (Referência)

Valores estimados para guiar análise (base: Besu 25.12.0, QBFT, 5 validadores, hardware típico):

| Cenário | Métrica | Sem Plugin | Com Plugin | Overhead |
|---------|---------|-----------|------------|----------|
| 50 TPS constante | TPS médio | ~48 | ~45 | ~6% |
| 100 TPS constante | TPS médio | ~95 | ~88 | ~7% |
| 200 TPS constante | TPS médio | ~180 | ~160 | ~11% |
| Rampa 10→500 | Saturação | ~350 TPS | ~300 TPS | ~14% |
| 50 TPS, 1000 remetentes | Cache-hit rate | N/A | ~15% | — |
| 50 TPS, 10 remetentes | Cache-hit rate | N/A | ~92% | — |
| Carga 30min, 100 TPS | Latência p50 | ~200ms | ~250ms | +50ms |

---

## 11. Instalação e Setup

### 11.1 Pré-requisitos

| Componente | Versão | Status |
|:---|:---|:---|
| Node.js | ≥ 18 | v22.13.0 ✅ |
| npm | ≥ 9 | 10.9.2 ✅ |
| Docker | ≥ 20 | 28.1.1 ✅ |
| Besu images | 25.12.0, 26.5.0 | ✅ |
| Caliper CLI | 0.6.0 | Instalado ✅ |

### 11.2 Instalação (já feito neste ambiente)

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
python3 orchestrator.py -c configs/cenario-valioso.json -a start --timeout 180

# 2. Roda benchmark comparativo
cd ../caliper
./caliper-manager.sh --suite compare

# 3. Roda saturação de cache
./caliper-manager.sh --suite cache

# 4. Roda tudo
./caliper-manager.sh --suite full

# 5. Para a rede
cd ../test-suite
python3 orchestrator.py -c configs/cenario-valioso.json -a stop
```

---

## 12. Próximos Passos

1. **Validação**: Rodar benchmark comparativo com rede Besu real e validar métricas
2. **SimpleStorage ABI**: Gerar ABI do SimpleStorage.sol via `solc --abi`
3. **CI/CD**: Integrar benchmarks ao pipeline (execução semanal ou por release)
4. **Relatórios**: Adicionar `reports/.gitignore` para evitar commit de relatórios
