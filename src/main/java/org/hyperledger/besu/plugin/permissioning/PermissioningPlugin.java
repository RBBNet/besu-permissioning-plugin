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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
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

  static final String ENV_ACCOUNTS_ADDRESS = "BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS";
  static final String ENV_NODES_ADDRESS = "BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS";
  static final String ENV_NODES_VERSION = "BESU_PERMISSIONS_NODES_CONTRACT_VERSION";
  static final String ENV_GAS_LIMIT = "BESU_PERMISSIONS_SIMULATION_GAS_LIMIT";

  /** Contract addresses must be given in full: Besu's Address parser left-pads short values. */
  private static final Pattern FULL_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

  /** Source of configuration values (the process environment in production). */
  private final Function<String, String> env;

  /** Creates the plugin reading its configuration from the process environment. */
  public PermissioningPlugin() {
    this(System::getenv);
  }

  /** Creates the plugin with an explicit configuration source (used by tests). */
  PermissioningPlugin(final Function<String, String> env) {
    this.env = env;
  }

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

    this.simulationService =
        context
            .getService(TransactionSimulationService.class)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "TransactionSimulationService is required but not available from Besu service context."));
    this.blockchainService =
        context
            .getService(BlockchainService.class)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "BlockchainService is required but not available from Besu service context."));

    configureFromEnvironment();

    PermissioningService permissioningService =
        context
            .getService(PermissioningService.class)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "PermissioningService is required but not available from Besu service context."));
    permissioningService.registerTransactionPermissioningProvider(
        new OnChainTransactionPermissioningProvider(this));
    permissioningService.registerNodePermissioningProvider(
        new OnChainNodePermissioningProvider(this));
  }

  /**
   * Reads and validates the configuration. Any missing mandatory setting or invalid value makes the
   * plugin throw, so that Besu aborts startup (default unless {@code --plugin-continue-on-error} is
   * enabled) instead of running in a degraded, fail-closed state that rejects even valid blocks.
   */
  void configureFromEnvironment() {
    List<String> errors = new ArrayList<>();

    String accountIngressEnv = env.apply(ENV_ACCOUNTS_ADDRESS);
    if (accountIngressEnv == null || accountIngressEnv.isBlank()) {
      errors.add(ENV_ACCOUNTS_ADDRESS + " is mandatory and is not set");
    } else {
      if (FULL_ADDRESS.matcher(accountIngressEnv.trim()).matches()) {
        this.accountIngressAddress = Address.fromHexString(accountIngressEnv.trim());
        LOG.info("Account Permissioning Ingress Address set to: {}", accountIngressAddress);
      } else {
        errors.add(
            ENV_ACCOUNTS_ADDRESS + "=" + accountIngressEnv + " is not a full 0x-prefixed address");
      }
    }

    String nodeContractVersionEnv = env.apply(ENV_NODES_VERSION);
    if (nodeContractVersionEnv != null && !nodeContractVersionEnv.isBlank()) {
      try {
        int parsedVersion = Integer.parseInt(nodeContractVersionEnv.trim());
        if (parsedVersion == 1 || parsedVersion == 2) {
          this.nodeContractVersion = parsedVersion;
        } else {
          errors.add(ENV_NODES_VERSION + "=" + parsedVersion + " is invalid (expected 1 or 2)");
        }
      } catch (NumberFormatException e) {
        errors.add(ENV_NODES_VERSION + "=" + nodeContractVersionEnv + " is not a number");
      }
    }
    LOG.info("Node contract interface version set to {}", nodeContractVersion);

    String nodeIngressEnv = env.apply(ENV_NODES_ADDRESS);
    if (nodeIngressEnv != null && !nodeIngressEnv.isBlank()) {
      if (FULL_ADDRESS.matcher(nodeIngressEnv.trim()).matches()) {
        this.nodeIngressAddress = Address.fromHexString(nodeIngressEnv.trim());
        LOG.info("Node Permissioning Ingress Address set to: {}", nodeIngressAddress);
      } else {
        errors.add(ENV_NODES_ADDRESS + "=" + nodeIngressEnv + " is not a full 0x-prefixed address");
      }
    } else {
      LOG.warn(
          "{} is not set: node permissioning operates in explicit fail-close mode "
              + "(only Besu's bootnode exception applies).",
          ENV_NODES_ADDRESS);
      this.nodeIngressAddress = null;
    }

    String gasLimitEnv = env.apply(ENV_GAS_LIMIT);
    if (gasLimitEnv != null && !gasLimitEnv.isBlank()) {
      try {
        long parsed = Long.parseLong(gasLimitEnv.trim());
        if (parsed <= 0) {
          errors.add(ENV_GAS_LIMIT + "=" + parsed + " must be a positive number");
        } else {
          this.simulationGasLimit = parsed;
          LOG.info("Simulation gas limit set to: {}", simulationGasLimit);
        }
      } catch (NumberFormatException e) {
        errors.add(ENV_GAS_LIMIT + "=" + gasLimitEnv + " is not a number");
      }
    }

    if (!errors.isEmpty()) {
      String message =
          "On-Chain Permissioning Plugin: invalid configuration — " + String.join("; ", errors);
      LOG.error(message);
      throw new IllegalStateException(message);
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
              accountRulesContractCache.set(Address.ZERO);
              lastAccountCacheUpdateBlock = currentBlock;
            }
          } else {
            LOG.error("Simulation to Account Ingress failed — caching sentinel zero address.");
            accountRulesContractCache.set(Address.ZERO);
            lastAccountCacheUpdateBlock = currentBlock;
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
              nodeRulesContractCache.set(Address.ZERO);
              lastNodeCacheUpdateBlock = currentBlock;
            }
          } else {
            LOG.error("Simulation to Node Ingress failed — caching sentinel zero address.");
            nodeRulesContractCache.set(Address.ZERO);
            lastNodeCacheUpdateBlock = currentBlock;
          }
        }
      }
    }
    return nodeRulesContractCache.get();
  }

  // --- Package-private test harness mutators (VisibleForTesting) ---

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
