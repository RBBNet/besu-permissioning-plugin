#!/bin/bash
# Generate genesis.json with pre-deployed contracts + storage from forge artifacts
# Usage: ./generate-genesis.sh [output_path]
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SMART_CONTRACTS="$(cd "$SCRIPT_DIR/../../../../smart-contracts" && pwd)"
OUTPUT="$(realpath "${1:-$SCRIPT_DIR/../templates/genesis.json}")"

echo "=== GENERATE GENESIS ==="
echo "Smart contracts: $SMART_CONTRACTS"
echo "Output: $OUTPUT"

cd "$SMART_CONTRACTS"

# Build contracts with storage layout
forge build --extra-output storageLayout --skip script 2>&1 | tail -3

# Generate genesis via Python
SMART_CONTRACTS="$SMART_CONTRACTS" OUTPUT="$OUTPUT" python3 << 'PYEOF'
import json, hashlib, os, sys

SMART_CONTRACTS = os.environ.get("SMART_CONTRACTS", os.getcwd())
OUTPUT = os.environ.get("OUTPUT", "genesis.json")

def keccak256(data: bytes) -> bytes:
    h = hashlib.sha3_256()
    h.update(data)
    return h.digest()

def load_artifact(contract_name):
    path = os.path.join(SMART_CONTRACTS, f"out/{contract_name}.sol/{contract_name}.json")
    with open(path) as f:
        return json.load(f)

def get_bytecode(artifact):
    # Use DEPLOYED bytecode (runtime), not init bytecode.
    # Genesis stores runtime code directly; init code includes constructor
    # which would corrupt the contract if stored without execution.
    return artifact['deployedBytecode']['object']

def compute_slot(base_slot, key_bytes):
    """Compute mapping entry slot: keccak256(key . base_slot)"""
    slot_bytes = int(base_slot).to_bytes(32, 'big')
    return '0x' + keccak256(key_bytes + slot_bytes).hex()

def compute_array_element_slot(base_slot, index):
    """Compute dynamic array element slot: keccak256(base_slot) + index"""
    slot_bytes = int(base_slot).to_bytes(32, 'big')
    base = int.from_bytes(keccak256(slot_bytes), 'big')
    return '0x' + (base + index).to_bytes(32, 'big').hex()

# Load artifacts
acct_ingress_artifact = load_artifact("AccountIngress")
node_ingress_artifact = load_artifact("NodeIngress")
admin_artifact = load_artifact("Admin")
acct_rules_artifact = load_artifact("AccountRules")
node_rules_artifact = load_artifact("NodeRules")

# Addresses (deterministic from deploy order)
ACCT_INGRESS = "0x0000000000000000000000000000000000008888"
NODE_INGRESS = "0x0000000000000000000000000000000000009999"
ADMIN_CONTRACT = "0x181a92c9b76ab7271a03b640cc172e75a0dc3484"
ACCT_RULES = "0x0e9e81bb09cdd55b607373e89e3154354a925b7d"
NODE_RULES = "0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616"
ADMIN_ADDR = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"

# Keys
ADMIN_KEY = bytes.fromhex("61646d696e697374726174696f6e000000000000000000000000000000000000")  # "administration"
RULES_KEY = bytes.fromhex("72756c6573000000000000000000000000000000000000000000000000000000")  # "rules"

admin_addr_bytes = bytes.fromhex(ADMIN_ADDR[2:])

