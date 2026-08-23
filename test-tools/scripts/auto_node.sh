#!/bin/bash
# Automated Node Provisioning Script
# Usage: ./auto_node.sh <project_name> <node_name> <rpc_port>

NODE_RULES_ADDRESS_DEFAULT="0x38cF23C52Bb4B13F051Aec09580a2dE845a7FA35"

PROJECT=$1
NEW_NODE=$2
RPC_PORT=$3

if [ -z "$PROJECT" ] || [ -z "$NEW_NODE" ] || [ -z "$RPC_PORT" ]; then
    echo "Usage: ./auto_node.sh <project_name> <node_name> <rpc_port>"
    echo "Example: ./auto_node.sh testNetwork validator14 10017"
    exit 1
fi

DOCKER_NET="${PROJECT,,}_default"

if [ ! -f "$PROJECT/.env.configs/genesis.json" ]; then
    echo "❌ FATAL ERROR: Project not found or invalid directory structure."
    exit 1
fi

echo "====================================================="
echo "🚀 AUTOMATED NODE PROVISIONING: $NEW_NODE"
echo "====================================================="

echo "1. Creating directories and generating local node key..."
mkdir -p $PROJECT/volumes/$NEW_NODE
mkdir -p $PROJECT/.env.configs/nodes/$NEW_NODE

if [ ! -f "$PROJECT/.env.configs/nodes/$NEW_NODE/key" ]; then
    openssl rand -hex 32 > $PROJECT/.env.configs/nodes/$NEW_NODE/key
fi

docker run --rm -v ${PWD}/$PROJECT/.env.configs/nodes/$NEW_NODE:/key-dir hyperledger/besu:25.12.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
PUB_KEY=$(cat $PROJECT/.env.configs/nodes/$NEW_NODE/key.pub | sed 's/^0x//')

docker run --rm -v ${PWD}/$PROJECT/.env.configs/nodes/boot1:/key-dir hyperledger/besu:25.12.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
BOOT_PUB=$(cat $PROJECT/.env.configs/nodes/boot1/key.pub | sed 's/^0x//')
BOOT_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJECT,,}_boot1_1)

echo "2. Preparing Hardhat environment..."
CURRENT_DIR=$(pwd)
HARDHAT_DIR="$PROJECT/Permissioning/gen02"
JS_FILE="scripts/accept_node.js"

cd "$HARDHAT_DIR" || exit

if [ ! -f "$JS_FILE" ]; then
    echo "   [!] accept_node.js script not found. Generating template script..."
    mkdir -p scripts
    cat << 'EOF' > $JS_FILE
const { ethers } = require("hardhat");

