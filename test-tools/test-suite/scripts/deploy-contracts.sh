#!/bin/bash
# ============================================================================
# DEPLOY CONTRACTS — Deploy Admin + AccountRules + NodeRules via forge create
# ============================================================================
# Executed by orchestrator after network starts (as setup_script).
# Environment variables:
#   RPC_URL, ACCOUNT_INGRESS, NODE_INGRESS, ADMIN_PK, ADMIN_ADDR
# ============================================================================
set -e

RPC_URL="${RPC_URL:-http://localhost:8545}"
ACCT_INGRESS="${ACCOUNT_INGRESS:-0x0000000000000000000000000000000000008888}"
NODE_INGRESS="${NODE_INGRESS:-0x0000000000000000000000000000000000009999}"
ADMIN_PK="${ADMIN_PK:-0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80}"
ADMIN_ADDR="${ADMIN_ADDR:-0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266}"

SMART_CONTRACTS_DIR="$(cd "$(dirname "$0")/../../../../smart-contracts" && pwd)"

ADMIN_KEY="0x61646d696e697374726174696f6e000000000000000000000000000000000000"
RULES_KEY="0x72756c6573000000000000000000000000000000000000000000000000000000"

echo "=== DEPLOY CONTRACTS ==="
echo "RPC: $RPC_URL"
echo "Smart contracts: $SMART_CONTRACTS_DIR"
echo ""

# Wait for RPC
for i in $(seq 1 60); do
    BLOCK=$(curl -s -X POST -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        "$RPC_URL" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('result','0x0'))" 2>/dev/null || echo "0x0")
    if [ "$BLOCK" != "0x0" ]; then
        echo "✓ RPC responding on block $BLOCK"
        break
    fi
    sleep 2
done

# Deploy Admin
echo ""
echo "--- Deploy Admin ---"
ADMIN_OUT=$(forge create --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
    --gas-limit 5000000 --broadcast \
    "$SMART_CONTRACTS_DIR/src/Admin.sol:Admin" 2>&1)
ADMIN_DEPLOYED=$(echo "$ADMIN_OUT" | grep -oP 'Deployed to: \K0x[a-fA-F0-9]{40}')
if [ -z "$ADMIN_DEPLOYED" ]; then
    echo "ERROR deploying Admin:"
    echo "$ADMIN_OUT"
    exit 1
fi
echo "  Admin: $ADMIN_DEPLOYED"
sleep 3

# Deploy AccountRules
echo ""
echo "--- Deploy AccountRules ---"
ACCT_RULES_OUT=$(forge create --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
    --gas-limit 5000000 --broadcast \
    "$SMART_CONTRACTS_DIR/src/AccountRules.sol:AccountRules" \
    --constructor-args "$ACCT_INGRESS" 2>&1)
ACCT_RULES=$(echo "$ACCT_RULES_OUT" | grep -oP 'Deployed to: \K0x[a-fA-F0-9]{40}')
if [ -z "$ACCT_RULES" ]; then
    echo "ERROR deploying AccountRules:"
    echo "$ACCT_RULES_OUT"
    exit 1
fi
echo "  AccountRules: $ACCT_RULES"
sleep 3

# Deploy NodeRules
echo ""
echo "--- Deploy NodeRules ---"
NODE_RULES_OUT=$(forge create --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
    --gas-limit 5000000 --broadcast \
    "$SMART_CONTRACTS_DIR/src/NodeRules.sol:NodeRules" \
    --constructor-args "$NODE_INGRESS" 2>&1)
NODE_RULES=$(echo "$NODE_RULES_OUT" | grep -oP 'Deployed to: \K0x[a-fA-F0-9]{40}')
if [ -z "$NODE_RULES" ]; then
    echo "ERROR deploying NodeRules:"
    echo "$NODE_RULES_OUT"
    exit 1
fi
echo "  NodeRules: $NODE_RULES"
sleep 3

# Register Admin in Ingresses
echo ""
echo "--- Registering Admin in Ingresses ---"
cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" --gas-limit 200000 \
    "$ACCT_INGRESS" "setContractAddress(bytes32,address)" "$ADMIN_KEY" "$ADMIN_DEPLOYED" \
    2>&1 | grep -E "transactionHash|status|Error" || true
sleep 2

cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" --gas-limit 200000 \
    "$NODE_INGRESS" "setContractAddress(bytes32,address)" "$ADMIN_KEY" "$ADMIN_DEPLOYED" \
    2>&1 | grep -E "transactionHash|status|Error" || true
sleep 2

# Register Rules in Ingresses
echo ""
echo "--- Registering Rules in Ingresses ---"
cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" --gas-limit 200000 \
    "$ACCT_INGRESS" "setContractAddress(bytes32,address)" "$RULES_KEY" "$ACCT_RULES" \
    2>&1 | grep -E "transactionHash|status|Error" || true
sleep 2

cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" --gas-limit 200000 \
    "$NODE_INGRESS" "setContractAddress(bytes32,address)" "$RULES_KEY" "$NODE_RULES" \
    2>&1 | grep -E "transactionHash|status|Error" || true
sleep 2

# Add Admin to AccountRules allowlist
echo ""
echo "--- Adding Admin to allowlist ---"
cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" --gas-limit 200000 \
    "$ACCT_RULES" "addAccount(address)" "$ADMIN_ADDR" \
    2>&1 | grep -E "transactionHash|status|Error" || true
sleep 3

# Final verification
echo ""
echo "=== FINAL VERIFICATION ==="
echo -n "  AccountIngress->Admin: "
cast call --rpc-url "$RPC_URL" "$ACCT_INGRESS" "getContractAddress(bytes32)(address)" "$ADMIN_KEY" 2>/dev/null
echo -n "  AccountIngress->Rules: "
cast call --rpc-url "$RPC_URL" "$ACCT_INGRESS" "getContractAddress(bytes32)(address)" "$RULES_KEY" 2>/dev/null
echo -n "  NodeIngress->Admin:    "
cast call --rpc-url "$RPC_URL" "$NODE_INGRESS" "getContractAddress(bytes32)(address)" "$ADMIN_KEY" 2>/dev/null
echo -n "  NodeIngress->Rules:    "
cast call --rpc-url "$RPC_URL" "$NODE_INGRESS" "getContractAddress(bytes32)(address)" "$RULES_KEY" 2>/dev/null
echo -n "  Admin accountPermitted: "
cast call --rpc-url "$RPC_URL" "$ACCT_RULES" "accountPermitted(address)(bool)" "$ADMIN_ADDR" 2>/dev/null

echo ""
echo "=== CONTRACTS DEPLOYED ==="
echo "ADMIN_CONTRACT=$ADMIN_DEPLOYED"
echo "ACCOUNT_RULES=$ACCT_RULES"
echo "NODE_RULES=$NODE_RULES"
