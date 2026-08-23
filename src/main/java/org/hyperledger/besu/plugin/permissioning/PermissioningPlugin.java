/*
 * Copyright Consortium / Hyperledger Besu Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.besu.plugin.permissioning;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.PermissioningService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hyperledger Besu On-Chain Permissioning Plugin entry point. */
public class PermissioningPlugin implements BesuPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(PermissioningPlugin.class);
  private static final long DEFAULT_SIMULATION_GAS_LIMIT = 3_000_000L;

  private ServiceManager serviceManager;
  private TransactionSimulationService simulationService;
  private BlockchainService blockchainService;

  private Address accountIngressAddress;
  private Address nodeIngressAddress;

  private final AtomicReference<Address> accountRulesContractCache = new AtomicReference<>();
  private final AtomicReference<Address> nodeRulesContractCache = new AtomicReference<>();
  private volatile long lastAccountCacheUpdateBlock = -1;
  private volatile long lastNodeCacheUpdateBlock = -1;

  private final Object accountCacheLock = new Object();
  private final Object nodeCacheLock = new Object();

  private int nodeContractVersion = 1;
  private long simulationGasLimit = DEFAULT_SIMULATION_GAS_LIMIT;

  private Counter txCheckCounter;
  private Counter txPermittedCounter;
  private Counter txDeniedCounter;

  private Counter nodeCheckCounter;
  private Counter nodePermittedCounter;
  private Counter nodeDeniedCounter;

  @Override
  public void register(final ServiceManager context) {
    LOG.info("Registering On-Chain Permissioning Plugin");
    this.serviceManager = context;

    this.simulationService = context.getService(TransactionSimulationService.class).orElseThrow();
    this.blockchainService = context.getService(BlockchainService.class).orElseThrow();

    configureFromEnvironment();

    context
        .getService(PermissioningService.class)
        .ifPresent(
            service -> {
              service.registerTransactionPermissioningProvider(
                  new OnChainTransactionPermissioningProvider(this));
              service.registerNodePermissioningProvider(new OnChainNodePermissioningProvider(this));
            });
  }

  void configureFromEnvironment() {
    String accountIngressEnv = System.getenv("BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS");
    if (accountIngressEnv != null && !accountIngressEnv.isEmpty()) {
      try {
        this.accountIngressAddress = Address.fromHexString(accountIngressEnv);
        LOG.info("Account Permissioning Ingress Address set to: {}", accountIngressAddress);
      } catch (IllegalArgumentException e) {
        LOG.error("Invalid Account Ingress Address format. Fail-close activated for transactions.");
        this.accountIngressAddress = null;
      }
    } else {
      LOG.warn("Missing BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS environment variable.");
    }

    String nodeContractVersionEnv = System.getenv("BESU_PERMISSIONS_NODES_CONTRACT_VERSION");
    if (nodeContractVersionEnv != null && !nodeContractVersionEnv.isEmpty()) {
      try {
        int parsedVersion = Integer.parseInt(nodeContractVersionEnv);
        if (parsedVersion == 1 || parsedVersion == 2) {
          this.nodeContractVersion = parsedVersion;
        } else {
          LOG.error(
              "Invalid BESU_PERMISSIONS_NODES_CONTRACT_VERSION={}. Defaulting to 1.",
              parsedVersion);
          this.nodeContractVersion = 1;
        }
      } catch (NumberFormatException e) {
        LOG.error(
            "Unparseable BESU_PERMISSIONS_NODES_CONTRACT_VERSION={}. Defaulting to 1.",
            nodeContractVersionEnv);
        this.nodeContractVersion = 1;
      }
    }
    LOG.info("Node contract interface version set to {}", nodeContractVersion);

    String nodeIngressEnv = System.getenv("BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS");
    if (nodeIngressEnv != null && !nodeIngressEnv.isEmpty()) {
      try {
        this.nodeIngressAddress = Address.fromHexString(nodeIngressEnv);
        LOG.info("Node Permissioning Ingress Address set to: {}", nodeIngressAddress);
      } catch (IllegalArgumentException e) {
        LOG.error(
            "Invalid Node Ingress Address format. Fail-close activated for node connections.");
        this.nodeIngressAddress = null;
      }
    } else {
      LOG.warn(
          "Missing BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS. Node permissioning operating in explicit fail-close mode.");
      this.nodeIngressAddress = null;
    }

    String gasLimitEnv = System.getenv("BESU_PERMISSIONS_SIMULATION_GAS_LIMIT");
    if (gasLimitEnv != null && !gasLimitEnv.isEmpty()) {
      try {
        this.simulationGasLimit = Long.parseLong(gasLimitEnv);
        LOG.info("Simulation gas limit set to: {}", simulationGasLimit);
      } catch (NumberFormatException e) {
        LOG.error(
            "Unparseable BESU_PERMISSIONS_SIMULATION_GAS_LIMIT={}. Defaulting to {}.",
            gasLimitEnv,
            DEFAULT_SIMULATION_GAS_LIMIT);
        this.simulationGasLimit = DEFAULT_SIMULATION_GAS_LIMIT;
      }
    }
  }

  private void setupMetrics(final MetricsSystem metricsSystem) {
    txCheckCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "onchain_transaction_check_count",
            "Number of transactions checked by the on-chain permissioning plugin");
    txPermittedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "onchain_transaction_check_count_permitted",
            "Number of transactions permitted by the on-chain permissioning plugin");
    txDeniedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "onchain_transaction_check_count_denied",
            "Number of transactions denied by the on-chain permissioning plugin");

    nodeCheckCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "onchain_node_check_count",
            "Number of node connections checked");
    nodePermittedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "onchain_node_check_count_permitted",
            "Number of node connections permitted");
    nodeDeniedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "onchain_node_check_count_denied",
            "Number of node connections denied");
    LOG.info("PermissioningPlugin metrics counters registered successfully.");
  }

  @Override
  public void start() {
    LOG.info("On-Chain Permissioning Plugin started.");

    if (serviceManager != null) {
      serviceManager.getService(MetricsSystem.class).ifPresent(this::setupMetrics);
    }

    if (accountIngressAddress == null) {
      LOG.error(
          "On-Chain Permissioning Plugin Error: Account Ingress Address is unconfigured or invalid. Fail-close active.");
    }
    if (nodeIngressAddress == null) {
      LOG.warn(
          "On-Chain Permissioning Plugin Warning: Node Ingress Address is unconfigured. Node connections will be rejected.");
    }
  }

  @Override
  public void stop() {
    LOG.info("On-Chain Permissioning Plugin stopped.");
  }

  Optional<Bytes> callContract(final Address target, final Bytes payload) {
    try {
      CallParameter callParams =
          new CallParameter() {
            @Override
            public Optional<Address> getSender() {
              return Optional.of(Address.ZERO);
            }

            @Override
            public Optional<Address> getTo() {
              return Optional.of(target);
            }

            @Override
            public OptionalLong getGas() {
              return OptionalLong.of(simulationGasLimit);
            }

            @Override
            public Optional<Wei> getGasPrice() {
              return Optional.of(Wei.ZERO);
            }

            @Override
            public Optional<Wei> getValue() {
              return Optional.of(Wei.ZERO);
            }

            @Override
            public Optional<Bytes> getPayload() {
              return Optional.of(payload);
            }

            @Override
            public Optional<BigInteger> getChainId() {
              return Optional.empty();
            }

            @Override
            public Optional<Wei> getMaxPriorityFeePerGas() {
              return Optional.empty();
            }

            @Override
            public Optional<Wei> getMaxFeePerGas() {
              return Optional.empty();
            }

            @Override
            public Optional<Wei> getMaxFeePerBlobGas() {
              return Optional.empty();
            }

            @Override
            public Optional<List<org.hyperledger.besu.datatypes.AccessListEntry>> getAccessList() {
              return Optional.empty();
            }

            @Override
            public Optional<List<org.hyperledger.besu.datatypes.VersionedHash>>
                getBlobVersionedHashes() {
              return Optional.empty();
            }

            @Override
            public OptionalLong getNonce() {
              return OptionalLong.empty();
            }

            @Override
            public Optional<Boolean> getStrict() {
              return Optional.empty();
            }

            @Override
            public List<org.hyperledger.besu.datatypes.CodeDelegation>
                getCodeDelegationAuthorizations() {
              return List.of();
            }
          };

      ProcessableBlockHeader simHeader = simulationService.simulatePendingBlockHeader();
      if (simHeader == null) {
        simHeader = blockchainService.getChainHeadHeader();
      }

      return simulationService
          .simulate(
              callParams,
              Optional.empty(),
              simHeader,
              OperationTracer.NO_TRACING,
              EnumSet.of(
                  TransactionSimulationService.SimulationParameters.ALLOW_EXCEEDING_BALANCE,
                  TransactionSimulationService.SimulationParameters.ALLOW_UNDERPRICED))
          .filter(r -> r.result().isSuccessful())
          .map(r -> r.result().getOutput());
    } catch (Exception e) {
      LOG.error("PermissioningPlugin: simulate() failed for {}: {}", target, e.getMessage());
      return Optional.empty();
    }
  }

  boolean simulateAndGetBool(final Address target, final Bytes payload) {
    return callContract(target, payload).map(bytes -> !bytes.isZero()).orElse(false);
  }

  Address getAccountRulesAddress() {
    var head = blockchainService.getChainHeadHeader();
    long currentBlock = head.getNumber();

    if (currentBlock > lastAccountCacheUpdateBlock || accountRulesContractCache.get() == null) {
      synchronized (accountCacheLock) {
        if (currentBlock > lastAccountCacheUpdateBlock || accountRulesContractCache.get() == null) {
          Bytes payload =
              Bytes.concatenate(
                  PermissioningPluginFunctions.GET_CONTRACT_ADDRESS_SELECTOR,
                  PermissioningPluginFunctions.RULES_NAME_KEY_BYTES);
          Optional<Bytes> result = callContract(accountIngressAddress, payload);

          if (result.isPresent()) {
            Bytes bytes = result.get();
            if (bytes.size() >= 32) {
              Address resolved = Address.wrap(bytes.slice(12));
              accountRulesContractCache.set(resolved);
              lastAccountCacheUpdateBlock = currentBlock;
              LOG.debug("Resolved AccountRules contract address: {}", resolved);
            } else {
              LOG.error(
                  "Account Ingress returned malformed address data ({} bytes).", bytes.size());
            }
          } else {
            LOG.error("Simulation to Account Ingress failed — caching null address.");
            accountRulesContractCache.set(null);
          }
        }
      }
    }
    return accountRulesContractCache.get();
  }

  Address getNodeRulesAddress() {
    var head = blockchainService.getChainHeadHeader();
    long currentBlock = head.getNumber();

    if (currentBlock > lastNodeCacheUpdateBlock || nodeRulesContractCache.get() == null) {
      synchronized (nodeCacheLock) {
        if (currentBlock > lastNodeCacheUpdateBlock || nodeRulesContractCache.get() == null) {
          Bytes payload =
              Bytes.concatenate(
                  PermissioningPluginFunctions.GET_CONTRACT_ADDRESS_SELECTOR,
                  PermissioningPluginFunctions.RULES_NAME_KEY_BYTES);
          Optional<Bytes> result = callContract(nodeIngressAddress, payload);

          if (result.isPresent()) {
            Bytes bytes = result.get();
            if (bytes.size() >= 32) {
              Address resolved = Address.wrap(bytes.slice(12));
              nodeRulesContractCache.set(resolved);
              lastNodeCacheUpdateBlock = currentBlock;
              LOG.debug("Resolved NodeRules contract address: {}", resolved);
            } else {
              LOG.error("Node Ingress returned malformed address data ({} bytes).", bytes.size());
            }
          } else {
            LOG.error("Simulation to Node Ingress failed — caching null address.");
            nodeRulesContractCache.set(null);
          }
        }
      }
    }
    return nodeRulesContractCache.get();
  }

  // --- Package-private getters and setters for testing without reflection ---

  void setAccountIngressAddress(final Address address) {
    this.accountIngressAddress = address;
  }

  Address getAccountIngressAddress() {
    return accountIngressAddress;
  }

  void setNodeIngressAddress(final Address address) {
    this.nodeIngressAddress = address;
  }

  Address getNodeIngressAddress() {
    return nodeIngressAddress;
  }

  void setNodeContractVersion(final int version) {
    this.nodeContractVersion = version;
  }

  int getNodeContractVersion() {
    return nodeContractVersion;
  }

  void setSimulationService(final TransactionSimulationService service) {
    this.simulationService = service;
  }

  void setBlockchainService(final BlockchainService service) {
    this.blockchainService = service;
  }

  Counter getTxCheckCounter() {
    return txCheckCounter;
  }

  Counter getTxPermittedCounter() {
    return txPermittedCounter;
  }

  Counter getTxDeniedCounter() {
    return txDeniedCounter;
  }

  Counter getNodeCheckCounter() {
    return nodeCheckCounter;
  }

  Counter getNodePermittedCounter() {
    return nodePermittedCounter;
  }

  Counter getNodeDeniedCounter() {
    return nodeDeniedCounter;
  }
}
