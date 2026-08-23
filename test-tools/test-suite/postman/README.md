# 📮 Postman & Newman API Testing Guide

Postman collection and utilities for validating on-chain permissioning rules against a live Hyperledger Besu network.

## 🚀 Execution Workflow

### 1. Boot Network Scenario

```bash
cd test-suite/
python3 orchestrator.py -c configs/scenario-full-network.json -a start --timeout 180
```

### 2. Generate Signed Transactions

```bash
cd postman/

# Install dependencies (one-time)
pip3 install coincurve rlp eth-utils requests pycryptodome

# Generate signed tx for ADMIN account (permitted)
python3 gen-signed-tx.py

# Generate signed tx for UNAUTH account (denied)
python3 gen-signed-tx.py --account unauth
```

### 3. Configure Postman Variables

1. Import `Permissioning.postman_collection.json` into Postman.
2. Set collection variables:
   - `ADMIN_SIGNED_TX`: hex string output from `python3 gen-signed-tx.py --account admin`
   - `UNAUTH_SIGNED_TX`: hex string output from `python3 gen-signed-tx.py --account unauth`
   - `RPC_URL`: `http://localhost:9005`

### 4. Run Test Folders

| Order | Folder | Description |
| :--- | :--- | :--- |
| 1 | ⚙️ **0. Setup** | Validates RPC connectivity, chainId, block height, and account nonces |
| 2 | 🔐 **1. Account Permissioning** | Validates PF-01 (admin accepted) and PF-02 (unauth denied) |
| 3 | 🌐 **2. Node Permissioning** | Queries NodeRules contract status |
| 4 | 📋 **3. Contract Verification** | Verifies bytecode for pre-deployed contracts |
| 5 | 💥 **4. Chaos & Fail-Close** | Tests fail-close scenarios |

---

## 🤖 Automated CLI Execution via Newman

```bash
npm install -g newman

ADMIN_TX=$(python3 gen-signed-tx.py --json | jq -r '.signed_raw_tx')
UNAUTH_TX=$(python3 gen-signed-tx.py --account unauth --json | jq -r '.signed_raw_tx')

newman run Permissioning.postman_collection.json \
  --env-var "RPC_URL=http://localhost:9005" \
  --env-var "ADMIN_SIGNED_TX=$ADMIN_TX" \
  --env-var "UNAUTH_SIGNED_TX=$UNAUTH_TX"
```
