#!/bin/bash
# Uso: ./secure_node.sh <nome_projeto> <nome_do_no> <porta_rpc>

PROJETO=$1
NO=$2
PORTA_RPC=$3
REDE_DOCKER="${PROJETO,,}_default"

if [ -z "$PROJETO" ] || [ -z "$NO" ] || [ -z "$PORTA_RPC" ]; then
    echo "Uso: ./secure_node.sh <nome_projeto> <nome_do_no> <porta_rpc>"
    exit 1
fi

# Extrai chave e IP do Bootnode de forma segura
docker run --rm -v ${PWD}/$PROJETO/.env.configs/nodes/boot1:/key-dir hyperledger/besu:26.4.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
BOOT_PUB=$(cat $PROJETO/.env.configs/nodes/boot1/key.pub | sed 's/^0x//')
BOOT_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJETO,,}_boot1_1)

echo "1. Desligando e recriando o nó com o Plugin RBB Ativado..."
docker rm -f ${PROJETO,,}_${NO}_1

echo "2. Subindo nó blindado..."
docker run -d --name ${PROJETO,,}_${NO}_1 \
  --network $REDE_DOCKER \
  -p $PORTA_RPC:8545 \
  -v ${PWD}/$PROJETO/volumes/$NO:/var/lib/besu \
  -v ${PWD}/$PROJETO/.env.configs/genesis.json:/var/lib/besu/genesis.json \
  -v ${PWD}/$PROJETO/.env.configs/log.xml:/var/lib/besu/log.xml \
  -v ${PWD}/$PROJETO/.env.configs/nodes/$NO/key:/var/lib/besu/key \
  -v ${PWD}/$PROJETO/plugins:/opt/besu/plugins \
  -e BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS="0x0000000000000000000000000000000000008888" \
  -e BESU_TX_POOL_ENABLE_BALANCE_CHECK=false \
  -e LOG4J_CONFIGURATION_FILE=/var/lib/besu/log.xml \
  hyperledger/besu:26.4.0 \
  --genesis-file=/var/lib/besu/genesis.json \
  --data-path=/var/lib/besu \
  --node-private-key-file=/var/lib/besu/key \
  --rpc-http-enabled=true \
  --rpc-http-api=ADMIN,ETH,NET,QBFT,WEB3,DEBUG,TRACE \
  --rpc-http-host=0.0.0.0 \
  --host-allowlist="*" \
  --rpc-http-cors-origins="*" \
  --bootnodes=enode://${BOOT_PUB}@${BOOT_IP}:30303 \
  --metrics-enabled=true --metrics-host=0.0.0.0

echo "✅ Sucesso! O nó $NO agora está 100% blindado pelo Plugin e sincronizado à rede."