async function main() {
    const NODE_RULES_ADDRESS = process.env.NODE_RULES_ADDRESS;
    if (!NODE_RULES_ADDRESS) throw new Error("NODE_RULES_ADDRESS environment variable is required.");

    const [adminSigner] = await ethers.getSigners();
    const pubKey = process.env.ENODE_PUB_KEY;
    const nodeName = process.env.NODE_NAME || "New Node";
    const nodeType = parseInt(process.env.NODE_TYPE || "1");

    if (!pubKey || pubKey.length !== 128) throw new Error("Invalid Enode key.");

    const enodeHigh = "0x" + pubKey.slice(0, 64);
    const enodeLow = "0x" + pubKey.slice(64, 128);

    const NodeRules = await ethers.getContractFactory("NodeRulesV2Impl");
    const nodeRulesContract = NodeRules.attach(NODE_RULES_ADDRESS).connect(adminSigner);

    const tx = await nodeRulesContract.addLocalNode(enodeHigh, enodeLow, nodeType, nodeName, { gasPrice: 0 });
    const receipt = await tx.wait();
    
    if (receipt.status === 1) console.log("   ✅ SUCCESS: Node authorized on-chain.");
    else throw new Error("EVM transaction execution failed.");
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
EOF
fi

echo "3. Triggering Hardhat for On-Chain Authorization..."
export NODE_RULES_ADDRESS="$NODE_RULES_ADDRESS_DEFAULT"
export ENODE_PUB_KEY="$PUB_KEY"
export NODE_NAME="$NEW_NODE"
export NODE_TYPE="1"

npx hardhat run "$JS_FILE" --network local_besu
HARDHAT_STATUS=$?

cd "$CURRENT_DIR" || exit

if [ $HARDHAT_STATUS -ne 0 ]; then
    echo "❌ ERROR: Hardhat authorization failed. Node creation aborted."
    exit 1
fi

echo "4. Starting node container $NEW_NODE (Initial Sync Phase)..."
docker run -d --name ${PROJECT,,}_${NEW_NODE}_1 \
  --network $DOCKER_NET \
  -p $RPC_PORT:8545 \
  -v ${PWD}/$PROJECT/volumes/$NEW_NODE:/var/lib/besu \
  -v ${PWD}/$PROJECT/.env.configs/genesis.json:/var/lib/besu/genesis.json \
  -v ${PWD}/$PROJECT/.env.configs/log.xml:/var/lib/besu/log.xml \
  -v ${PWD}/$PROJECT/.env.configs/nodes/$NEW_NODE/key:/var/lib/besu/key \
  -e BESU_TX_POOL_ENABLE_BALANCE_CHECK=false \
  -e LOG4J_CONFIGURATION_FILE=/var/lib/besu/log.xml \
  hyperledger/besu:25.12.0 \
  --genesis-file=/var/lib/besu/genesis.json \
  --data-path=/var/lib/besu \
  --node-private-key-file=/var/lib/besu/key \
  --rpc-http-enabled=true \
  --rpc-http-api=ADMIN,ETH,NET,QBFT,WEB3 \
  --rpc-http-host=0.0.0.0 \
  --host-allowlist="*" \
  --rpc-http-cors-origins="*" \
  --bootnodes=enode://${BOOT_PUB}@${BOOT_IP}:30303 \
  --metrics-enabled=true --metrics-host=0.0.0.0 > /dev/null 2>&1

echo "⏳ Waiting 15 seconds for initial block header sync..."
sleep 15

echo "5. Restarting node with Permissioning Plugin active..."
docker rm -f ${PROJECT,,}_${NEW_NODE}_1 > /dev/null 2>&1

docker run -d --name ${PROJECT,,}_${NEW_NODE}_1 \
  --network $DOCKER_NET \
  -p $RPC_PORT:8545 \
  -v ${PWD}/$PROJECT/volumes/$NEW_NODE:/var/lib/besu \
  -v ${PWD}/$PROJECT/.env.configs/genesis.json:/var/lib/besu/genesis.json \
  -v ${PWD}/$PROJECT/.env.configs/log.xml:/var/lib/besu/log.xml \
  -v ${PWD}/$PROJECT/.env.configs/nodes/$NEW_NODE/key:/var/lib/besu/key \
  -v ${PWD}/$PROJECT/plugins:/opt/besu/plugins \
  -e BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS="0x0000000000000000000000000000000000008888" \
  -e BESU_TX_POOL_ENABLE_BALANCE_CHECK=false \
  -e LOG4J_CONFIGURATION_FILE=/var/lib/besu/log.xml \
  hyperledger/besu:25.12.0 \
  --genesis-file=/var/lib/besu/genesis.json \
  --data-path=/var/lib/besu \
  --node-private-key-file=/var/lib/besu/key \
  --rpc-http-enabled=true \
  --rpc-http-api=ADMIN,ETH,NET,QBFT,WEB3 \
  --rpc-http-host=0.0.0.0 \
  --host-allowlist="*" \
  --rpc-http-cors-origins="*" \
  --bootnodes=enode://${BOOT_PUB}@${BOOT_IP}:30303 \
  --metrics-enabled=true --metrics-host=0.0.0.0 > /dev/null 2>&1

echo "====================================================="
echo "✅ NODE PROVISIONING SUCCESSFUL!"
echo "Node $NEW_NODE is running on RPC port $RPC_PORT."
echo "Authorized on-chain and protected by Permissioning Plugin."
echo "====================================================="