#!/bin/bash
# ============================================================================
# SETUP CONTRACTS — Registers Admin + Rules in Ingress contracts post-genesis
# ============================================================================
# Usage:
#   Direct:  ./setup-contracts.sh
#   Via orchestrator: configure "setup_script" in scenario JSON
#
# Environment variables (inherited from orchestrator or set here):
#   RPC_URL          — http://localhost:<port>
#   ACCOUNT_INGRESS  — 0x0000000000000000000000000000000000008888
#   NODE_INGRESS     — 0x0000000000000000000000000000000000009999
#   ADMIN_CONTRACT   — Admin contract address
#   ACCOUNT_RULES    — AccountRules (GEN1) or AccountRulesV2 (GEN2)
#   NODE_RULES       — NodeRules (GEN1) or NodeRulesV2 (GEN2)
#   ADMIN_PK         — Admin private key
#   ADMIN_ADDR       — Admin address
#
# GEN2 Mode: export GEN2=true to use GEN2 addresses
# ============================================================================
set -e

RPC_URL="${RPC_URL:-http://localhost:8545}"
ACCT_INGRESS="${ACCOUNT_INGRESS:-0x0000000000000000000000000000000000008888}"
NODE_INGRESS="${NODE_INGRESS:-0x0000000000000000000000000000000000009999}"
ADMIN_CONTRACT="${ADMIN_CONTRACT:-0x181a92c9b76ab7271a03b640cc172e75a0dc3484}"
ACCOUNT_RULES="${ACCOUNT_RULES:-0x0e9e81bb09cdd55b607373e89e3154354a925b7d}"
NODE_RULES="${NODE_RULES:-0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616}"
ADMIN_PK="${ADMIN_PK:-0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80}"
ADMIN_ADDR="${ADMIN_ADDR:-0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266}"

# GEN2: overrides addresses if GEN2=true
if [ "${GEN2:-false}" = "true" ]; then
    ADMIN_CONTRACT="${ADMIN_CONTRACT_GEN2:-0x5FbDB2315678afecb367f032d93F642f64180aa3}"
    ACCOUNT_RULES="${ACCOUNT_RULES_GEN2:-0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512}"
    NODE_RULES="${NODE_RULES_GEN2:-0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0}"
fi

ADMIN_KEY="0x61646d696e697374726174696f6e000000000000000000000000000000000000"
RULES_KEY="0x72756c6573000000000000000000000000000000000000000000000000000000"

echo "=== SETUP CONTRACTS ==="
echo "RPC: $RPC_URL"
echo "Mode: ${GEN2:-GEN1}"
echo "Account Ingress: $ACCT_INGRESS"
echo "Node Ingress:    $NODE_INGRESS"
echo "Admin Contract:  $ADMIN_CONTRACT"
echo "Account Rules:   $ACCOUNT_RULES"
echo "Node Rules:      $NODE_RULES"
echo "Admin:           $ADMIN_ADDR"
echo ""

# Wait for RPC to respond
for i in $(seq 1 30); do
    BLOCK=$(curl -s -X POST -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        "$RPC_URL" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('result','0x0'))" 2>/dev/null || echo "0x0")
    if [ "$BLOCK" != "0x0" ]; then
        echo "✓ RPC responding on block $BLOCK"
        break
    fi
    sleep 2
done

# Check existing contract registrations
echo ""
echo "--- Checking existing registrations ---"
CURRENT_ADMIN_ACCT=$(cast call --rpc-url "$RPC_URL" "$ACCT_INGRESS" "getContractAddress(bytes32)(address)" "$ADMIN_KEY" 2>/dev/null || echo "0x0000000000000000000000000000000000000000")
CURRENT_RULES_ACCT=$(cast call --rpc-url "$RPC_URL" "$ACCT_INGRESS" "getContractAddress(bytes32)(address)" "$RULES_KEY" 2>/dev/null || echo "0x0000000000000000000000000000000000000000")
CURRENT_ADMIN_NODE=$(cast call --rpc-url "$RPC_URL" "$NODE_INGRESS" "getContractAddress(bytes32)(address)" "$ADMIN_KEY" 2>/dev/null || echo "0x0000000000000000000000000000000000000000")
CURRENT_RULES_NODE=$(cast call --rpc-url "$RPC_URL" "$NODE_INGRESS" "getContractAddress(bytes32)(address)" "$RULES_KEY" 2>/dev/null || echo "0x0000000000000000000000000000000000000000")

