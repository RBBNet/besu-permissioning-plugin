#!/bin/bash
# Usage: ./secure_node.sh <project_name> <node_name> <rpc_port>

PROJECT=$1
NODE_NAME=$2
RPC_PORT=$3

if [ -z "$PROJECT" ] || [ -z "$NODE_NAME" ] || [ -z "$RPC_PORT" ]; then
    echo "Usage: ./secure_node.sh <project_name> <node_name> <rpc_port>"
    exit 1
fi

DOCKER_NET="${PROJECT,,}_default"

echo "1. Fetching bootnode credentials..."
docker run --rm -v ${PWD}/$PROJECT/.env.configs/nodes/boot1:/key-dir hyperledger/besu:25.12.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
BOOT_PUB=$(cat $PROJECT/.env.configs/nodes/boot1/key.pub | sed 's/^0x//')
BOOT_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJECT,,}_boot1_1)

echo "2. Stopping and recreating node container with Permissioning Plugin active..."
docker rm -f ${PROJECT,,}_${NODE_NAME}_1 > /dev/null 2>&1

echo "3. Booting secured node container..."
docker run -d --name ${PROJECT,,}_${NODE_NAME}_1 \
  --network $DOCKER_NET \
  -p $RPC_PORT:8545 \
  -v ${PWD}/$PROJECT/volumes/$NODE_NAME:/var/lib/besu \
  -v ${PWD}/$PROJECT/.env.configs/genesis.json:/var/lib/besu/genesis.json \
  -v ${PWD}/$PROJECT/.env.configs/log.xml:/var/lib/besu/log.xml \
  -v ${PWD}/$PROJECT/.env.configs/nodes/$NODE_NAME/key:/var/lib/besu/key \
  -v ${PWD}/$PROJECT/plugins:/opt/besu/plugins \
  -e BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS="0x0000000000000000000000000000000000008888" \
  -e BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS="0x0000000000000000000000000000000000009999" \
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

echo "SUCCESS: Node $NODE_NAME is now protected by Permissioning Plugin."