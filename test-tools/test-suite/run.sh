#!/bin/bash
# Script de conveniência para iniciar o orquestrador do test-suite
cd "$(dirname "$0")"

# Verifica se o Python 3 está instalado
if ! command -v python3 &> /dev/null; then
    echo "❌ ERRO: Python 3 não está instalado no sistema."
    exit 1
fi

# Roda o script de orquestração repassando os parâmetros
python3 orchestrator.py "$@"
