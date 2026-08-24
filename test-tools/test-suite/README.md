# 🧪 Configurable Integration Test Suite (`test-suite`)

Integration test suite for certifying the **Besu On-Chain Permissioning Plugin**.

**Execution Mode**: `TransactionSimulationService` — Besu internal plugin API. Queries smart contracts in-memory via `simulate()` with zero external HTTP dependencies.

---

## 🗂️ Directory Structure

```text
test-suite/
├── configs/                       # Customizable scenario JSON configurations
│   ├── scenario-full-network.json       # 5 nodes, 2 Besu versions, active plugin
│   ├── scenario-heterogeneous.json   # Plugin + transparent node coexistence
│   ├── scenario-e2e.json             # 1 node without plugin (deploy) + 1 with plugin (test)
│   ├── scenario-failclose-no-ingress.json     # FC-01: empty ingress -> fail-close
│   ├── scenario-failclose-invalid-ingress.json # FC-02: invalid ingress -> fail-close
│   └── scenario-mixed-network.json    # NR-01: rogue node without plugin
├── templates/                     # State cloning templates
│   ├── genesis.json               # QBFT + 6 pre-deployed contracts with storage
│   └── log.xml                    # Besu logging configuration
├── scripts/                       # Helper automation scripts
│   ├── generate-genesis.sh        # Generates genesis bytecode from forge artifacts
│   ├── deploy-contracts.sh        # Deploys post-genesis contracts
│   ├── setup-contracts.sh         # Idempotent registration of Admin + Rules in Ingress
│   └── verify-contracts.sh        # Standalone verification of on-chain contracts
├── postman/                       # HTTP-RPC Postman/Newman collections
│   ├── Permissioning.postman_collection.json
│   └── README.md
├── monitoring/                    # Prometheus + Grafana monitoring stack
│   ├── monitoring.sh              # Script to start/stop monitoring stack
│   ├── prometheus.yml             # Prometheus configuration
│   └── grafana/                   # Grafana provisioning
├── orchestrator.py                # Python CLI orchestrator with health checks
├── run.sh                         # Bash shortcut wrapper for orchestrator
├── README.md                      # Documentation
└── TEST_CREATION_GUIDE.md         # Guide for creating new test scenarios
```

---

## 🚀 Execution

### Prerequisites
* **Python 3**, **Docker**, and **Docker Compose**
* **Plugin Fat JAR** at `build/libs/besu-plugin-permissioning.jar`
* **Foundry/Cast** (`cast --version`)

### Quick Start
```bash
./test-suite/run.sh
# or:
python3 test-suite/orchestrator.py --config configs/scenario-full-network.json
```

### Orchestrator Options
```bash
python3 test-suite/orchestrator.py \
  --config configs/scenario-full-network.json \
  --action start|stop|status \
  --timeout 300 \        # Timeout waiting for block generation
  --skip-verify \        # Skip contract verification
  --skip-setup \         # Skip post-genesis setup script
  --monitoring           # Boot Prometheus + Grafana stack
```

### Monitoring Stack

To view plugin metrics in real time:

```bash
# Start network with monitoring stack enabled
python3 orchestrator.py -c configs/scenario-full-network.json -a start --monitoring

# Access Grafana Dashboard
# URL: http://localhost:3000
# User: admin / Password: admin
```

Exposed Grafana Metrics:
- `besu_permissioning_onchain_transaction_check_count_permitted_total` - Permitted transactions
- `besu_permissioning_onchain_transaction_check_count_denied_total` - Denied transactions
- `besu_permissioning_onchain_transaction_check_count_total` - Total evaluated transactions

See `monitoring/README.md` for complete details.

---

## 📦 Pre-Deployed Genesis Contracts

Genesis (`templates/genesis.json`) is generated via `scripts/generate-genesis.sh` from forge build artifacts:

| Contract | Address |
| :--- | :--- |
| Account Ingress | `0x0000000000000000000000000000000000008888` |
| Node Ingress | `0x0000000000000000000000000000000000009999` |
| Admin | `0x181a92c9b76ab7271a03b640cc172e75a0dc3484` |
| AccountRules | `0x0e9e81bb09cdd55b607373e89e3154354a925b7d` |
| NodeRules | `0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616` |

---

## 📝 Test Scenario Matrix

| Scenario | Description |
| :--- | :--- |
| **PF-01** | Admin account (on allowlist) -> Transaction APPROVED |
| **PF-02** | Unauthorized account (not on allowlist) -> Transaction BLOCKED (-32007) |
| **FC-01** | Empty Ingress -> FAIL-CLOSE (all transactions denied) |
| **FC-02** | Invalid Ingress address -> FAIL-CLOSE |
| **CV** | Multi-version Besu network (25.12 + 26.5) |
| **NR-01** | Rogue node without plugin |
| **Heterogeneous** | Coexistence test: plugin + transparent node |
| **E2E** | End-to-end contract deployment + transaction execution |
