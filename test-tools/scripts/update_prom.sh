#!/bin/bash
# Uso: ./update_prom.sh <nome_projeto> <nome_do_novo_no>

PROJETO=$1
NOVO_NO=$2

if [ -z "$PROJETO" ] || [ -z "$NOVO_NO" ]; then
    echo "Uso: ./update_prom.sh <nome_projeto> <nome_do_novo_no>"
    echo "Exemplo: ./update_prom.sh redeToy_bird validator15"
    exit 1
fi

ARQUIVO_PROM="$PROJETO/examples/prometheus/prometheus.yml"

if [ ! -f "$ARQUIVO_PROM" ]; then
    echo "❌ ERRO: Arquivo $ARQUIVO_PROM não encontrado."
    exit 1
fi

# Checa se o nó já está no arquivo para evitar duplicação
if grep -q "'$NOVO_NO:9545'" "$ARQUIVO_PROM"; then
    echo "⚠️ O nó $NOVO_NO já está monitorado no Prometheus."
    exit 0
fi

echo "1. Adicionando $NOVO_NO ao prometheus.yml (Apenas no bloco besu-nodes)..."

# Usando awk para garantir que adicionamos APENAS debaixo do PRIMEIRO 'targets:'
awk -v node="          - '$NOVO_NO:9545'" '
/targets:/ && !feito {
    print $0
    print node
    feito=1
    next
}
1' "$ARQUIVO_PROM" > "$ARQUIVO_PROM.tmp" && mv "$ARQUIVO_PROM.tmp" "$ARQUIVO_PROM"

echo "2. Reiniciando o contêiner do Prometheus para aplicar as mudanças..."
# Descobre o nome do contêiner do Prometheus daquele projeto específico e reinicia
CONTAINER_PROM=$(docker ps --format "{{.Names}}" | grep "${PROJETO,,}_prometheus")

if [ -n "$CONTAINER_PROM" ]; then
    docker restart "$CONTAINER_PROM"
    echo "✅ Sucesso! O nó $NOVO_NO foi adicionado com segurança ao monitoramento."
else
    echo "❌ ERRO: Contêiner do Prometheus não encontrado rodando."
fi