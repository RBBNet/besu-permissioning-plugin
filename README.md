# Hyperledger Besu On-Chain Permissioning Plugin

An enterprise-grade **On-Chain Permissioning Plugin** for Hyperledger Besu. This plugin validates EVM transactions and P2P node connections using smart contracts via Besu's `TransactionSimulationService` API.

It restores and modernizes the on-chain permissioning capabilities using an Ingress-Proxy-Rules smart contract pattern.

## Features

- **Transaction Permissioning:** Validates transaction sender, target, value, gas, and payload against dynamic smart contract rules (`transactionAllowed`).
- **Node Connection Permissioning:** Validates P2P connection handshakes between nodes supporting both V1 tripwire smart contracts (`connectionAllowed(bytes32,...)`) and V2 per-enode contracts (`connectionAllowed(string,string,uint16)`).
- **In-Memory EVM Simulation:** Queries smart contract state using `TransactionSimulationService` against the pending/latest block head — zero external HTTP dependencies, zero state mutations.
- **Fail-Close Security:** Strict fail-close design; any configuration error, invalid ABI payload, or simulation exception automatically rejects unauthorized transactions and node connection attempts.
- **Prometheus Metrics:** Exposes transaction and node connection permissioning counters (`permitted`, `denied`, `total_checked`).
- **Multi-Version Besu Compatibility:** Compatible with Hyperledger Besu 25.12.0+ and 26.x.

## Architecture

```
                  +--------------------------+
                  | Hyperledger Besu Node    |
                  +------------+-------------+
                               |
                   Permissioning Event Callback
                               |
                               v
               +---------------+---------------+
               | PermissioningPlugin           |
               +---------------+---------------+
                               |
              Resolve Rules Address via Ingress
                               |
                               v
            +------------------+-------------------+
            | TransactionSimulationService (EVM)  |
            +------------------+-------------------+
                               |
        Simulates call against latest chain state
                               |
               +---------------+---------------+
               | Smart Contracts               |
               | - Ingress Contract            |
               | - Rules Contract              |
               +---------------+---------------+
                               |
                 Return True (Permitted) / False
```

1. **Ingress Resolution:** On block transitions, the plugin queries an Ingress smart contract to resolve the active Rules contract address.
2. **Dynamic Rules Validation:** Queries `transactionAllowed()` or `connectionAllowed()` on the resolved Rules contract.
3. **Simulation Execution:** Validation is performed in-memory via `simulate()` against the chain state without submitting state-changing transactions.
4. **Fail-Close Policy:** Any resolution failure, invalid address format, or EVM execution exception immediately causes the plugin to reject the operation.

## Building from Source

Requires **Java 21** and Gradle.

```bash
./gradlew clean shadowJar
```

The output fat JAR will be created at:
`build/libs/besu-plugin-permissioning.jar`

## Installation

Copy `besu-plugin-permissioning.jar` into the `plugins/` directory of your Hyperledger Besu node:

```bash
cp build/libs/besu-plugin-permissioning.jar /opt/besu/plugins/
```

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS` | Yes | — | Hex address of Account Ingress smart contract (e.g. `0x...8888`) |
| `BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS` | Optional | — | Hex address of Node Ingress smart contract. If omitted, node permissioning operates in explicit fail-close mode. |
| `BESU_PERMISSIONS_NODES_CONTRACT_VERSION` | Optional | `1` | Node contract interface version (`1` for V1 tripwire, `2` for V2 per-enode ABI-encoded bool). |

Configuration is validated when the plugin registers. A missing `BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS`, a zero address, or any other invalid value, makes the plugin throw and **Besu aborts startup** with a message listing every problem. Do not enable `--plugin-continue-on-error`: with it, Besu would start **without** the plugin, that is, without any on-chain permissioning.
| `BESU_PERMISSIONS_SIMULATION_GAS_LIMIT` | Optional | `3000000` | Gas limit allocated for in-memory contract simulation calls. |
| `BESU_PERMISSIONS_FIXED_NODES` | Optional | — | Comma-separated enode URLs permitted while the node has not yet reached sync, in addition to `static-nodes.json` in the data path and `BESU_BOOTNODES`. See [Node permissioning during initial sync](#node-permissioning-during-initial-sync). |

### Node permissioning during initial sync

While a node syncs, its local chain state is old, so the node permissioning contract cannot be evaluated against the current state of the network. Like Besu's former native on-chain permissioning, the plugin handles this window explicitly:

- **Until the node first reaches sync**, only outbound connections to fixed nodes are permitted, without calling the contract. Inbound connections are refused.
- **After the node reaches sync**, every connection is decided by the contract.

Fixed nodes are read from `static-nodes.json` in the data path, `BESU_BOOTNODES` and `BESU_PERMISSIONS_FIXED_NODES`. The node's own enode is ignored if it is listed. Set `BESU_PERMISSIONS_FIXED_NODES` when:

- the static nodes file is outside the data path (`--static-nodes-file`);
- bootnodes are given on the command line or in a TOML config file, which the plugin cannot read.

If no fixed node is configured, or the plugin cannot determine the local node ID (read from the Besu security module selected by `BESU_SECURITY_MODULE`, default `localfile`), every connection is decided by the contract, also during sync.


### Docker Compose Example

```yaml
version: '3.8'

services:
  besu:
    image: hyperledger/besu:25.12.0
    container_name: besu-permissioned-node
    environment:
      BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS: "0x0000000000000000000000000000000000008888"
      BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS: "0x0000000000000000000000000000000000009999"
      BESU_PERMISSIONS_NODES_CONTRACT_VERSION: "1"
    volumes:
      - ./build/libs/besu-plugin-permissioning.jar:/opt/besu/plugins/besu-plugin-permissioning.jar:ro
    ports:
      - "8545:8545"
      - "30303:30303"
```

## Testing

Run unit tests:
```bash
./gradlew test
```

Run code formatting checks:
```bash
./gradlew spotlessCheck
```

Run automated formatting:
```bash
./gradlew spotlessApply
```

## License

This project is licensed under the **Apache License 2.0** - see the [LICENSE](LICENSE) file for details.