# ============================================================
# Account Ingress Storage (layout: Ingress + AccountIngress)
# slot 0: RULES_CONTRACT (bytes32)
# slot 1: ADMIN_CONTRACT (bytes32)
# slot 2: registry (mapping(bytes32 => address))
# slot 3: contractKeys (bytes32[] dynamic)
# slot 4: indexOf (mapping(bytes32 => uint256))
# slot 5: version (uint256)
# ============================================================
acct_ingress_storage = {
    "0x0000000000000000000000000000000000000000000000000000000000000000": "0x" + RULES_KEY.hex(),
    "0x0000000000000000000000000000000000000000000000000000000000000001": "0x" + ADMIN_KEY.hex(),
    # registry[ADMIN_KEY] -> Admin contract address
    compute_slot(2, ADMIN_KEY): "0x" + ADMIN_CONTRACT[2:].zfill(64),
    # registry[RULES_KEY] -> AccountRules address
    compute_slot(2, RULES_KEY): "0x" + ACCT_RULES[2:].zfill(64),
    # contractKeys length = 2
    "0x0000000000000000000000000000000000000000000000000000000000000003": "0x0000000000000000000000000000000000000000000000000000000000000002",
    # contractKeys[0] = RULES_CONTRACT value
    compute_array_element_slot(3, 0): "0x" + RULES_KEY.hex(),
    # contractKeys[1] = ADMIN_CONTRACT value
    compute_array_element_slot(3, 1): "0x" + ADMIN_KEY.hex(),
    # indexOf[RULES_KEY] = 1 (1-indexed)
    compute_slot(4, RULES_KEY): "0x0000000000000000000000000000000000000000000000000000000000000001",
    # indexOf[ADMIN_KEY] = 2
    compute_slot(4, ADMIN_KEY): "0x0000000000000000000000000000000000000000000000000000000000000002",
    # version = 1
    "0x0000000000000000000000000000000000000000000000000000000000000005": "0x0000000000000000000000000000000000000000000000000000000000000001",
}

# ============================================================
# Node Ingress Storage
# ============================================================
node_ingress_storage = {
    "0x0000000000000000000000000000000000000000000000000000000000000000": "0x" + RULES_KEY.hex(),
    "0x0000000000000000000000000000000000000000000000000000000000000001": "0x" + ADMIN_KEY.hex(),
    compute_slot(2, ADMIN_KEY): "0x" + ADMIN_CONTRACT[2:].zfill(64),
    compute_slot(2, RULES_KEY): "0x" + NODE_RULES[2:].zfill(64),
    "0x0000000000000000000000000000000000000000000000000000000000000003": "0x0000000000000000000000000000000000000000000000000000000000000002",
    compute_array_element_slot(3, 0): "0x" + RULES_KEY.hex(),
    compute_array_element_slot(3, 1): "0x" + ADMIN_KEY.hex(),
    compute_slot(4, RULES_KEY): "0x0000000000000000000000000000000000000000000000000000000000000001",
    compute_slot(4, ADMIN_KEY): "0x0000000000000000000000000000000000000000000000000000000000000002",
    "0x0000000000000000000000000000000000000000000000000000000000000005": "0x0000000000000000000000000000000000000000000000000000000000000001",
}

# ============================================================
# Admin Storage
# slot 0: allowlist (address[] dynamic)
# slot 1: indexOf (mapping(address => uint256))
# ============================================================
admin_storage = {
    # allowlist length = 1
    "0x0000000000000000000000000000000000000000000000000000000000000000": "0x0000000000000000000000000000000000000000000000000000000000000001",
    # allowlist[0] = admin address
    compute_array_element_slot(0, 0): "0x" + ADMIN_ADDR[2:].zfill(64),
    # indexOf[admin_addr] = 1 (1-indexed)
    compute_slot(1, admin_addr_bytes): "0x0000000000000000000000000000000000000000000000000000000000000001",
}

# ============================================================
# AccountRules Storage
# slot 0: allowlist (address[] dynamic)
# slot 1: indexOf (mapping(address => uint256))
# slot 2: readOnlyMode (bool)
# slot 3: version (uint256)
# slot 4: ingressContract (address)
# ============================================================
acct_rules_storage = {
    "0x0000000000000000000000000000000000000000000000000000000000000000": "0x0000000000000000000000000000000000000000000000000000000000000001",
    compute_array_element_slot(0, 0): "0x" + ADMIN_ADDR[2:].zfill(64),
    compute_slot(1, admin_addr_bytes): "0x0000000000000000000000000000000000000000000000000000000000000001",
    "0x0000000000000000000000000000000000000000000000000000000000000002": "0x0000000000000000000000000000000000000000000000000000000000000000",  # readOnlyMode = false
    "0x0000000000000000000000000000000000000000000000000000000000000003": "0x0000000000000000000000000000000000000000000000000000000000000001",  # version = 1
    "0x0000000000000000000000000000000000000000000000000000000000000004": "0x" + ACCT_INGRESS[2:].zfill(64),
}

