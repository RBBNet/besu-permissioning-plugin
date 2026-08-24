# Monitoring Stack

Prometheus + Grafana for visualizing permissioning plugin metrics.

## Usage

### Via Orchestrator (recommended)

```bash
# Start network WITH monitoring
python3 orchestrator.py -c configs/scenario-full-network.json -a start --monitoring

# Stop network AND monitoring
python3 orchestrator.py -a stop

# Check status (includes monitoring)
python3 orchestrator.py -a status
```

### Manually

```bash
# Start
./monitoring.sh start

# Stop
./monitoring.sh stop

# Status
./monitoring.sh status
```

## URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | - |

## Plugin Metrics

In Grafana → **Explore** → **Prometheus**, use:

```
# Permitted transactions
besu_permissioning_onchain_transaction_check_count_permitted_total

# Denied transactions
besu_permissioning_onchain_transaction_check_count_denied_total

# Total verified transactions
besu_permissioning_onchain_transaction_check_count_total

# Permitted node connections
besu_permissioning_onchain_node_check_count_permitted_total

# Denied node connections
besu_permissioning_onchain_node_check_count_denied_total

# Total node connections verified
besu_permissioning_onchain_node_check_count_total
```

## Configuration

- `prometheus.yml` - Prometheus configuration (scrape targets)
- `grafana/provisioning/` - Automatic Prometheus datasource provisioning
