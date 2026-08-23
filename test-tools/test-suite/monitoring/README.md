# Monitoring Stack

Prometheus + Grafana para visualização de métricas do plugin de permissionamento.

## Uso

### Via Orchestrator (recomendado)

```bash
# Iniciar rede COM monitoramento
python3 orchestrator.py -c configs/cenario-valioso.json -a start --monitoring

# Parar rede E monitoramento
python3 orchestrator.py -a stop

# Ver status (inclui monitoramento)
python3 orchestrator.py -a status
```

### Manualmente

```bash
# Iniciar
./monitoring.sh start

# Parar
./monitoring.sh stop

# Status
./monitoring.sh status
```

## URLs

| Serviço | URL | Credenciais |
|---------|-----|-------------|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | - |

## Métricas do Plugin

No Grafana → **Explore** → **Prometheus**, use:

```
# Transações permitidas
besupermissioning_onchain_transaction_check_count_permitted_total

# Transações negadas
besupermissioning_onchain_transaction_check_count_denied_total

# Total verificadas
besupermissioning_onchain_transaction_check_count_total

# Cache hits
besupermissioning_onchain_transaction_cache_hit_count_total

# Conexões de nó verificadas
besupermissioning_onchain_node_check_count_total
```

## Configuração

- `prometheus.yml` - Configuração do Prometheus (scrape targets)
- `grafana/provisioning/` - Datasource automática do Prometheus
