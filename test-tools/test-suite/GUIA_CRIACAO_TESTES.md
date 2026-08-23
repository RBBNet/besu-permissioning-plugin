# Guia Passo a Passo: Criando Novos Cenários de Teste no Hyperledger Besu Test Suite

**Versão:** 1.0 | **Data:** 2026-06-22 | **Escopo:** `test-suite/`

---

## Índice

1. [Visão Geral da Arquitetura](#1-visão-geral)
2. [Validação do Ambiente Atual](#2-validação-do-ambiente)
3. [Como Criar um Novo Cenário (Arquivo JSON de Config)](#3-criar-novo-cenário)
4. [Como Expandir a Coleção Postman](#4-expandir-coleção-postman)
5. [Teste de Estresse com Newman](#5-teste-de-estresse)
6. [Comprovando Fail-Close via Logs](#6-comprovando-fail-close)
7. [“Brincando” com Requisições Livres no Postman](#7-brincando-com-postman)
8. [Checklist de Validação](#8-checklist)
9. [Referência Rápida: Campos do Config JSON](#9-referência-config)

---

## 1. Visão Geral da Arquitetura

```
test-suite/
├── configs/          ← Cenários JSON (VOCÊ CRIA AQUI)
├── postman/          ← Coleção HTTP-RPC (VOCÊ EXPANDE AQUI)
├── templates/        ← genesis.json + log.xml (base imutável)
├── orchestrator.py   ← Motor que lê config → gera docker-compose → sobe rede
├── run.sh            ← Atalho bash
└── run-data/         ← Dados gerados em runtime (docker-compose.yml, volumes)
```

Fluxo:
1. Você escreve um `.json` em `configs/` descrevendo a topologia de rede.
2. Executa `./run.sh --config configs/seu-cenario.json`
3. O `orchestrator.py` gera `run-data/docker-compose.yml` e sobe os containers.
4. Você interage com a rede via Postman/Newman/curl nos endpoints RPC expostos.
5. Logs são capturados dos containers Docker (`docker logs <container>`).

---

## 2. Validação do Ambiente Atual

### 2.1 Pré-requisitos (todos OK no ambiente validado)

| Componente | Status | Comando de Verificação |
|---|---|---|
| Docker + Docker Compose | ✓ OK | `docker compose version` |
| Python 3 | ✓ OK | `python3 --version` |
| Plugin JAR | ✓ OK | `ls plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar` |
| Rede ativa | ✓ OK (failclose-sem-ingress) | `docker ps --filter "name=plugin"` |
| RPC responde | ✓ OK | `curl -s -X POST http://localhost:9005 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'` |

### 2.2 Newman (para automação CLI)

```bash
# Instalar (não instalado atualmente)
npm install -g newman

# Verificar
newman --version
```

### 2.3 Orquestrador — Ajuda

```bash
python3 test-suite/orchestrator.py --help
# Opções:
#   --config, -c   : caminho do JSON de configuração
#   --action, -a   : start (default) | stop | status
```

---

## 3. Criar um Novo Cenário (Arquivo JSON de Config)

### 3.1 Estrutura do Arquivo de Configuração

```json
{
  "nome_rede": "plugin-meu-cenario",
  "plugin_jar_path": "../plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar",
  "nos": [
    {
      "nome": "validador-1",
      "tipo": "bootnode",
      "porta_rpc": 9001,
      "porta_metrics": 9541,
      "versao_besu": "26.5.0",
      "usar_plugin": true,
      "ingress_address": "0x0000000000000000000000000000000000008888",
      "node_ingress_address": "0x0000000000000000000000000000000000009999"
    }
  ]
}
```

### 3.2 Campos Obrigatórios por Nó

| Campo | Tipo | Descrição |
|---|---|---|
| `nome` | string | Nome único do nó (ex: `"validador-1"`) |
| `tipo` | string | `"bootnode"` (1º nó, líder inicial) / `"validator"` / `"rpc"` / `"rogue"` (sem plugin) |
| `porta_rpc` | int | Porta host mapeada para RPC (8545 interno). Use 9001-9010 |
| `porta_metrics` | int | Porta host mapeada para métricas Prometheus (9545 interno) |
| `versao_besu` | string | Tag da imagem Docker: `"24.12.0"`, `"25.12.0"`, `"26.5.0"` |
| `usar_plugin` | bool | `true` = monta plugin JAR e injeta env vars de permissionamento |
| `ingress_address` | string | Endereço do contrato Account Ingress. `""` = omite env var (fail-close) |
| `node_ingress_address` | string | Endereço do contrato Node Ingress (normalmente `0x...9999`) |

### 3.3 Exemplos de Cenários que Você Pode Criar

#### a) Cenário de Carga com Múltiplos Nós RPC

```json
{
  "nome_rede": "plugin-carga-alta",
  "plugin_jar_path": "../plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar",
  "nos": [
    {"nome":"boot","tipo":"bootnode","porta_rpc":9001,"porta_metrics":9541,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"rpc-1","tipo":"rpc","porta_rpc":9005,"porta_metrics":9545,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"rpc-2","tipo":"rpc","porta_rpc":9006,"porta_metrics":9546,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"rpc-3","tipo":"rpc","porta_rpc":9007,"porta_metrics":9547,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"}
  ]
}
```

#### b) Cenário de Governança com Ingress Vazio (Rolling Upgrade)

```json
{
  "nome_rede": "plugin-gov-rolling",
  "plugin_jar_path": "../plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar",
  "nos": [
    {"nome":"v1","tipo":"bootnode","porta_rpc":9001,"porta_metrics":9541,"versao_besu":"24.12.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"v2","tipo":"validator","porta_rpc":9002,"porta_metrics":9542,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"rpc","tipo":"rpc","porta_rpc":9005,"porta_metrics":9545,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"}
  ]
}
```

#### c) Cenário de Ataque — Nó Malicioso sem Plugin (Byzantine)

```json
{
  "nome_rede": "plugin-byzantine",
  "plugin_jar_path": "../plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar",
  "nos": [
    {"nome":"honesto-1","tipo":"bootnode","porta_rpc":9001,"porta_metrics":9541,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"honesto-2","tipo":"validator","porta_rpc":9002,"porta_metrics":9542,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"atacante","tipo":"rogue","porta_rpc":9003,"porta_metrics":9543,"versao_besu":"26.5.0","usar_plugin":false}
  ]
}
```

#### d) Cenário de Fail-Close com Ingress Revogado Após Bloco N

```json
{
  "nome_rede": "plugin-failclose-pos-revoke",
  "plugin_jar_path": "../plugin-permissioned-rbb-integra/build/libs/onchain-permissioning-plugin.jar",
  "nos": [
    {"nome":"v1","tipo":"bootnode","porta_rpc":9001,"porta_metrics":9541,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"v2","tipo":"validator","porta_rpc":9002,"porta_metrics":9542,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"v3","tipo":"validator","porta_rpc":9003,"porta_metrics":9543,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"v4","tipo":"validator","porta_rpc":9004,"porta_metrics":9544,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"},
    {"nome":"rpc","tipo":"rpc","porta_rpc":9005,"porta_metrics":9545,"versao_besu":"26.5.0","usar_plugin":true,"ingress_address":"0x0000000000000000000000000000000000008888","node_ingress_address":"0x0000000000000000000000000000000000009999"}
  ]
}
```

### 3.4 Como Executar o Novo Cenário

```bash
# 1. Parar rede atual (se houver)
./test-suite/run.sh --action stop

# 2. Subir novo cenário
./test-suite/run.sh --config configs/seu-cenario.json

# 3. Verificar status
./test-suite/run.sh --action status

# 4. Testar conectividade RPC
curl -s -X POST http://localhost:9005 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

---

## 4. Expandir a Coleção Postman

### 4.1 Estrutura da Coleção Atual

A coleção em `postman/Plugin Permissioning_Permissioning.postman_collection.json` tem 4 pastas:

| Pasta | Requests | Propósito |
|---|---|---|
| `0. Conectividade e Bloco Atual` | 2 | `eth_blockNumber`, `net_peerCount` |
| `1. Account Permissioning (PF)` | 3 | PF-01, PF-02, consulta on-chain |
| `2. Node Permissioning` | 1 | Consulta de permissão de nó |
| `3. Chaos & Fail-Close (FC)` | 2 | FC-01, FC-02 |

### 4.2 Como Adicionar Novas Requests no Postman (UI)

1. Abra o Postman → Import → Selecione `Plugin Permissioning_Permissioning.postman_collection.json`
2. Clique com botão direito na coleção → **Add Folder** (ex: `4. Stress & Sobrecarga`)
3. Dentro da pasta → **Add Request**
4. Configure:
   - **Method**: `POST`
   - **URL**: `{{RPC_URL}}` (usa variável da coleção)
   - **Headers**: `Content-Type: application/json`
   - **Body** (raw JSON):

```json
{
  "jsonrpc": "2.0",
  "method": "eth_sendRawTransaction",
  "params": ["0x...RAW_TX_ASSINADA..."],
  "id": 1
}
```

5. Salve. Depois exporte a coleção atualizada:
   - Coleção → `...` → **Export** → substitua `Plugin Permissioning_Permissioning.postman_collection.json`

### 4.3 Novas Requests Sugeridas para a Coleção

#### Pasta `4. Governança Dinâmica (GD)`

```json
// GD-01: Atualizar Rules do Ingress (eth_sendRawTransaction com calldata para o contrato Ingress)
{
  "jsonrpc": "2.0",
  "method": "eth_sendRawTransaction",
  "params": ["0x...TX_ASSINADA_PELO_ADMIN_COM_CALLDATA_INGRESS..."],
  "id": 1
}

// GD-02: Verificar se mudança de governança propagou (eth_call no AccountRules)
{
  "jsonrpc": "2.0",
  "method": "eth_call",
  "params": [
    {
      "to": "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
      "data": "0x8da5cb5b000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266"
    },
    "latest"
  ],
  "id": 1
}
```

#### Pasta `5. Métricas e Observabilidade`

```json
// MET-01: Bloco atual (eth_blockNumber)
// MET-02: Contagem de peers (net_peerCount)
// MET-03: Validadores QBFT (qbft_getValidatorsByBlockNumber)
{
  "jsonrpc": "2.0",
  "method": "qbft_getValidatorsByBlockNumber",
  "params": ["latest"],
  "id": 1
}
// MET-04: Saldo de conta (eth_getBalance)
{
  "jsonrpc": "2.0",
  "method": "eth_getBalance",
  "params": ["0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", "latest"],
  "id": 1
}
// MET-05: Conteúdo da txpool (txpool_content)
{
  "jsonrpc": "2.0",
  "method": "txpool_content",
  "params": [],
  "id": 1
}
```

#### Pasta `6. Stress — Disparo em Loop`

Requisições que serão usadas com **Postman Runner** ou **Newman** com `-n` (iterations):

```json
// STRESS-01: Transação permitida em massa (ADMIN)
// STRESS-02: Transação bloqueada em massa (UNAUTH)
// STRESS-03: Consulta de saldo em alta frequência
```

---

## 5. Teste de Estresse com Postman e Newman

### 5.1 Via Postman Runner (GUI)

1. Clique na coleção → **Run**
2. Selecione as requests que quer estressar (ex: PF-01 + PF-02)
3. Configure:
   - **Iterations**: 100 (ou mais)
   - **Delay**: 100ms (ou 0ms para rajada)
   - **Data file**: opcional (CSV/JSON com variações de parâmetros)
4. Clique **Run**
5. Analise os resultados na aba **Run Results**

### 5.2 Via Newman (CLI — Recomendado para Carga Pesada)

```bash
# Instalar (se ainda não tiver)
npm install -g newman

# Executar cenário PF completo 100x
newman run test-suite/postman/Plugin Permissioning_Permissioning.postman_collection.json \
  --folder "1. Account Permissioning (PF)" \
  --global-var "RPC_URL=http://localhost:9005" \
  --iteration-count 100 \
  --delay-request 50 \
  --reporters cli,json \
  --reporter-json-export stress-result.json

# Executar coleção INTEIRA em loop 500x (estresse pesado)
newman run test-suite/postman/Plugin Permissioning_Permissioning.postman_collection.json \
  --global-var "RPC_URL=http://localhost:9005" \
  --iteration-count 500 \
  --delay-request 10 \
  --timeout-request 10000 \
  --reporters cli,json \
  --reporter-json-export stress-full.json

# Paralelo: disparar contra múltiplos nós simultaneamente
newman run test-suite/postman/Plugin Permissioning_Permissioning.postman_collection.json \
  --global-var "RPC_URL=http://localhost:9005" \
  --iteration-count 200 &
newman run test-suite/postman/Plugin Permissioning_Permissioning.postman_collection.json \
  --global-var "RPC_URL=http://localhost:9001" \
  --iteration-count 200 &
newman run test-suite/postman/Plugin Permissioning_Permissioning.postman_collection.json \
  --global-var "RPC_URL=http://localhost:9002" \
  --iteration-count 200 &
wait
```

### 5.3 Via Script Bash com curl (Estresse Máximo)

```bash
#!/bin/bash
# stress-test.sh — Dispara transações em loop contra o nó RPC

RPC_URL="http://localhost:9005"
COUNT=1000
DELAY=0.01  # segundos entre chamadas

# Raw TX assinada pelo ADMIN (pré-assinada offline)
RAW_TX="0xf861808082520894f39fd6e51aad88f6f4ce6ab8827279cfffb922668101801ca06b0fc5721867c0c16922b0c1673be7489816ea8c8c6bc1a1ad7a8412f4603b6ba056ff351fc8167f56b2c28892f3984d6b63d9ab0401b3b2cc2cc29091cfbfcf01"

for i in $(seq 1 $COUNT); do
  curl -s -X POST "$RPC_URL" \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"method\":\"eth_sendRawTransaction\",\"params\":[\"$RAW_TX\"],\"id\":$i}" &
  sleep $DELAY
done
wait
echo "Disparadas $COUNT transações"
```

---

## 6. Comprovando Fail-Close via Logs

### 6.1 O Que Procurar nos Logs

O fail-close é comprovado por 3 evidências nos logs (já validadas no `RELATORIO_VALIDACAO.txt`):

#### Evidência 1 — ERRO de Ingress Faltando

```
ERROR | PermissioningPlugin
  PermissioningPlugin: MISSING BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS environment variable.
```

#### Evidência 2 — Modo FAIL-CLOSE Ativado

```
ERROR | PermissioningPlugin
  On-Chain Permissioning Plugin CRITICAL CONFIGURATION ERROR:
  Ingress Address is missing or invalid. Node is in FAIL-CLOSE mode.
  ALL TRANSACTIONS AND/OR CONNECTIONS WILL BE REJECTED for security reasons.
```

#### Evidência 3 — Conexões Bloqueadas (Node Ingress)

```
ERROR | PermissioningPlugin
  PermissioningPlugin: eth_call to NodeIngress failed — caching null.
  PermissioningPlugin: Could not resolve NodeRules address. Rejecting connection.
```

### 6.2 Comandos para Extrair Evidências

```bash
# Puxar logs de todos os containers ativos
for container in $(docker ps --filter "name=plugin" --format "{{.Names}}"); do
  echo "=== $container ==="
  docker logs $container 2>&1 | grep -E "FAIL-CLOSE|MISSING.*INGRESS|CRITICAL CONFIGURATION|TransactionSimulator" | head -5
done

# Contar ocorrências de fail-close
docker logs plugin-failclose-sem-ingress-rpc-node-1 2>&1 | grep -c "FAIL-CLOSE"

# Extrair linhas com contexto (5 linhas antes/depois)
docker logs plugin-failclose-sem-ingress-validador-1 2>&1 | grep -B5 -A5 "FAIL-CLOSE"

# Salvar logs para auditoria
docker logs plugin-failclose-sem-ingress-validador-1 > failclose-evidence-$(date +%Y%m%d-%H%M).log 2>&1
```

### 6.3 Métricas Prometheus que Corroboram Fail-Close

```bash
# Blocos total = 0 (rede parada por fail-close)
curl -s http://localhost:9541/metrics | grep "besu_blockchain_chain_head"

# Peers = 0 (sem conexões P2P permitidas)
curl -s http://localhost:9541/metrics | grep "besu_peers"

# Transações = 0 (nenhuma transação minerada)
curl -s http://localhost:9541/metrics | grep "besu_blockchain_chain_head_transaction_count"
```

---

## 7. “Brincando” com Requisições no Postman (Modo Interativo)

### Sim, é totalmente possível. Roteiro:

### 7.1 Setup

1. **Tenha uma rede saudável rodando** (com Ingress válido):
   ```bash
   ./test-suite/run.sh --action stop
   ./test-suite/run.sh --config configs/cenario-valioso.json
   ```

2. **Importe a coleção no Postman** (`Plugin Permissioning_Permissioning.postman_collection.json`)

3. **Confirme que `{{RPC_URL}}` aponta para `http://localhost:9005`**

### 7.2 Coisas que Dá pra Fazer

#### a) Disparar Transações em Série (Postman Runner)
- Abra o Runner (ícone ▶ no canto)
- Selecione PF-01, arraste para a lista
- Configure 50 iterações, delay 100ms
- Execute e veja os hashes aparecerem

#### b) Enviar Transações Assinadas Manualmente

Use `eth_sendRawTransaction` com uma raw TX válida. Exemplo com Python para gerar a raw TX:

```python
# Gera raw transaction assinada para usar no Postman
from eth_account import Account
from eth_account.messages import encode_defunct
import rlp
from eth_account._utils.signing import sign_transaction_dict

# Chave privada do ADMIN (conta permitida)
priv_key = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
acct = Account.from_key(priv_key)

# Construir transação simples (enviar 1 wei para si mesmo)
tx = {
    'nonce': 0,
    'gasPrice': 1,
    'gas': 21000,
    'to': '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266',
    'value': 1,
    'data': b'',
    'chainId': 12120014
}
signed = acct.sign_transaction(tx)
raw_tx = signed.raw_transaction.hex()
print(f"Raw TX: 0x{raw_tx}")
```

Copie o `0x...` e cole no body da request PF-01 no Postman ({params: ["0x..."]}).

#### c) Alterar Permissões On-Chain em Tempo Real

1. No Postman, crie uma nova request:
   - Method: `POST`
   - URL: `{{RPC_URL}}`
   - Body (raw):
```json
{
  "jsonrpc": "2.0",
  "method": "eth_sendRawTransaction",
  "params": ["0x...TX_ASSINADA_COM_CALLDATA_PARA_O_INGRESS..."],
  "id": 1
}
```

2. Envie. A transação é minerada → o plugin lê o novo estado no próximo bloco (cache de 1 bloco).
3. Verifique a mudança de comportamento imediatamente:
   - Envie PF-02 (conta bloqueada) → antes era rejeitada
   - Se você adicionou a conta ao Rules, agora PF-02 deve passar

#### d) “Flood” Manual de Transações (Brincadeira de Carga)

No Postman Runner:
- Crie uma request "Flood" com PF-01
- Runner → 200 iterações → delay 0ms
- Observe no log:
```bash
docker logs -f plugin-valioso-net-rpc-node-1 | grep -E "Permission check|Sender"
```

---

## 8. Checklist de Validação para Novos Cenários

Antes de considerar um novo cenário "pronto", verifique:

- [ ] Arquivo JSON válido (sem vírgulas extras, campos obrigatórios preenchidos)
- [ ] Portas não conflitam com outros cenários ou serviços locais
- [ ] `nome_rede` único (Docker usa como namespace)
- [ ] Número de validadores ≥ 4 se for cenário que precisa de consenso QBFT (senão rede não produz blocos)
- [ ] `ingress_address` com `0x` prefix, mesmo se vazio (`""`)
- [ ] Cenário sobe sem erros: `./run.sh --config configs/seu-cenario.json`
- [ ] `docker ps` mostra todos os containers como `healthy`
- [ ] `curl localhost:<porta>/metrics` retorna métricas Prometheus
- [ ] Postman/Newman consegue executar as requests contra o cenário
- [ ] Logs capturados confirmam o comportamento esperado (fail-close ou transações permitidas)
- [ ] Documentar no `README.md` a nova cobertura

### Comandos de Sanidade Pós-Start

```bash
# 1. Container status
docker ps --filter "name=plugin" --format "table {{.Names}}\t{{.Status}}"

# 2. Bloco atual (deve avançar se ≥4 validadores)
curl -s -X POST http://localhost:9001 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# 3. Peer count
curl -s -X POST http://localhost:9001 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'

# 4. Validadores QBFT
curl -s -X POST http://localhost:9001 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"qbft_getValidatorsByBlockNumber","params":["latest"],"id":1}'
```

---

## 9. Referência Rápida: Hierarquia de Arquivos e Responsabilidades

```
Você edita:
  configs/*.json          → Define topologia, versões, plugin on/off, ingress
  postman/*.json          → Define chamadas JSON-RPC e cenários de validação HTTP
  README.md               → Documenta cobertura de cenários

Orquestrador usa:
  templates/genesis.json  → Bloco gênese (chainId 12120014, contratos, alloc)
  templates/log.xml       → Filtro de logs (DEBUG para plugin de permissionamento)
  orchestrator.py         → Lê config → gera run-data/docker-compose.yml → docker compose up

Runtime (gerado, não editar):
  run-data/
    docker-compose.yml    → Gerado dinamicamente pelo orchestrator
    genesis.json          → Cópia do template
    log.xml               → Cópia do template
    volumes/              → Chaves + dados de blockchain persistentes
```

---

## Apêndice A: Tabela de Portas

| Porta Host | Uso | Container Interno |
|---|---|---|
| 9001 | RPC validador-1 (bootnode) | 8545 |
| 9002 | RPC validador-2 | 8545 |
| 9003 | RPC validador-3 | 8545 |
| 9004 | RPC validador-4 | 8545 |
| 9005 | RPC rpc-node-1 | 8545 |
| 9006-9010 | Livres para novos nós RPC | 8545 |
| 9541 | Métricas validador-1 | 9545 |
| 9542 | Métricas validador-2 | 9545 |
| 9543 | Métricas validador-3 | 9545 |
| 9544 | Métricas validador-4 | 9545 |
| 9545 | Métricas rpc-node-1 | 9545 |
| 9546-9550 | Livres para novas métricas | 9545 |

## Apêndice B: Contas de Teste Pré-Financiadas

| Papel | Endereço | Chave Privada |
|---|---|---|
| ADMIN (permitida) | `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` | `0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80` |
| UNAUTH (bloqueada) | `0x70997970C51812dc3A010C7d01b50e0d17dc79C8` | `0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d` |

## Apêndice C: Contratos Implantados no Genesis

| Contrato | Endereço | Propósito |
|---|---|---|
| Account Ingress | `0x0000000000000000000000000000000000008888` | Roteia chamadas de permissão de contas |
| Node Ingress | `0x0000000000000000000000000000000000009999` | Roteia chamadas de permissão de nós |
| Account Rules | `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512` | Armazena regras de permissão de contas |
| Node Rules | `0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0` | Armazena regras de permissão de nós |
