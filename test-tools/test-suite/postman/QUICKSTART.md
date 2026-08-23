# QUICKSTART: Testes Postman em 5 Passos

## Pré-requisitos
- Docker rodando
- Python 3 instalado
- Postman instalado

## Passo 1: Subir a Rede

```bash
cd test-suite/
python3 orchestrator.py -c configs/cenario-valioso.json -a start --timeout 180
```

**Opcional**: Para ver métricas em tempo real (Grafana + Prometheus):
```bash
python3 orchestrator.py -c configs/cenario-valioso.json -a start --timeout 180 --monitoring
# Acesse Grafana: http://localhost:3000 (admin/admin)
```

Aguarde até ver: `A REDE BLOCKCHAIN Plugin Permissioning ESTÁ DE PÉ E OPERACIONAL!`

## Passo 2: Gerar Transações Assinadas

```bash
cd postman/

# Instalar dependências (só na primeira vez)
pip3 install coincurve rlp eth-utils requests pycryptodome

# Gerar transação ADMIN (conta permitida)
python3 gen-signed-tx.py

# Copie o hex que aparece (cole no Postman depois)

# Gerar transação UNAUTH (conta bloqueada)
python3 gen-signed-tx.py --account unauth

# Copie o hex também
```

## Passo 3: Configurar Postman

1. Abra o Postman
2. Clique em **Import** → selecione `Permissioning.postman_collection.json`
3. Na coleção importada, vá em **Variables** (aba superior)
4. Preencha:
   - `ADMIN_SIGNED_TX`: cole o hex da transação ADMIN
   - `UNAUTH_SIGNED_TX`: cole o hex da transação UNAUTH
   - `RPC_URL`: deixe como `http://localhost:9005`

## Passo 4: Executar os Testes (na ordem)

Execute cada pasta **na ordem**:

| Ordem | Pasta | O que testa |
|-------|-------|-------------|
| 1º | ⚙️ **0. Setup** | Verifica se a rede está respondendo |
| 2º | 🔐 **1. Account Permissioning** | Testa se ADMIN passa e UNAUTH é bloqueada |
| 3º | 🌐 **2. Node Permissioning** | Verifica permissão de nós |
| 4º | 📋 **3. Verificação de Contratos** | Confirma que contratos estão deployados |
| 5º | 💥 **4. Chaos & Fail-Close** | Testa cenários de falha (precisa de rede separada) |

## Passo 5: Interpretar Resultados

- ✅ **Teste verde**: Passou (transação aceita ou rejeitada corretamente)
- ❌ **Teste vermelhou**: Falhou (verifique se as transações estão assinadas corretamente)

## Troubleshooting Rápido

### Erro `-32602 Invalid params`
- Transação mal formada
- Solução: Rode `gen-signed-tx.py` novamente com nonce correto

### Erro `-32000` em vez de `-32007`
- Besu retorna -32000 para alguns erros
- Se mensagem contiver "not authorized", plugin está funcionando

### PF-02 passou (conta bloqueada foi aceita)
- Plugin não está bloqueando
- Verifique: `docker logs plugin-valioso-net-rpc-node-1 | grep -i permission`

## Para Parar a Rede

```bash
cd test-suite/
python3 orchestrator.py -a stop
```

**Nota**: O comando `stop` também para o stack de monitoramento (Prometheus + Grafana) se estiver rodando.
