#!/bin/bash
# Usage: ./vote.sh <rpc_port> <validator_address> <true|false>

RPC_PORT=$1
TARGET_ADDRESS=$2
VOTE=$3

if [ -z "$RPC_PORT" ] || [ -z "$TARGET_ADDRESS" ] || [ -z "$VOTE" ]; then
    echo "Usage: ./vote.sh <rpc_port> <validator_address> <true|false>"
    echo "Example: ./vote.sh 10001 0x123...abc true"
    exit 1
fi

echo "Submitting QBFT consensus vote..."
curl -X POST --data "{\"jsonrpc\":\"2.0\",\"method\":\"qbft_proposeValidatorVote\",\"params\":[\"$TARGET_ADDRESS\", $VOTE],\"id\":1}" \
  -H "Content-Type: application/json" http://localhost:$RPC_PORT
echo ""