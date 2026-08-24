#!/bin/bash
# Usage: ./add_node.sh <project_name> <node_name> <rpc_port>

PROJECT=$1
NEW_NODE=$2
RPC_PORT=$3

if [ -z "$PROJECT" ] || [ -z "$NEW_NODE" ] || [ -z "$RPC_PORT" ]; then
    echo "Usage: ./add_node.sh <project_name> <node_name> <rpc_port>"
    exit 1
fi

DOCKER_NET="${PROJECT,,}_default"

# Safety check
if [ ! -f "$PROJECT/.env.configs/genesis.json" ]; then
    echo "FATAL ERROR: File $PROJECT/.env.configs/genesis.json NOT FOUND!"
    exit 1
fi

if [ ! -f "$PROJECT/.env.configs/log.xml" ]; then
    echo "FATAL ERROR: File $PROJECT/.env.configs/log.xml NOT FOUND!"
    exit 1
fi

echo "1. Creating directories for $NEW_NODE in $PROJECT..."
mkdir -p $PROJECT/volumes/$NEW_NODE
mkdir -p $PROJECT/.env.configs/nodes/$NEW_NODE

if [ ! -f "$PROJECT/.env.configs/nodes/$NEW_NODE/key" ]; then
    openssl rand -hex 32 > $PROJECT/.env.configs/nodes/$NEW_NODE/key
fi

echo "2. Exporting node keys safely..."
docker run --rm -v ${PWD}/$PROJECT/.env.configs/nodes/$NEW_NODE:/key-dir hyperledger/besu:25.12.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
PUB_KEY=$(cat $PROJECT/.env.configs/nodes/$NEW_NODE/key.pub | sed 's/^0x//')

docker run --rm -v ${PWD}/$PROJECT/.env.configs/nodes/$NEW_NODE:/key-dir hyperledger/besu:25.12.0 public-key export-address --node-private-key-file=/key-dir/key --to=/key-dir/address > /dev/null 2>&1
NODE_ADDRESS=$(cat $PROJECT/.env.configs/nodes/$NEW_NODE/address)

docker run --rm -v ${PWD}/$PROJECT/.env.configs/nodes/boot1:/key-dir hyperledger/besu:25.12.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
BOOT_PUB=$(cat $PROJECT/.env.configs/nodes/boot1/key.pub | sed 's/^0x//')
BOOT_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJECT,,}_boot1_1)

echo "======================================================================"
echo "MANDATORY STEP: AUTHORIZE NODE ON-CHAIN"
echo "In another terminal, export node credentials and run contract setup:"
echo ""
echo "export ENODE_PUB_KEY=\"$PUB_KEY\""
echo "export NODE_NAME=\"$NEW_NODE\""
echo "export NODE_TYPE=\"1\""
echo ""
echo "======================================================================"
echo "SAVE THIS NODE ADDRESS IF PROMOTING TO VALIDATOR LATER:"
echo "Address: $NODE_ADDRESS"
echo "======================================================================"
read -p "Press ENTER after contract authorization succeeds..."

echo "3. Starting node $NEW_NODE in initial synchronization mode..."
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
  --metrics-enabled=true --metrics-host=0.0.0.0

echo "Node $NEW_NODE created on port $RPC_PORT and synchronizing block history."
echo "After sync completes, execute: ./secure_node.sh $PROJECT $NEW_NODE $RPC_PORT"