# ============================================================
# NodeRules Storage
# slot 0: allowlist (enode[] dynamic)
# slot 1: indexOf (mapping(uint256 => uint256))
# slot 2: owner (address) + readOnlyMode (bool) [PACKED]
# slot 3: version (uint256)
# slot 4: nodeIngressContract (address)
# ============================================================
node_rules_storage = {
    # allowlist empty for now (no nodes pre-authorized)
    "0x0000000000000000000000000000000000000000000000000000000000000000": "0x0000000000000000000000000000000000000000000000000000000000000000",
    # slot 2: owner = admin + readOnlyMode = false (packed)
    "0x0000000000000000000000000000000000000000000000000000000000000002": "0x" + ADMIN_ADDR[2:].zfill(64),  # owner=admin, no readOnlyMode
    "0x0000000000000000000000000000000000000000000000000000000000000003": "0x0000000000000000000000000000000000000000000000000000000000000001",
    "0x0000000000000000000000000000000000000000000000000000000000000004": "0x" + NODE_INGRESS[2:].zfill(64),
}

# ============================================================
# Build Genesis
# ============================================================
genesis = {
    "config": {
        "chainId": 12120014,
        "constantinopleFixBlock": 0,
        "berlinBlock": 0,
        "londonBlock": 0,
        "cancunTime": 0,
        "shanghaiTime": 0,
        "qbft": {
            "blockperiodseconds": 2,
            "epochlength": 30000,
            "requesttimeoutseconds": 8
        },
        "contractSizeLimit": 2147483647
    },
    "nonce": "0x0",
    "gasLimit": "0xF42400",
    "difficulty": "0x1",
    "mixHash": "0x63746963616c2062797a616e74696e65206661756c7420746f6c6572616e6365",
    "coinbase": "0x0000000000000000000000000000000000000000",
    "alloc": {
        ACCT_INGRESS: {
            "comment": "Account Ingress",
            "balance": "0",
            "code": get_bytecode(acct_ingress_artifact),
            "storage": acct_ingress_storage
        },
        NODE_INGRESS: {
            "comment": "Node Ingress",
            "balance": "0",
            "code": get_bytecode(node_ingress_artifact),
            "storage": node_ingress_storage
        },
        ADMIN_ADDR: {
            "balance": "100000000000000000000000000"
        },
        ADMIN_CONTRACT: {
            "comment": "Admin (predeployed)",
            "balance": "0",
            "code": get_bytecode(admin_artifact),
            "storage": admin_storage
        },
        ACCT_RULES: {
            "comment": "AccountRules (predeployed, ingress=0x8888)",
            "balance": "0",
            "code": get_bytecode(acct_rules_artifact),
            "storage": acct_rules_storage
        },
        NODE_RULES: {
            "comment": "NodeRules (predeployed, ingress=0x9999)",
            "balance": "0",
            "code": get_bytecode(node_rules_artifact),
            "storage": node_rules_storage
        }
    },
    "extraData": "0xf83aa00000000000000000000000000000000000000000000000000000000000000000d594f39fd6e51aad88f6f4ce6ab8827279cfffb92266c080c0",
    "timestamp": "0x65dca409"
}

# Write
with open(OUTPUT, 'w') as f:
    json.dump(genesis, f, indent=2)

# Summary
alloc = genesis['alloc']
print(f"\nGenesis generated: {OUTPUT}")
print(f"Chain ID: {genesis['config']['chainId']}")
print(f"QBFT block period: {genesis['config']['qbft']['blockperiodseconds']}s")
print(f"\nAlloc ({len(alloc)} entries):")
for addr, data in alloc.items():
    comment = data.get('comment', '')
    storage_count = len(data.get('storage', {}))
    code_len = len(data.get('code', ''))
    balance = data.get('balance', '0')
    extra = f'balance={balance} ETH' if balance != '0' else f'code={code_len} chars, storage={storage_count} keys'
    print(f"  {addr}  {comment:45s}  {extra}")
PYEOF
echo "=== DONE ==="