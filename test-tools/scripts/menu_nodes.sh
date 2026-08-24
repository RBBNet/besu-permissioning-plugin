#!/bin/bash
# Usage: ./menu_nodes.sh [project_name]

PROJECT=$1

if [ -z "$PROJECT" ]; then
    read -p "Enter project directory name: " PROJECT
fi

if [ ! -d "$PROJECT" ]; then
    echo "Project directory $PROJECT not found."
    exit 1
fi

PROJECT_LOWER="${PROJECT,,}"

executar_acao() {
    local ACAO=$1
    local STATUS_FILTRO=$2
    local MSG_VAZIO=$3

    mapfile -t CONTAINERS < <(docker ps -a --filter "name=^${PROJECT_LOWER}_" --filter "status=$STATUS_FILTRO" --format "{{.Names}}")

    if [ ${#CONTAINERS[@]} -eq 0 ]; then
        echo ""
        echo "$MSG_VAZIO"
        read -p "Press ENTER to return to menu..."
        return
    fi

    echo ""
    echo "Select container to apply command ($ACAO):"
    echo "------------------------------------------------"
    for i in "${!CONTAINERS[@]}"; do
        echo "[$((i+1))] ${CONTAINERS[$i]}"
    done
    echo "[0] Cancel"
    echo "------------------------------------------------"

    read -p "Select option [0-${#CONTAINERS[@]}]: " ESCOLHA

    if [[ "$ESCOLHA" =~ ^[0-9]+$ ]] && [ "$ESCOLHA" -ge 1 ] && [ "$ESCOLHA" -le "${#CONTAINERS[@]}" ]; then
        CONTAINER_ALVO="${CONTAINERS[$((ESCOLHA-1))]}"
        echo "Executing docker $ACAO on $CONTAINER_ALVO..."
        docker $ACAO "$CONTAINER_ALVO"
        echo "Done!"
        read -p "Press ENTER to continue..."
    fi
}

while true; do
    clear
    echo "===================================================="
    echo "BESU CONTAINER MANAGEMENT MENU: [$PROJECT]"
    echo "===================================================="
    echo "[1] STOP a running node"
    echo "[2] START a stopped node"
    echo "[3] RESTART a running node"
    echo "[4] View status of all nodes"
    echo "[0] Exit"
    echo "===================================================="
    read -p "Select option [0-4]: " OPTION

    case $OPTION in
        1)
            executar_acao "stop" "running" " No running nodes available to stop."
            ;;
        2)
            executar_acao "start" "exited" " No stopped nodes available to start."
            ;;
        3)
            executar_acao "restart" "running" " No running nodes available to restart."
            ;;
        4)
            echo ""
            echo "--- Active Nodes Status ---"
            docker ps -a --filter "name=^${PROJECT_LOWER}_" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | sort -f
            echo ""
            read -p "Press ENTER to return to menu..."
            ;;
        0)
            echo "Exiting..."
            exit 0
            ;;
        *)
            echo "Invalid option."
            sleep 1
            ;;
    esac
done