# On-Chain Testbench

Testbench modular para validação de permissionamento on-chain em redes Hyperledger Besu. Orquestra redes completas, injeta falhas e valida políticas de governança via DSL fluente Java.

## Plugin — eth_call

O plugin foi migrado de `TransactionSimulator` para consulta via `eth_call` (JSON-RPC). Elimina incompatibilidades com opcodes Shanghai/Cancun (PUSH0, mcopy) em contratos compilados com Solc 0.8.28.

## Estrutura

```
onchain-testbench/
├── src/main/java/org/hyperledger/besu/testframework/
│   ├── contracts/          # GenesisStrategy, PermissioningStrategy
│   ├── core/               # BlockchainNetwork, BesuNode, ConsensusTopology
│   ├── dsl/                # BesuNodeAssert, ContainerLogAssert
│   ├── orchestrator/       # BesuNodeFactory, NetworkOrchestrator
│   ├── reporting/          # SuperLog, TestReporter, Evidence
│   └── scenarios/          # FailClose, CacheInvalidation, etc.
├── src/test/java/org/hyperledger/besu/testframework/
│   ├── FrameworkUnitTest.java        # Testes unitários
│   ├── MultiVersionPluginTest.java   # Compatibilidade multi-versão Besu
│   ├── SuperLogTest.java             # Relatórios de evidência
│   └── SmokeTest.java               # End-to-end (requer Docker)
├── build.gradle
├── genesis.json                      # Genesis com Ingresses
└── genesis-evolution.json            # Genesis com contratos pré-deployados
```

## Testes

```bash
cd plugin-permissioned-rbb-integra/onchain-testbench

# Unitários (sem Docker)
gradle test --tests "*FrameworkUnitTest" --tests "*SuperLogTest"

# Integração (requer Docker + plugin JAR)
gradle test --tests "*MultiVersionPluginTest" -DrunIntegration=true
gradle test --tests "*SmokeTest" -DrunIntegration=true
```
