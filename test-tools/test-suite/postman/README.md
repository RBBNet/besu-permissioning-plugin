# 📮 Guia de Testes via Postman & Newman

Este diretório contém a coleção do Postman para validar cenários de permissionamento on-chain no **Plugin Sandbox** (Hyperledger Besu).

## 🚀 Fluxo Completo de Teste

### 1. Subir a Rede

```bash
cd test-suite/
python3 orchestrator.py -c configs/cenario-valioso.json -a start --timeout 180
```

Aguarde a mensagem: `A REDE BLOCKCHAIN Plugin Permissioning ESTÁ DE PÉ E OPERACIONAL!`

Anote os endpoints e endereços exibidos — a coleção já vem pré-configurada com os valores corretos, mas confira.

### 2. Gerar Transações Assinadas

**Importante**: As transações precisam ser assinadas com a chainId correta (12120014) e o nonce atual da conta. A coleção antiga usava transações pré-assinadas com chainId errada → erro `-32602 Invalid params`.

```bash
cd postman/

# Instalar dependências (uma vez)
pip3 install coincurve rlp eth-utils requests pycryptodome

# Gerar transação para conta ADMIN (permitida)
python3 gen-signed-tx.py

# Gerar transação para conta UNAUTH (bloqueada)
python3 gen-signed-tx.py --account unauth
```

Cada comando exibe o hex da transação assinada. Copie para as variáveis da coleção.

### 3. Configurar o Postman

1. Abra o Postman.
2. **Import** → selecione `Permissioning.postman_collection.json`.
3. A coleção **Plugin Permissioning Suite v2** aparece no workspace.
4. Clique na coleção → aba **Variables**:
   - `ADMIN_SIGNED_TX`: cole o hex do passo 2 (`--account admin`)
   - `UNAUTH_SIGNED_TX`: cole o hex do passo 2 (`--account unauth`)
   - Confira `RPC_URL` = `http://localhost:9005`

### 4. Executar os Testes (na ordem)

| Ordem | Pasta | O que faz |
|-------|-------|-----------|
| 1º | ⚙️ **0. Setup** | Verifica conectividade, chainId, bloco, nonce |
| 2º | 🔐 **1. Account Permissioning** | PF-01 (admin aceita), PF-02 (unauth rejeita), consultas on-chain |
| 3º | 🌐 **2. Node Permissioning** | Consulta NodeRules |
| 4º | 📋 **3. Verificação de Contratos** | Bytecode dos 5 contratos pré-deployados |
| 5º | 💥 **4. Chaos & Fail-Close** | Requer cenários FC separados |

---

## 📂 Organização da Coleção

### ⚙️ 0. Setup & Diagnóstico
- **0.1 eth_chainId** — Deve retornar `0xb8efce` (12120014)
- **0.2 eth_blockNumber** — Bloco atual da rede
- **0.3 eth_gasPrice** — Preço do gás em wei
- **0.4 eth_getTransactionCount (ADMIN)** — Nonce da conta ADMIN. Use este valor se precisar gerar transação com `--nonce <N>`
- **0.5 eth_getTransactionCount (UNAUTH)** — Nonce da conta bloqueada

### 🔐 1. Account Permissioning (PF)
- **PF-01** — `eth_sendRawTransaction` com conta ADMIN → ✅ Aceita (retorna tx hash)
- **PF-02** — `eth_sendRawTransaction` com conta UNAUTH → ❌ Rejeitada (erro -32007)
- **PF-03** — `eth_call accountPermitted(ADMIN)` no AccountRules → `true`
- **PF-04** — `eth_call accountPermitted(UNAUTH)` no AccountRules → `false`

### 🌐 2. Node Permissioning (NR)
- **NR-01** — `eth_call nodePermitted()` no NodeRules

### 📋 3. Verificação de Contratos (CV)
- **CV-01 a CV-05** — `eth_getCode` para cada contrato pré-deployado
  - Account Ingress (`0x0000...8888`)
  - Node Ingress (`0x0000...9999`)
  - Admin Proxy (`0x181a92c9...`)
  - AccountRules (`0x0e9e81bb...`)
  - NodeRules (`0xf01d20a2...`)

### 💥 4. Chaos & Fail-Close (FC)
- **FC-01** — Rede sem Ingress → Todas transações bloqueadas
- **FC-02** — Ingress inválido → Fail-close, todas transações bloqueadas

⚠ Para FC-01/FC-02 é necessário subir a rede com as configs específicas:
```bash
python3 orchestrator.py -c configs/cenario-failclose-sem-ingress.json -a start --timeout 180
python3 orchestrator.py -c configs/cenario-failclose-ingress-invalido.json -a start --timeout 180
```

---

## 🔧 Script Auxiliar: gen-signed-tx.py

Gera transações assinadas com EIP-155 usando a chainId correta.

```bash
# Uso básico
python3 gen-signed-tx.py                           # Conta ADMIN (padrão)
python3 gen-signed-tx.py --account unauth          # Conta UNAUTH
python3 gen-signed-tx.py --rpc-url http://localhost:9001  # Outro nó

# Parâmetros manuais (sem consultar o nó)
python3 gen-signed-tx.py --nonce 0 --gas-price 0 --chain-id 12120014

# Saída JSON para scripts
python3 gen-signed-tx.py --json
```

### Opções

| Flag | Padrão | Descrição |
|------|--------|-----------|
| `--account` | `admin` | `admin` ou `unauth` |
| `--rpc-url` | `http://localhost:9005` | URL do nó RPC |
| `--nonce` | auto | Nonce (auto-detecta do nó) |
| `--gas-price` | auto | Gas price em wei (auto-detecta) |
| `--gas-limit` | `21000` | Gas limit |
| `--value` | `1` | Valor em wei |
| `--to` | self | Endereço destino (padrão: self-transfer) |
| `--chain-id` | auto | Chain ID (auto-detecta) |
| `--json` | — | Saída JSON |

---

## 🤖 Execução via Newman (CLI)

```bash
npm install -g newman

# Gerar transações primeiro, depois exportar variáveis
ADMIN_TX=$(python3 gen-signed-tx.py --json | jq -r '.signed_raw_tx')
UNAUTH_TX=$(python3 gen-signed-tx.py --account unauth --json | jq -r '.signed_raw_tx')

# Executar coleção
newman run Permissioning.postman_collection.json \
  --env-var "RPC_URL=http://localhost:9005" \
  --env-var "ADMIN_SIGNED_TX=$ADMIN_TX" \
  --env-var "UNAUTH_SIGNED_TX=$UNAUTH_TX"
```

---

## ❗ Troubleshooting

### Erro `-32602 Invalid params` no PF-01 ou PF-02
A transação está mal formada. Causas comuns:
1. **Chain ID incorreta** — execute o request 0.1 para confirmar
2. **Nonce errado** — execute o request 0.4/0.5 para ver o nonce atual
3. **Transação antiga** — rode `gen-signed-tx.py` novamente

### Erro `-32000` no PF-02 em vez de `-32007`
O Besu retorna `-32000` para alguns erros de validação. Se a mensagem contiver "not authorized" ou "permission", o plugin está funcionando.

### PF-02 passou (transação de UNAUTH aceita)
O plugin NÃO está bloqueando. Verifique:
- `docker logs plugin-valioso-net-rpc-node-1 | grep -i permission`
- O Ingress address está correto na config?
- O AccountRules está implantado? (execute CV-04)
