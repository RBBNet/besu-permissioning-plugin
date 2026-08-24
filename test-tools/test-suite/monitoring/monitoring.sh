#!/bin/bash
# Monitor Stack for Plugin Permissioning Test Suite
# Usage:
# ./monitoring.sh start   - Start Prometheus + Grafana
# ./monitoring.sh stop    - Stop and remove containers
# ./monitoring.sh status  - Show container status

ACTION=${1:-status}

case $ACTION in
    start)
        echo "Starting monitoring stack..."
        
        # Stop existing containers if any
        docker rm -f plugin-prometheus plugin-grafana 2>/dev/null
        
        # Get host IP for Docker
        HOST_IP=$(ip route | grep default | awk '{print $3}')
        
        # Start Prometheus
        docker run -d --name plugin-prometheus \
            -p 9090:9090 \
            -v "$(dirname "$0")/prometheus.yml:/etc/prometheus/prometheus.yml" \
            --add-host=host.docker.internal:$HOST_IP \
            prom/prometheus:v2.51.0
        
        # Start Grafana
        docker run -d --name plugin-grafana \
            -p 3000:3000 \
            -e GF_SECURITY_ADMIN_USER=admin \
            -e GF_SECURITY_ADMIN_PASSWORD=admin \
            -e GF_AUTH_ANONYMOUS_ENABLED=true \
            -e GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer \
            -v "$(dirname "$0")/grafana/provisioning:/etc/grafana/provisioning" \
            --add-host=host.docker.internal:$HOST_IP \
            grafana/grafana-oss:10.4.0
        
        echo ""
        echo "Monitoring stack started!"
        echo "Grafana:    http://localhost:3000 (admin/admin)"
        echo "Prometheus: http://localhost:9090"
        ;;
        
    stop)
        echo "Stopping monitoring stack..."
        docker rm -f plugin-prometheus plugin-grafana 2>/dev/null
        echo "Monitoring stack stopped."
        ;;
        
    status)
        echo "Monitoring stack status:"
        docker ps --filter "name=plugin-prometheus" --filter "name=plugin-grafana" \
            --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
        ;;
        
    *)
        echo "Usage: $0 {start|stop|status}"
        exit 1
        ;;
esac
