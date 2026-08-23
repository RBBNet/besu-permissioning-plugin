#!/bin/bash
# A BAZUCA AUTOMATIZADA DE NÓS (SELF-HEALING)
# Executar na raiz
# Uso: ./auto_node.sh <nome_projeto> <nome_do_no> <porta_rpc>

# ========================================================
# 🔧 CONFIGURAÇÃO DA REDE (ATUALIZE AO CRIAR NOVA REDE)
# ========================================================
ENDERECO_NODE_RULES="0x38cF23C52Bb4B13F051Aec09580a2dE845a7FA35"
# ========================================================

PROJETO=$1
NOVO_NO=$2
PORTA_RPC=$3

if [ -z "$PROJETO" ] || [ -z "$NOVO_NO" ] || [ -z "$PORTA_RPC" ]; then
    echo "Uso: ./auto_node.sh <nome_projeto> <nome_do_no> <porta_rpc>"
    echo "Exemplo: ./auto_node.sh redeToy_bird validator14 10017"
    exit 1
fi

REDE_DOCKER="${PROJETO,,}_default"

if [ ! -f "$PROJETO/.env.configs/genesis.json" ]; then
    echo "❌ ERRO FATAL: Projeto não encontrado ou nome incorreto."
    exit 1
fi

echo "====================================================="
echo "🚀 INICIANDO CRIAÇÃO AUTOMATIZADA DO NÓ: $NOVO_NO"
echo "====================================================="

echo "1. Criando diretórios e gerando chaves locais..."
mkdir -p $PROJETO/volumes/$NOVO_NO
mkdir -p $PROJETO/.env.configs/nodes/$NOVO_NO

if [ ! -f "$PROJETO/.env.configs/nodes/$NOVO_NO/key" ]; then
    openssl rand -hex 32 > $PROJETO/.env.configs/nodes/$NOVO_NO/key
fi

docker run --rm -v ${PWD}/$PROJETO/.env.configs/nodes/$NOVO_NO:/key-dir hyperledger/besu:26.4.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
PUB_KEY=$(cat $PROJETO/.env.configs/nodes/$NOVO_NO/key.pub | sed 's/^0x//')

docker run --rm -v ${PWD}/$PROJETO/.env.configs/nodes/boot1:/key-dir hyperledger/besu:26.4.0 public-key export --node-private-key-file=/key-dir/key --to=/key-dir/key.pub > /dev/null 2>&1
BOOT_PUB=$(cat $PROJETO/.env.configs/nodes/boot1/key.pub | sed 's/^0x//')
BOOT_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJETO,,}_boot1_1)

echo "2. Preparando ambiente Hardhat..."
DIRETORIO_ATUAL=$(pwd)
PASTA_HARDHAT="$PROJETO/Permissionamento/gen02"
ARQUIVO_JS="scripts/aceitar_no.js"

cd "$PASTA_HARDHAT" || exit

# --- SISTEMA DE AUTO-REPARO (SELF-HEALING) ---
# Se o script JS não existir, o Bash cria ele na hora!
if [ ! -f "$ARQUIVO_JS" ]; then
    echo "   [!] Script aceitar_no.js não encontrado. Gerando automaticamente..."
    mkdir -p scripts
    cat << 'EOF' > $ARQUIVO_JS
const { ethers } = require("hardhat");

async function main() {
    const NODE_RULES_ADDRESS = process.env.NODE_RULES_ADDRESS;
    if (!NODE_RULES_ADDRESS) throw new Error("A variável NODE_RULES_ADDRESS não foi definida.");

    const [adminSigner] = await ethers.getSigners();
    const pubKey = process.env.ENODE_PUB_KEY;
    const nodeName = process.env.NODE_NAME || "Novo No";
    const nodeType = parseInt(process.env.NODE_TYPE || "1");

    if (!pubKey || pubKey.length !== 128) throw new Error("Enode inválido.");

    const enodeHigh = "0x" + pubKey.slice(0, 64);
    const enodeLow = "0x" + pubKey.slice(64, 128);

    const NodeRules = await ethers.getContractFactory("NodeRulesV2Impl");
    const nodeRulesContract = NodeRules.attach(NODE_RULES_ADDRESS).connect(adminSigner);

    const tx = await nodeRulesContract.addLocalNode(enodeHigh, enodeLow, nodeType, nodeName, { gasPrice: 0 });
    const receipt = await tx.wait();
    
    if (receipt.status === 1) console.log("   ✅ SUCESSO: Nó autorizado on-chain.");
    else throw new Error("A transação falhou na EVM.");
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
EOF
fi
# ---------------------------------------------

echo "3. Acionando o Hardhat para autorização On-Chain..."
export NODE_RULES_ADDRESS="$ENDERECO_NODE_RULES"
export ENODE_PUB_KEY="$PUB_KEY"
export NODE_NAME="$NOVO_NO"
export NODE_TYPE="1"

npx hardhat run "$ARQUIVO_JS" --network local_besu
HARDHAT_STATUS=$?

cd "$DIRETORIO_ATUAL" || exit

if [ $HARDHAT_STATUS -ne 0 ]; then
    echo "❌ ERRO: O Hardhat falhou ao autorizar a transação. O nó não será iniciado."
    exit 1
fi

echo "4. Subindo o nó $NOVO_NO (Módulo de Sincronização)..."
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
  --metrics-enabled=true --metrics-host=0.0.0.0 > /dev/null 2>&1

echo "⏳ Aguardando 15 segundos para o Besu baixar o histórico de blocos..."
sleep 15

echo "5. Reboot e Blindagem Máxima (Ativando Plugin RBB)..."
docker rm -f ${PROJETO,,}_${NOVO_NO}_1 > /dev/null 2>&1

docker run -d --name ${PROJETO,,}_${NOVO_NO}_1 \
  --network $REDE_DOCKER \
  -p $PORTA_RPC:8545 \
  -v ${PWD}/$PROJETO/volumes/$NOVO_NO:/var/lib/besu \
  -v ${PWD}/$PROJETO/.env.configs/genesis.json:/var/lib/besu/genesis.json \
  -v ${PWD}/$PROJETO/.env.configs/log.xml:/var/lib/besu/log.xml \
  -v ${PWD}/$PROJETO/.env.configs/nodes/$NOVO_NO/key:/var/lib/besu/key \
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
  --metrics-enabled=true --metrics-host=0.0.0.0 > /dev/null 2>&1

echo "====================================================="
echo "✅ SUCESSO ABSOLUTO!"
echo "O nó $NOVO_NO está no ar na porta $PORTA_RPC."
echo "Autorizado, sincronizado e protegido pelo Plugin."
echo "====================================================="