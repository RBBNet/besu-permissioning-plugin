"""# ⚙️ Guia de Orquestração e Operação de Infraestrutura — RBB

Este diretório consolida a suíte de ferramentas e scripts de automação projetados para gerenciar o ciclo de vida, a governança e a observabilidade da **Rede Blockchain Brasil (RBB)** em ambientes de simulação (*Rede Toy*).

Essas ferramentas foram desenvolvidas seguindo padrões de Engenharia de Plataforma (DevOps/SecOps) para mitigar erros operacionais, abstraindo a complexidade de orquestração do Docker, interações com a máquina virtual Ethereum (EVM) e chamadas diretas a contratos inteligentes de permissionamento e governança.

---

## 🗂️ Sumário de Ferramentas por Domínio

### 1. Gestão Unificada do Ciclo de Vida (Nós)
Scripts voltados para o dia a dia da operação e escalabilidade horizontal programada da rede.

* **`menu_nos.sh`**
  * **Função:** Interface iterativa de controle de infraestrutura.
  * **Descrição:** Fornece um painel centralizado em terminal para aplicar comandos de estado do Docker (Start, Stop, Restart) em instâncias específicas da rede. O script aplica mapeamento ordenado e filtros baseados no status de cada contêiner, prevenindo falhas de comando.
  * **Uso:**
    ```bash
    ./menu_nos.sh [nome_da_rede]
    ```

* **`auto_node.sh`**
  * **Função:** Pipeline de integração e provisionamento dinâmico de nós.
  * **Descrição:** Orquestra todo o processo necessário para expandir a rede de forma segura:
    1. Estrutura pastas de volumes e gera credenciais criptográficas.
    2. Transaciona a autorização do novo *Enode* On-Chain via hardhat na lista do `NodeRulesV2Impl`.
    3. Inicia o contêiner em modo de varredura para sincronização inicial P2P.
    4. Executa um *reboot* da máquina para isolar e aplicar o Plugin RBB (Ingress).
  * **Uso:**
    ```bash
    ./auto_node.sh <nome_da_rede> <nome_do_novo_no> <porta_rpc>
    ```

### 2. Procedimentos Manuais Avançados e Depuração
Camadas modulares de execução, ideais para operações manuais granulares, análise de comportamento de rede ou diagnóstico de falhas (Debugging).

* **`add_node.sh`**
  * **Função:** Provisionamento sem camada de segurança ativa.
  * **Descrição:** Aloca chaves criptográficas e instancia o nó em modo aberto. Interrompe seu próprio processo para permitir a validação explícita On-Chain através da execução direta via JavaScript (Hardhat), garantindo ampla transparência da sincronização.
  * **Uso:**
    ```bash
    ./add_node.sh <nome_da_rede> <nome_do_no> <porta_rpc>
    ```

* **`secure_node.sh`**
  * **Função:** Injeção pós-sincronização de regras de segurança.
  * **Descrição:** Destrói e recria o contêiner isolado de um nó previamente validado, implementando os redirecionamentos restritivos por meio das variáveis de ambiente de `NodeIngress` e `AccountIngress` lidas pelo plugin Java da RBB.
  * **Uso:**
    ```bash
    ./secure_node.sh <nome_da_rede> <nome_do_no> <porta_rpc>
    ```

### 3. Governança e Consenso Protocolar (QBFT)
Ferramentas de comunicação direta em baixo nível com o protocolo blockchain subjacente.

* **`vote.sh`**
  * **Função:** Gestão de participação da camada de validação.
  * **Descrição:** Implementa interface CLI de acesso aos terminais RPC da Hyperledger Besu. Executa votos criptográficos do protocolo BFT propondo a ascensão de nós regulares (writers) para validadores, bem como o rebaixamento de nós corrompidos.
  * **Uso:**
    ```bash
    ./vote.sh <porta_rpc_votante> <endereco_alvo> <true/false>
    ```

### 4. Sustentação, Telemetria e Observabilidade
Gestão da conectividade dos *endpoints* operacionais.

* **`update_prom.sh`**
  * **Função:** Automação de registros de monitoramento em tempo real.
  * **Descrição:** Injera com precisão o IP ou porta de métricas de novos nós ao ambiente `prometheus.yml`. Efetua um ciclo de recarga programada no contêiner do Prometheus, propagando imediatamente os metadados de carga e saúde de bloco para os painéis (Dashboards) do Grafana.
  * **Uso:**
    ```bash
    ./update_prom.sh <nome_da_rede> <nome_do_no>
    ```

---