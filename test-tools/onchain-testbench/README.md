# On-Chain Testbench

A modular Java testbench for validating on-chain permissioning policies in Hyperledger Besu networks. It orchestrates network container topologies, injects network scenarios, and verifies permissioning rules using a fluent Java DSL.

## Project Structure

```text
onchain-testbench/
├── src/main/java/org/hyperledger/besu/testframework/
│   ├── contracts/          # GenesisStrategy, PermissioningStrategy
│   ├── core/               # BlockchainNetwork, BesuNode, ConsensusTopology
│   ├── dsl/                # BesuNodeAssert, ContainerLogAssert
│   ├── orchestrator/       # BesuNodeFactory, NetworkOrchestrator
│   ├── reporting/          # SuperLog, TestReporter, Evidence
│   └── scenarios/          # FailClose, CacheInvalidation, etc.
├── src/test/java/org/hyperledger/besu/testframework/
│   ├── FrameworkUnitTest.java        # Unit tests
│   ├── MultiVersionPluginTest.java   # Besu multi-version compatibility
│   ├── SuperLogTest.java             # Evidence reporting tests
│   └── SmokeTest.java               # End-to-end integration (requires Docker)
├── build.gradle
├── genesis.json                      # Ingress contract genesis
└── genesis-evolution.json            # Pre-deployed contract genesis
```

## Running Tests

```bash
cd test-tools/onchain-testbench

# Unit tests (without Docker)
./gradlew test --tests "*FrameworkUnitTest" --tests "*SuperLogTest"

# Integration tests (requires Docker daemon)
./gradlew test --tests "*MultiVersionPluginTest" -DrunIntegration=true
./gradlew test --tests "*SmokeTest" -DrunIntegration=true
```
