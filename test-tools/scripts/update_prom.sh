#!/bin/bash
# Usage: ./update_prom.sh <project_name> <node_name>

PROJECT=$1
NEW_NODE=$2

if [ -z "$PROJECT" ] || [ -z "$NEW_NODE" ]; then
    echo "Usage: ./update_prom.sh <project_name> <node_name>"
    exit 1
fi

NODE_IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${PROJECT,,}_${NEW_NODE}_1)

if [ -z "$NODE_IP" ]; then
    echo "ERROR: Node container ${PROJECT,,}_${NEW_NODE}_1 is not running."
    exit 1
fi

PROM_CONFIG="$PROJECT/.env.configs/prometheus.yml"
TARGET_STRING="'$NODE_IP:9545'"

if grep -q "$TARGET_STRING" "$PROM_CONFIG"; then
    echo "Node $NEW_NODE ($NODE_IP) is already monitored in Prometheus."
    exit 0
fi

echo "1. Adding node $NEW_NODE ($NODE_IP:9545) to $PROM_CONFIG..."
awk -v ip="$NODE_IP" '
/targets:/ && !done {
    print $0
    print "          - \x27" ip ":9545\x27"
    done = 1
    next
}
{ print }
' "$PROM_CONFIG" > "$PROM_CONFIG.tmp" && mv "$PROM_CONFIG.tmp" "$PROM_CONFIG"

echo "2. Restarting Prometheus monitoring container..."
docker restart ${PROJECT,,}_prometheus_1 > /dev/null 2>&1

echo "Node $NEW_NODE ($NODE_IP:9545) added to Prometheus monitoring."