echo "  AccountIngress->Admin: $CURRENT_ADMIN_ACCT"
echo "  AccountIngress->Rules: $CURRENT_RULES_ACCT"
echo "  NodeIngress->Admin:    $CURRENT_ADMIN_NODE"
echo "  NodeIngress->Rules:    $CURRENT_RULES_NODE"

# Register Admin in Account Ingress
if [ "$CURRENT_ADMIN_ACCT" = "0x0000000000000000000000000000000000000000" ]; then
    echo ""
    echo "Registering Admin in Account Ingress..."
    cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
        "$ACCT_INGRESS" "setContractAddress(bytes32,address)" "$ADMIN_KEY" "$ADMIN_CONTRACT" \
        2>&1 | grep -E "status|transactionHash|Error" || true
else
    echo "  ✓ Admin already registered in Account Ingress"
fi

# Register Admin in Node Ingress
if [ "$CURRENT_ADMIN_NODE" = "0x0000000000000000000000000000000000000000" ]; then
    echo ""
    echo "Registering Admin in Node Ingress..."
    cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
        "$NODE_INGRESS" "setContractAddress(bytes32,address)" "$ADMIN_KEY" "$ADMIN_CONTRACT" \
        2>&1 | grep -E "status|transactionHash|Error" || true
else
    echo "  ✓ Admin already registered in Node Ingress"
fi

# Register AccountRules in Account Ingress
if [ "$CURRENT_RULES_ACCT" = "0x0000000000000000000000000000000000000000" ]; then
    echo ""
    echo "Registering AccountRules in Account Ingress..."
    cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
        "$ACCT_INGRESS" "setContractAddress(bytes32,address)" "$RULES_KEY" "$ACCOUNT_RULES" \
        2>&1 | grep -E "status|transactionHash|Error" || true
else
    echo "  ✓ AccountRules already registered in Account Ingress"
fi

# Register NodeRules in Node Ingress
if [ "$CURRENT_RULES_NODE" = "0x0000000000000000000000000000000000000000" ]; then
    echo ""
    echo "Registering NodeRules in Node Ingress..."
    cast send --rpc-url "$RPC_URL" --private-key "$ADMIN_PK" \
        "$NODE_INGRESS" "setContractAddress(bytes32,address)" "$RULES_KEY" "$NODE_RULES" \
        2>&1 | grep -E "status|transactionHash|Error" || true
else
    echo "  ✓ NodeRules already registered in Node Ingress"
fi

# Final verification
echo ""
echo "--- Final verification ---"
echo -n "  AccountIngress->Admin: "
cast call --rpc-url "$RPC_URL" "$ACCT_INGRESS" "getContractAddress(bytes32)(address)" "$ADMIN_KEY" 2>/dev/null
echo -n "  AccountIngress->Rules: "
cast call --rpc-url "$RPC_URL" "$ACCT_INGRESS" "getContractAddress(bytes32)(address)" "$RULES_KEY" 2>/dev/null
echo -n "  NodeIngress->Admin:    "
cast call --rpc-url "$RPC_URL" "$NODE_INGRESS" "getContractAddress(bytes32)(address)" "$ADMIN_KEY" 2>/dev/null
echo -n "  NodeIngress->Rules:    "
cast call --rpc-url "$RPC_URL" "$NODE_INGRESS" "getContractAddress(bytes32)(address)" "$RULES_KEY" 2>/dev/null

echo ""
echo "=== SETUP CONTRACTS COMPLETED ==="
