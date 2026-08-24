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
besupermissioning_onchain_transaction_check_count_permitted_total

# Denied transactions
besupermissioning_onchain_transaction_check_count_denied_total

# Total verified
besupermissioning_onchain_transaction_check_count_total

# Cache hits
besupermissioning_onchain_transaction_cache_hit_count_total

# Node connections verified
besupermissioning_onchain_node_check_count_total
```

## Configuration

- `prometheus.yml` - Prometheus configuration (scrape targets)
- `grafana/provisioning/` - Automatic Prometheus datasource provisioning
