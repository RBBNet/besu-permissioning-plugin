# 🧪 Plugin Sandbox — Suíte de Testes Configuráveis (`test-suite`)

Suíte de testes de integração para certificar o **Plugin de Permissionamento On-Chain** no Hyperledger Besu.

**Modo atual**: `TransactionSimulationService` — API interna do Besu. O plugin consulta contratos via `simulate()` sem dependência de HTTP externo.

---

## 🗂️ Estrutura

```
test-suite/
├── configs/                       # Cenários JSON customizáveis
│   ├── cenario-valioso.json       # 5 nós, 2 versões Besu, plugin ativo — PF + CV
│   ├── cenario-heterogeneo.json   # Plugin + transparente + 2 versões — migração real
│   ├── cenario-e2e.json           # 1 nó sem plugin (deploy) + 1 com plugin (teste)
│   ├── cenario-failclose-sem-ingress.json     # FC-01: ingress vazio → fail-close
│   ├── cenario-failclose-ingress-invalido.json # FC-02: ingress inexistente → fail-close
│   └── cenario-rede-mista.json    # NR-01: nó rogue sem plugin
├── templates/                     # Modelos para clonagem do estado inicial
│   ├── genesis.json               # QBFT + 6 contratos pré-deployados com storage
│   └── log.xml                    # Configuração de logs do Besu
├── scripts/                       # Scripts auxiliares
│   ├── generate-genesis.sh        # Gera genesis com bytecode + storage do forge
│   ├── deploy-contracts.sh        # Deploy GEN01/GEN02 pós-genesis (nó sem plugin)
│   ├── setup-contracts.sh         # Registro idempotente Admin+Rules nos Ingresses
│   └── verify-contracts.sh        # Verificação standalone de contratos on-chain
├── postman/                       # Coleções HTTP-RPC para Postman/Newman
│   ├── Permissioning.postman_collection.json
│   └── README.md
├── monitoring/                    # Prometheus + Grafana
│   ├── monitoring.sh              # Script para iniciar/parar stack
│   ├── prometheus.yml             # Configuração do Prometheus
│   └── grafana/                   # Provisioning do Grafana
├── orchestrator.py                # CLI Python com health-check + verificação + logs
├── run.sh                         # Atalho bash para o orchestrator
├── README.md                      # Esta documentação
├── GUIA_CRIACAO_TESTES.md         # Guia de criação de novos cenários
├── ANALISE_TECNICA_PLUGIN_SIMULATOR.md  # Análise técnica do plugin
└── RELATORIO_PRE_DEPLOY_CONTRATOS.md    # Por que contratos precisam ser pré-deployados
```

---

## 🚀 Como Executar

### Pré-requisitos
* **Python 3**, **Docker** e **Docker Compose**
* **Plugin JAR** em `plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar`
* **Foundry/Cast** (`cast --version`)

### Inicializar Rede Padrão
```bash
./test-suite/run.sh
# ou:
python3 test-suite/orchestrator.py --config configs/cenario-valioso.json
```

### Flags do Orchestrator
```bash
python3 test-suite/orchestrator.py \
  --config configs/cenario-valioso.json \
  --action start|stop|status \
  --timeout 300 \        # timeout para aguardar blocos
  --skip-verify \        # pular verificação de contratos
  --skip-setup \         # pular script de setup pós-genesis
  --monitoring           # iniciar Prometheus + Grafana
```

### Monitoramento

Para visualizar métricas do plugin em tempo real:

```bash
# Iniciar rede COM monitoramento
python3 orchestrator.py -c configs/cenario-valioso.json -a start --monitoring

# Acessar Grafana
# URL: http://localhost:3000
# Usuário: admin / Senha: admin
```

Métricas disponíveis no Grafana:
- `besupermissioning_onchain_transaction_check_count_permitted_total` - Transações permitidas
- `besupermissioning_onchain_transaction_check_count_denied_total` - Transações negadas
- `besupermissioning_onchain_transaction_check_count_total` - Total verificadas

Veja `monitoring/README.md` para mais detalhes.

---

## 📦 Contratos Pré-Deployados no Genesis

O genesis (`templates/genesis.json`) é gerado via `scripts/generate-genesis.sh` a partir dos artifacts do forge, com storage layout correto. Contém todos os contratos GEN01 pré-configurados:

| Contrato | Endereço |
|----------|----------|
| Account Ingress | `0x0000000000000000000000000000000000008888` |
| Node Ingress | `0x0000000000000000000000000000000000009999` |
| Admin | `0x181a92c9b76ab7271a03b640cc172e75a0dc3484` |
| AccountRules | `0x0e9e81bb09cdd55b607373e89e3154354a925b7d` |
| NodeRules | `0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616` |

Admin (`0xf39Fd6e5...`) pré-autorizado no allowlist. Zero-gas configurado (`BESU_MIN_GAS_PRICE=0` + `BESU_TX_POOL_ENABLE_BALANCE_CHECK=false`).

---

## 🔧 Plugin — Modo `TransactionSimulationService`

O plugin consulta contratos via API interna `TransactionSimulationService.simulate()` do Besu. Sem dependência de HTTP externo.

| Variável | Obrigatória | Descrição |
|----------|:-----------:|-----------|
| `BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS` | **Sim** | Endereço do Account Ingress (ex: `0x0000...8888`) |
| `BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS` | Não | Endereço do Node Ingress (fallback para Account Ingress) |

---

## 📝 Cobertura de Cenários

| Cenário | O que testa |
|---------|------------|
| **PF-01** | Admin (no allowlist) → transação APROVADA |
| **PF-02** | Unauth (fora do allowlist) → transação BLOQUEADA (-32007) |
| **FC-01** | Sem Ingress → FAIL-CLOSE (todas tx rejeitadas) |
| **FC-02** | Ingress inválido → FAIL-CLOSE |
| **CV** | Rede híbrida multi-versão Besu (25.12 + 26.5) |
| **NR-01** | Nó rogue sem plugin → bypass comprovado |
| **Heterogêneo** | Migração real: plugin + transparente coexistindo |
| **E2E** | Deploy GEN02 + 50 transações + validação plugin |
