#!/bin/bash
# Uso: ./add_node.sh <nome_projeto> <nome_do_no> <porta_rpc>

PROJETO=$1
NOVO_NO=$2
PORTA_RPC=$3

if [ -z "$PROJETO" ] || [ -z "$NOVO_NO" ] || [ -z "$PORTA_RPC" ]; then
    echo "Uso: ./add_node.sh <nome_projeto> <nome_do_no> <porta_rpc>"
    exit 1
fi

REDE_DOCKER="${PROJETO,,}_default"

# ========================================================
# TRAVA DE SEGURANÇA: Impede o Docker de criar pastas falsas
# ========================================================
if [ ! -f "$PROJETO/.env.configs/genesis.json" ]; then
    echo "❌ ERRO FATAL: Arquivo $PROJETO/.env.configs/genesis.json NÃO ENCONTRADO!"
    echo "Verifique se o nome do projeto está correto (letras maiúsculas/minúsculas importam)."
    exit 1
fi

if [ ! -f "$PROJETO/.env.configs/log.xml" ]; then
    echo "❌ ERRO FATAL: Arquivo $PROJETO/.env.configs/log.xml NÃO ENCONTRADO!"
    exit 1
fi
# ========================================================

echo "1. Criando diretórios para $NOVO_NO em $PROJETO..."
mkdir -p $PROJETO/volumes/$NOVO_NO
mkdir -p $PROJETO/.env.configs/nodes/$NOVO_NO

if [ ! -f "$PROJETO/.env.configs/nodes/$NOVO_NO/key" ]; then
    openssl rand -hex 32 > $PROJETO/.env.configs/nodes/$NOVO_NO/key
fi

echo "2. Extraindo chaves de forma segura..."
docker run --rm -v ${PWD}/$PROJETO/.env.configs/nodes/$NOVO_NO:/key-dir hyperledger/besu:26.4.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
PUB_KEY=$(cat $PROJETO/.env.configs/nodes/$NOVO_NO/key.pub | sed 's/^0x//')

docker run --rm -v ${PWD}/$PROJETO/.env.configs/nodes/$NOVO_NO:/key-dir hyperledger/besu:26.4.0 public-key export-address --node-private-key-file=/key-dir/key --to=/key-dir/address > /dev/null 2>&1
ENDERECO_NO=$(cat $PROJETO/.env.configs/nodes/$NOVO_NO/address)

docker run --rm -v ${PWD}/$PROJETO/.env.configs/nodes/boot1:/key-dir hyperledger/besu:26.4.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
BOOT_PUB=$(cat $PROJETO/.env.configs/nodes/boot1/key.pub | sed 's/^0x//')
BOOT_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJETO,,}_boot1_1)

echo "======================================================================"
echo "🚨 PASSO OBRIGATÓRIO: AUTORIZAR O NÓ NA BLOCKCHAIN"
echo "Abra OUTRO terminal, vá na pasta 'Permissionamento/gen02' e rode:"
echo ""
echo "export ENODE_PUB_KEY=\"$PUB_KEY\""
echo "export NODE_NAME=\"$NOVO_NO\""
echo "export NODE_TYPE=\"1\""
echo "npx hardhat run aceitar_no.js --network local_besu"
echo ""
echo "======================================================================"
echo "⭐ GUARDE O ENDEREÇO DESTE NÓ SE QUISER PROMOVÊ-LO A VALIDADOR DEPOIS:"
echo "Endereço: $ENDERECO_NO"
echo "======================================================================"
read -p "Pressione ENTER somente DEPOIS que o Hardhat der SUCESSO..."

echo "3. Subindo o nó $NOVO_NO (Módulo de Sincronização Livre)..."
docker run -d --name ${PROJETO,,}_${NOVO_NO}_1 \
  --network $REDE_DOCKER \
  -p $PORTA_RPC:8545 \
  -v ${PWD}/$PROJETO/volumes/$NOVO_NO:/var/lib/besu \
  -v ${PWD}/$PROJETO/.env.configs/genesis.json:/var/lib/besu/genesis.json \
  -v ${PWD}/$PROJETO/.env.configs/log.xml:/var/lib/besu/log.xml \
  -v ${PWD}/$PROJETO/.env.configs/nodes/$NOVO_NO/key:/var/lib/besu/key \
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

echo "✅ Nó $NOVO_NO criado na porta $PORTA_RPC e sincronizando o histórico!"
echo "Aguarde ele terminar de sincronizar e rode: ./secure_node.sh $PROJETO $NOVO_NO $PORTA_RPC"