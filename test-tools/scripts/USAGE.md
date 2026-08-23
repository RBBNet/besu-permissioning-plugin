# ⚙️ Infrastructure Orchestration & Operation Guide

This directory consolidates the suite of automation tools and scripts designed to manage the lifecycle, governance, and observability of Hyperledger Besu permissioned networks in simulation and test environments.

These tools follow Platform Engineering (DevOps/SecOps) best practices to automate Docker container orchestration, EVM interactions, and smart contract configuration.

---

## 🗂️ Tool Reference by Domain

### 1. Unified Lifecycle Management (Nodes)

* **`menu_nodes.sh`**
  * **Function:** Interactive terminal UI for network container control.
  * **Description:** Provides a centralized CLI panel to execute Docker state commands (Start, Stop, Restart) on specific network node instances.
  * **Usage:**
    ```bash
    ./menu_nodes.sh [network_name]
    ```

* **`auto_node.sh`**
  * **Function:** Automated node provisioning pipeline.
  * **Description:** Orchestrates the process required to expand the network safely:
    1. Prepares volume directories and generates cryptographic node credentials.
    2. Submits on-chain authorization for the new *Enode* via smart contract rules.
    3. Boots the container for initial P2P synchronization.
    4. Applies Node Ingress and Account Ingress environment variables for plugin enforcement.
  * **Usage:**
    ```bash
    ./auto_node.sh <network_name> <new_node_name> <rpc_port>
    ```

### 2. Manual Operations & Debugging

* **`add_node.sh`**
  * **Function:** Open node provisioning for debugging.
  * **Description:** Generates cryptographic keys and instantiates a node instance in initial open mode for manual synchronization verification.
  * **Usage:**
    ```bash
    ./add_node.sh <network_name> <node_name> <rpc_port>
    ```

* **`secure_node.sh`**
  * **Function:** Post-synchronization security enforcement.
  * **Description:** Recreates a node container with strict `BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS` and `BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS` environment variables passed to the Java permissioning plugin.
  * **Usage:**
    ```bash
    ./secure_node.sh <network_name> <node_name> <rpc_port>
    ```

### 3. Consensus Governance (QBFT)

* **`vote.sh`**
  * **Function:** Validator set management interface.
  * **Description:** CLI interface connecting to Hyperledger Besu JSON-RPC endpoints to propose validator additions or removals via QBFT consensus RPC API calls.
  * **Usage:**
    ```bash
    ./vote.sh <rpc_port> <validator_address> <true|false>
    ```

---

## 🔒 Security Best Practices

1. **Environment Variables:** Always configure ingress addresses via environment variables (`BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS` and `BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS`).
2. **Fail-Close Safeguards:** If ingress environment variables are missing or invalid, the plugin operates in strict fail-close mode to prevent unauthorized access.