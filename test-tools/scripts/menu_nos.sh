#!/bin/bash

# =======================================================
# 1. DEFINIÇÃO DO PROJETO
# =======================================================
# O "${1%/}" corta a barra "/" do final se você usar o Autocomplete (TAB)
PROJETO="${1%/}"

if [ -z "$PROJETO" ]; then
    echo "Uso: ./menu_nos.sh <nome_projeto>"
    echo "Exemplo: ./menu_nos.sh redeToy_bird"
    exit 1
fi

# Converte para minúsculo para bater com o padrão do Docker
REDE="${PROJETO,,}"

# =======================================================
# 2. FUNÇÃO INTELIGENTE DE SELEÇÃO DE NÓS
# =======================================================
selecionar_no() {
    ACAO=$1
    clear
    echo ""

    # Define o filtro do Docker e a mensagem de erro com base na ação
    if [ "$ACAO" == "stop" ] || [ "$ACAO" == "restart" ]; then
        FILTRO_STATUS="-f status=running"
        MSG_VAZIO="❌ Nenhum nó está LIGADO no momento para poder ser selecionado."
    elif [ "$ACAO" == "start" ]; then
        # Pega containers parados (exited) ou recém-criados
        FILTRO_STATUS="-f status=exited -f status=created"
        MSG_VAZIO="❌ Nenhum nó está PAUSADO no momento para poder ser ligado."
    else
        FILTRO_STATUS=""
        MSG_VAZIO="❌ Nenhum nó encontrado."
    fi

    # Busca no Docker aplicando o filtro de status, extrai o nome e ORDENA alfabeticamente (sort)
    mapfile -t NOS < <(docker ps -a $FILTRO_STATUS --format "{{.Names}}" | grep -i "^${REDE}_" | sed -E "s/^${REDE}_(.*)_[0-9]+$/\1/i" | sort)
    
    if [ ${#NOS[@]} -eq 0 ]; then
        echo "$MSG_VAZIO"
        read -p "Pressione ENTER para voltar..."
        return 1
    fi

    echo "🤖 Selecione o nó que você deseja aplicar o comando ($ACAO):"
    
    # Cria o menu numerado dinâmico
    select NO_ESCOLHIDO in "${NOS[@]}" "Voltar ao Menu Principal"; do
        if [ "$NO_ESCOLHIDO" == "Voltar ao Menu Principal" ]; then
            return 1
        elif [ -n "$NO_ESCOLHIDO" ]; then
            CONTAINER="${REDE}_${NO_ESCOLHIDO}_1"
            echo ""
            echo "⏳ Executando 'docker $ACAO' em $CONTAINER..."
            docker $ACAO $CONTAINER
            echo "✅ Comando finalizado com sucesso."
            sleep 1
            return 0
        else
            echo "⚠️ Opção inválida! Digite o número correspondente."
        fi
    done
}

# =======================================================
# 3. MENU PRINCIPAL
# =======================================================
while true; do
    clear
    echo "================================================="
    echo " 🎛️  SALA DE CONTROLE DE INFRA - REDE: $PROJETO"
    echo "================================================="
    echo "1. 🔴 Derrubar Nó (Stop - Simula Queda/Manutenção)"
    echo "2. 🟢 Ligar Nó (Start - Volta à Rede)"
    echo "3. ♻️  Reiniciar Nó (Restart)"
    echo "4. 📊 Ver Status Atual de Todos os Nós"
    echo "5. ❌ Sair"
    echo "================================================="
    read -p "Escolha a ação (1-5): " OPCAO

    case $OPCAO in
        1) 
            selecionar_no "stop" 
            ;;
        2) 
            selecionar_no "start" 
            ;;
        3) 
            selecionar_no "restart" 
            ;;
        4)
            clear
            echo "--- STATUS DOS NÓS NO DOCKER ---"
            # Lista os nós, ignora diferenças de caixa e organiza alfabeticamente para ficar bonito de ler
            docker ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -i "^${REDE}_" | sort
            echo "--------------------------------"
            read -p "Pressione ENTER para voltar ao menu..."
            ;;
        5)
            echo "👋 Saindo da Sala de Controle..."
            break
            ;;
        *)
            echo "⚠️ Opção inválida!"
            sleep 1
            ;;
    esac
done