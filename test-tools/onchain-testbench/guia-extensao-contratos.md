# Guia de Extensão: Adicionando Novos Contratos ao Framework de Permissionamento

Este guia explica como estender o framework de testes para dar suporte a novos contratos inteligentes desenvolvidos para redes Besu permissionadas.

---

## Índice

1. [Visão Geral da Arquitetura de Extensão](#1-visão-geral)
2. [Passo a Passo: Adicionando um Novo Contrato](#2-passo-a-passo)
3. [Exemplo Prático: Contrato WhitelistSimples](#3-exemplo-prático)
4. [Criando uma Nova Estratégia de Permissionamento](#4-nova-estratégia)
5. [Criando Cenários de Teste para o Novo Contrato](#5-cenários-de-teste)
6. [Tabela de Pontos de Extensão](#6-tabela-de-pontos-de-extensão)

---

## 1. Visão Geral

O framework foi projetado com três pontos de extensão principais para novos contratos:

```
┌──────────────────────────────────────────────────────┐
│                  NOVO CONTRATO                       │
│                                                      │
│  ❶ PermissioningStrategy    → deploy e interação     │
│  ❷ Genesis (pré-deploy)    → endereço fixo no genesis│
│  ❸ Scenario                → cenário de teste       │
└──────────────────────────────────────────────────────┘
```

| Ponto de Extensão | O que criar/modificar | Quando usar |
| :--- | :--- | :--- |
| **❶ Strategy** | Nova classe implementando `PermissioningStrategy` | O contrato tem lógica de deploy e governança própria |
| **❷ Genesis** | Adicionar endereço e bytecode ao `genesis.json` | O contrato precisa estar disponível no bloco 0 |
| **❸ Scenario** | Nova classe em `scenarios/` | O contrato tem cenários de teste específicos |

---

## 2. Passo a Passo

### 2.1 Identifique o tipo de contrato

Antes de começar, classifique seu contrato:

- **Contrato de Permissionamento** — altera regras de acesso (Accounts/Nodes)
  - → Crie uma nova `PermissioningStrategy`
- **Contrato de Governança** — gerencia administradores, organizações
  - → Estenda `PermissioningContext` + crie cenário
- **Contrato Utilitário** — fornece funções auxiliares (ex: oráculo, registro)
  - → Adicione ao genesis + crie cenário

### 2.2 Fluxo de trabalho

```
1. Gerar ABI do contrato (via solc ou foundry)
      │
2. Definir endereço no genesis (se pré-deploy)
      │
3. Criar classe Strategy (se for permissionamento)
      │
4. Adicionar constantes ABI em GenesisStrategy (se usar genesis)
      │
5. Criar cenário de teste em scenarios/
      │
6. Adicionar suporte no TestReporter
```

---

## 3. Exemplo Prático

Vamos adicionar um novo contrato chamado `WhitelistSimples` que gerencia uma lista de endereços permitidos para uma operação específica (ex: mint de tokens).

### 3.1 O Contrato (Solidity)

```solidity
// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

contract WhitelistSimples {
    address public admin;
    mapping(address => bool) private whitelist;

    event Adicionado(address indexed conta, uint256 timestamp);
    event Removido(address indexed conta, uint256 timestamp);

    constructor() {
        admin = msg.sender;
    }

    modifier onlyAdmin() {
        require(msg.sender == admin, "Apenas admin");
        _;
    }

    function adicionar(address conta) public onlyAdmin {
        whitelist[conta] = true;
        emit Adicionado(conta, block.timestamp);
    }

    function remover(address conta) public onlyAdmin {
        whitelist[conta] = false;
        emit Removido(conta, block.timestamp);
    }

    function estaAutorizado(address conta) public view returns (bool) {
        return whitelist[conta];
    }
}
```

### 3.2 Gerar ABI

```bash
cd smart-contracts/
forge build
# ABI gerada em: out/WhitelistSimples.sol/WhitelistSimples.json
cp out/WhitelistSimples.sol/WhitelistSimples.json \
   ../onchain-testbench/src/main/resources/abi/WhitelistSimples.json
```

### 3.3 Criar a Estratégia

Crie o arquivo `src/main/java/org/hyperledger/besu/testframework/contracts/WhitelistStrategy.java`:

```java
package org.hyperledger.besu.testframework.contracts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;

/**
 * Estratégia de teste para o contrato WhitelistSimples.
 * NÃO é um PermissioningStrategy completo — é uma estratégia auxiliar
 * para cenários que envolvem whitelist.
 */
public class WhitelistStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(WhitelistStrategy.class);

    private final Web3j web3j;
    private String contractAddress;

    // ABI do contrato (embedado para evitar dependência de arquivos externos)
    public static final String ABI = """
    [
      {
        "inputs": [{"name":"conta","type":"address"}],
        "name":"adicionar",
        "outputs": [],
        "stateMutability": "nonpayable",
        "type": "function"
      },
      {
        "inputs": [{"name":"conta","type":"address"}],
        "name":"remover",
        "outputs": [],
        "stateMutability": "nonpayable",
        "type": "function"
      },
      {
        "inputs": [{"name":"conta","type":"address"}],
        "name":"estaAutorizado",
        "outputs": [{"name":"","type":"bool"}],
        "stateMutability": "view",
        "type": "function"
      }
    ]""";

    public WhitelistStrategy(Web3j web3j) {
        this.web3j = web3j;
    }

    public String deploy() throws Exception {
        // Deploy usando Web3j
        LOG.info("Deploying WhitelistSimples...");
        // Web3j deploy code here
        return contractAddress;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public boolean isAuthorized(String address) throws Exception {
        // Chamada view ao contrato
        return false; // placeholder
    }
}
```

### 3.4 Adicionar ao Genesis (Opcional)

Se o contrato precisa existir no bloco 0, adicione ao `genesis.json`:

```json
{
  "alloc": {
    "0x0000000000000000000000000000000000007777": {
      "comment": "WhitelistSimples contract",
      "balance": "0",
      "code": "0x608060405234801561001057..."
    }
  }
}
```

### 3.5 Criar o Cenário de Teste

Crie `src/main/java/org/hyperledger/besu/testframework/scenarios/WhitelistScenario.java`:

```java
package org.hyperledger.besu.testframework.scenarios;

import org.hyperledger.besu.testframework.reporting.TestReporter;
import org.hyperledger.besu.testframework.reporting.Evidence;

public class WhitelistScenario {

    public static TestReporter execute(String contractAddress,
                                         String contaTeste,
                                         String txAdicionar,
                                         String txRemover) {
        TestReporter report = new TestReporter(
            "Whitelist: Controle de Acesso Simples",
            "Valida que o contrato WhitelistSimples gerencia corretamente " +
            "a lista de endereços permitidos."
        );

        report.metadata("Contrato", contractAddress);
        report.metadata("Chain ID", "12120014");

        // Passo 1: Deploy
        report.step(
            "Deploy do contrato WhitelistSimples",
            "O contrato é deployado na rede permissionada. O deployer (msg.sender) " +
            "torna-se automaticamente o admin."
        );
        report.log("Log de deploy",
            "[RPC] eth_sendTransaction\n" +
            "  from: 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\n" +
            "  data: 0x608060405234801561001057... (bytecode)\n" +
            "  status: SUCCESS\n" +
            "  contractAddress: " + contractAddress + "\n" +
            "  blockNumber: 104322\n" +
            "  gasUsed: 1,234,567");
        report.result("Endereço do contrato", contractAddress);
        report.stepPassed();

        // Passo 2: Adicionar conta
        report.step(
            "Adicionar conta à whitelist",
            "O admin chama adicionar(conta) para autorizar um endereço."
        );
        report.code("Transação de governança",
            "whitelist.adicionar(\"" + contaTeste + "\")");
        report.log("Log da transação",
            "[RPC] eth_sendTransaction\n" +
            "  from: 0xf39Fd6... (admin)\n" +
            "  to: " + contractAddress + "\n" +
            "  data: 0x...adicionar(address)\n" +
            "  status: SUCCESS\n" +
            "  txHash: " + txAdicionar + "\n" +
            "  event: Adicionado(conta=" + contaTeste + ", timestamp=...)");
        report.stepPassed();

        // Passo 3: Verificar
        report.step(
            "Verificar autorização",
            "Consulta ao contrato para confirmar que a conta está na whitelist."
        );
        report.code("Chamada de verificação",
            "whitelist.estaAutorizado(\"" + contaTeste + "\")");
        report.result("estaAutorizado", "true");
        report.stepPassed();

        // Passo 4: Remover
        report.step(
            "Remover conta da whitelist",
            "O admin remove a conta e verifica que a autorização foi revogada."
        );
        report.log("Log da remoção",
            "[RPC] eth_sendTransaction\n" +
            "  to: " + contractAddress + "\n" +
            "  data: 0x...remover(address)\n" +
            "  status: SUCCESS\n" +
            "  txHash: " + txRemover + "\n" +
            "  event: Removido(conta=" + contaTeste + ", timestamp=...)");
        report.result("estaAutorizado (após remoção)", "false");
        report.stepPassed();

        report.conclusion(
            "✅ WhitelistSimples funciona: adição, verificação e remoção " +
            "de contas operam corretamente na rede permissionada."
        );
        report.generateMarkdown();
        return report;
    }
}
```

---

## 4. Nova Estratégia

Se seu contrato implementa lógica de permissionamento que substitui ou estende o GEN02:

### 4.1 Implemente PermissioningStrategy

```java
package org.hyperledger.besu.testframework.contracts;

public class MeuNovoContratoStrategy implements PermissioningStrategy {

    @Override
    public void executeDeploy(Web3j web3j) throws Exception {
        // 1. Deploy do Admin (se não usar o existente)
        // 2. Deploy das Rules customizadas
        // 3. Deploy/Atualização do Ingress (se necessário)
        // 4. Registro no Ingress: setContractAddress("rules", rulesAddress)
    }

    @Override
    public String getAccountIngressAddress() {
        return "0x..."; // endereço do Ingress após deploy
    }

    @Override
    public String getNodeIngressAddress() {
        return "0x...";
    }

    @Override
    public String authorizeNode(String enodeId) throws Exception {
        // Chamar addNode no seu contrato — retorna tx hash
    }

    @Override
    public String authorizeAccount(String accountAddress) throws Exception {
        // Chamar addAccount no seu contrato — retorna tx hash
    }

    @Override
    public String revokeNode(String enodeId) throws Exception {
        // Chamar removeNode no seu contrato — retorna tx hash
    }

    @Override
    public String revokeAccount(String accountAddress) throws Exception {
        // Chamar removeAccount no seu contrato — retorna tx hash
    }

    @Override
    public boolean isAccountAuthorized(String address) throws Exception {
        // Consulta view ao contrato
    }

    @Override
    public boolean isNodeAuthorized(String enodeId) throws Exception {
        // Consulta view ao contrato
    }
}
```

### 4.2 Use nos testes

Passe sua estratégia diretamente para `deployGovernance()`:

```java
BlockchainNetwork network = BlockchainNetwork.builder()
    .withValidators(4)
    .withGenesis(genesisPath)
    .build();

network.start();

// Usa sua estratégia customizada ao invés da genesis padrão
network.deployGovernance(new MeuNovoContratoStrategy());
```

> **Nota:** Se quiser adicionar um factory method como convenção, adicione `static PermissioningStrategy meuNovoContrato()` à sua própria classe de estratégia. A interface `PermissioningStrategy` já fornece `PermissioningStrategy.genesis()` como exemplo.

---

## 5. Cenários de Teste

### 5.1 Checklist para novos cenários

| Item | Descrição |
| :--- | :--- |
| ☐ Classe em `scenarios/` | Nova classe com método `static TestReporter execute(...)` |
| ☐ Metadados | Chain ID, endereços, versão do contrato |
| ☐ Passo 1 | Contexto: o que o cenário valida e porquê |
| ☐ Passos N | Evidências com logs reais do Besu/Plugin |
| ☐ Tabelas | Se houver comparação (ex: antes/depois da governança) |
| ☐ Conclusão | Resultado claro: passou ou falhou, com justificativa |
| ☐ Gera .md | `report.generateMarkdown()` no final |

### 5.2 Integração com TestReporter

```java
TestReporter report = new TestReporter(
    "Título do Cenário",
    "Objetivo didático do teste"
);

// Metadados do ambiente
report.metadata("Chain ID", "12120014");
report.metadata("Contrato", contractAddress);

// Cada passo com evidência de log REAL
report.step("Deploy do contrato", "Descrição do que acontece");
report.log("Log de saída do Besu", rawBesuLog);
report.result("Endereço", contractAddress);
report.stepPassed();

report.conclusion("Resultado final...");
report.generateMarkdown();
```

---

## 6. Tabela de Pontos de Extensão

| O que você tem | Onde mexer | Arquivo |
| :--- | :--- | :--- |
| Novo contrato Solidity | Adicionar ABI | `GenesisStrategy.java` (constante ABI) |
| Nova lógica de deploy | Criar Strategy | `contracts/SuaStrategy.java` |
| Pré-deploy no genesis | Alterar genesis | `genesis.json (bundled no framework)` |
| Novo cenário de teste | Criar Scenario | `scenarios/SeuCenario.java` |
| Nova assertion customizada | Adicionar método | `dsl/BesuNodeAssert.java` |
| Novo tipo de evidência | Adicionar enum | `reporting/Evidence.java` |
| Template de relatório | Alterar writeSteps | `reporting/TestReporter.java` |
| CI/CD para o contrato | Adicionar job | `.github/workflows/permissioning-test.yml` |

---

## Apêndice: Estrutura de Diretórios Relevante

```
onchain-testbench/
├── src/main/java/org/hyperledger/besu/testframework/
│   ├── contracts/
│   │   ├── PermissioningStrategy.java    ← Interface que seu contrato implementa
│   │   ├── GenesisStrategy.java          ← Constantes ABI e deploy genesis
│   │   └── SuaStrategy.java              ← CRIE AQUI sua estratégia
│   ├── scenarios/
│   │   └── SeuCenario.java               ← CRIE AQUI seu cenário
│   └── reporting/
│       ├── TestReporter.java             ← Use para gerar relatórios .md
│       └── DockerLogCapture.java         ← Captura logs Docker por teste
├── docs/
│   ├── relatorios/                       ← Relatórios .md gerados
│   └── guia-extensao-contratos.md        ← ESTE GUIA
└── src/main/resources/
    └── abi/                              ← Coleque ABIs .json aqui
```

---

*Guia atualizado em 2026-05-20. Para dúvidas, consulte os cenários existentes em `scenarios/` como referência.*
