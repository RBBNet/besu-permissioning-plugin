# QUICKSTART: Postman Testing in 5 Steps

## Prerequisites
- Docker running
- Python 3 installed
- Postman installed

## Step 1: Boot Network Scenario

```bash
cd test-suite/
python3 orchestrator.py -c configs/scenario-full-network.json -a start --timeout 180
```

Wait until output displays: `NETWORK UP AND OPERATIONAL!`

## Step 2: Generate Signed Transactions

```bash
cd postman/

# Install dependencies (one-time setup)
pip3 install coincurve rlp eth-utils requests pycryptodome

# Generate ADMIN transaction (permitted account)
python3 gen-signed-tx.py

# Generate UNAUTH transaction (blocked account)
python3 gen-signed-tx.py --account unauth
```

## Step 3: Configure Postman Collection

1. Open Postman
2. Click **Import** -> select `Permissioning.postman_collection.json`
3. Under collection **Variables** tab, populate:
   - `ADMIN_SIGNED_TX`: hex string from Step 2 (`--account admin`)
   - `UNAUTH_SIGNED_TX`: hex string from Step 2 (`--account unauth`)
   - `RPC_URL`: `http://localhost:9005`

## Step 4: Execute Test Suite

Run folders sequentially:

| Step | Folder | Target Validation |
| :--- | :--- | :--- |
| 1 | ⚙️ **0. Setup** | RPC connectivity & chain status |
| 2 | 🔐 **1. Account Permissioning** | Verifies ADMIN is permitted and UNAUTH is blocked |
| 3 | 🌐 **2. Node Permissioning** | Verifies node rule queries |
| 4 | 📋 **3. Contract Verification** | Verifies genesis contract bytecodes |
| 5 | 💥 **4. Chaos & Fail-Close** | Verifies fail-close behavior |

## Step 5: Stop Network

```bash
cd test-suite/
python3 orchestrator.py -a stop
```
