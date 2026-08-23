# Test Creation & Orchestration Guide

This guide describes how to define, configure, and execute automated end-to-end test scenarios for the Besu On-Chain Permissioning Plugin using the Python test suite orchestrator.

---

## 1. Directory Overview

```text
test-suite/
├── configs/                   # Scenario JSON configuration files
├── templates/                 # Genesis and logging configuration templates
├── scripts/                   # Smart contract deployment and setup bash scripts
├── monitoring/                # Prometheus & Grafana stack configuration
├── orchestrator.py            # Primary Python test suite orchestrator
└── run.sh                     # Convenient execution wrapper script
```

---

## 2. Defining a Test Scenario (`configs/scenario-*.json`)

Scenarios are configured using JSON files specifying consensus parameters, Besu node versions, active plugins, and ingress contract addresses.

### Example Configuration (`configs/scenario-happy-path.json`)

```json
{
  "scenario_name": "happy-path-validation",
  "description": "Standard permissioning happy-path scenario with active plugin and valid Ingress contracts.",
  "nodes": [
    {
      "name": "node-1",
      "besu_version": "25.12.0",
      "role": "validator",
      "plugin_enabled": true,
      "account_ingress_address": "0x0000000000000000000000000000000000008888",
      "node_ingress_address": "0x0000000000000000000000000000000000009999",
      "node_contract_version": 1
    }
  ]
}
```

---

## 3. Running Scenarios

Execute scenarios using `run.sh`:

```bash
# Run standard happy-path test scenario
./run.sh -c configs/scenario-happy-path.json -a start

# Run fail-close test scenario
./run.sh -c configs/scenario-failclose-no-ingress.json -a start

# Stop running network containers
./run.sh -c configs/scenario-happy-path.json -a stop
```
