#!/bin/bash
# ============================================================================
# VERIFY CONTRACTS — Verifies if on-chain contracts are accessible
# ============================================================================
# Usage: ./verify-contracts.sh [RPC_URL]
# Environment variables:
# ACCOUNT_INGRESS, NODE_INGRESS, ADMIN_CONTRACT, ACCOUNT_RULES, NODE_RULES
# ADMIN_ADDR (to check allowlist)
# ============================================================================
set -e

RPC_URL="${1:-http://localhost:8545}"
ACCT_INGRESS="${ACCOUNT_INGRESS:-0x0000000000000000000000000000000000008888}"
NODE_INGRESS="${NODE_INGRESS:-0x0000000000000000000000000000000000009999}"
ADMIN_CONTRACT="${ADMIN_CONTRACT:-0x181a92c9b76ab7271a03b640cc172e75a0dc3484}"
ACCOUNT_RULES="${ACCOUNT_RULES:-0x0e9e81bb09cdd55b607373e89e3154354a925b7d}"
NODE_RULES="${NODE_RULES:-0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616}"
ADMIN_ADDR="${ADMIN_ADDR:-0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266}"

echo "=== VERIFY CONTRACTS ==="
echo "RPC: $RPC_URL"
echo ""

# Current block
BLOCK=$(curl -s -X POST -H 'Content-Type: application/json' \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    "$RPC_URL" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result','0x0'))")
echo "Current block: $BLOCK"

if [ "$BLOCK" = "0x0" ]; then
    echo "Network producing no blocks — checks may fail (fail-close?)."
fi

echo ""
echo "--- Contract Bytecode ---"
check_code() {
    local ADDR="$1" LABEL="$2"
    local CODE=$(curl -s -X POST -H 'Content-Type: application/json' \
        --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getCode\",\"params\":[\"$ADDR\",\"latest\"],\"id\":1}" \
        "$RPC_URL" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('result','')))")
    if [ "${CODE:-0}" -gt 4 ]; then
        echo "$LABEL ($ADDR) — $CODE chars"
    else
        echo "$LABEL ($ADDR) — NOT FOUND"
    fi
}

check_code "$ACCT_INGRESS" "Account Ingress"
check_code "$NODE_INGRESS" "Node Ingress"
check_code "$ADMIN_CONTRACT" "Admin"
check_code "$ACCOUNT_RULES" "AccountRules"
check_code "$NODE_RULES" "NodeRules"

echo ""
echo "--- Ingress Registrations ---"
ADMIN_KEY="0x61646d696e697374726174696f6e000000000000000000000000000000000000"
RULES_KEY="0x72756c6573000000000000000000000000000000000000000000000000000000"

check_registry() {
    local INGRESS="$1" KEY="$2" LABEL="$3"
    local VAL=$(cast call --rpc-url "$RPC_URL" "$INGRESS" "getContractAddress(bytes32)(address)" "$KEY" 2>/dev/null || echo "ERROR")
    if [ "$VAL" != "0x0000000000000000000000000000000000000000" ] && [ "$VAL" != "ERROR" ]; then
        echo "$LABEL → $VAL"
    else
        echo "$LABEL → NOT REGISTERED ($VAL)"
    fi
}

check_registry "$ACCT_INGRESS" "$ADMIN_KEY" "AccountIngress→Admin"
check_registry "$ACCT_INGRESS" "$RULES_KEY" "AccountIngress→Rules"
check_registry "$NODE_INGRESS" "$ADMIN_KEY" "NodeIngress→Admin"
check_registry "$NODE_INGRESS" "$RULES_KEY" "NodeIngress→Rules"

echo ""
echo "--- Allowlist ---"
PERMITTED=$(cast call --rpc-url "$RPC_URL" "$ACCOUNT_RULES" "accountPermitted(address)(bool)" "$ADMIN_ADDR" 2>/dev/null || echo "ERROR")
if [ "$PERMITTED" = "true" ]; then
    echo "Admin ($ADMIN_ADDR) PERMITTED in AccountRules"
elif [ "$PERMITTED" = "false" ]; then
    echo "Admin ($ADMIN_ADDR) NOT permitted in AccountRules"
else
    echo "Error querying allowlist: $PERMITTED"
fi

echo ""
echo "=== VERIFY CONTRACTS COMPLETED ==="
