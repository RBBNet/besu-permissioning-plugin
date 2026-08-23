#!/bin/bash
# Executar na raiz (ex: ~/rbb/scripts)
# Uso: ./vote.sh <porta_validador_votante> <endereco_no_alvo> <true/false>

PORTA_VOTANTE=$1
ALVO=$2
VOTO=$3 # true para promover a validador, false para remover

if [ -z "$PORTA_VOTANTE" ] || [ -z "$ALVO" ] || [ -z "$VOTO" ]; then
    echo "Uso: ./vote.sh <porta_validador_votante> <endereco_no_alvo> <true/false>"
    echo "Exemplo p/ Adicionar: ./vote.sh 10002 0x123...abc true"
    echo "Exemplo p/ Remover:   ./vote.sh 10003 0x123...abc false"
    exit 1
fi

echo "🗳️ Enviando voto pelo Validador na porta $PORTA_VOTANTE..."
curl -s -X POST --data "{\"jsonrpc\":\"2.0\",\"method\":\"qbft_proposeValidatorVote\",\"params\":[\"$ALVO\", $VOTO], \"id\":1}" http://localhost:$PORTA_VOTANTE
echo -e "\n"

echo "🔎 Lista de Validadores Atuais na rede:"
curl -s -X POST --data '{"jsonrpc":"2.0","method":"qbft_getValidatorsByBlockNumber","params":["latest"], "id":1}' http://localhost:$PORTA_VOTANTE
echo ""