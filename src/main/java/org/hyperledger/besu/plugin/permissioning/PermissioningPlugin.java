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

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.spec.ECPoint;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.EnodeURL;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.PermissioningService;
import org.hyperledger.besu.plugin.services.SecurityModuleService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.sync.SynchronizationService;
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

  /** The zero address never identifies an Ingress contract. */
  private static final Pattern ZERO_ADDRESS = Pattern.compile("^0x0{40}$");

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

  private int nodeContractVersion = 1;
  private long simulationGasLimit = DEFAULT_SIMULATION_GAS_LIMIT;
  private static final String DEFAULT_SECURITY_MODULE = "localfile";

  private static final Pattern ENODE_ID_PATTERN = Pattern.compile("enode://([0-9a-fA-F]{128})");

  private volatile SynchronizationService synchronizationService;
  private volatile Set<Bytes> fixedNodeIds = Set.of();
  private final AtomicBoolean reachedSync = new AtomicBoolean(false);

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
      if (ZERO_ADDRESS.matcher(accountIngressEnv.trim()).matches()) {
        errors.add(ENV_ACCOUNTS_ADDRESS + " must not be the zero address");
      } else if (FULL_ADDRESS.matcher(accountIngressEnv.trim()).matches()) {
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
      if (ZERO_ADDRESS.matcher(nodeIngressEnv.trim()).matches()) {
        errors.add(ENV_NODES_ADDRESS + " must not be the zero address");
      } else if (FULL_ADDRESS.matcher(nodeIngressEnv.trim()).matches()) {
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

  /**
   * Registers the plugin counters. Besu prefixes them with the category ("besu_permissioning_") and
   * Prometheus appends "_total" to counters, so they are exposed as e.g. {@code
   * besu_permissioning_transactions_checked_total}. Keep these names stable: operators build
   * dashboards and alerts on them.
   */
  void setupMetrics(final MetricsSystem metricsSystem) {
    txCheckCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "transactions_checked",
            "Number of transactions checked by the on-chain permissioning plugin");
    txPermittedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "transactions_permitted",
            "Number of transactions permitted by the on-chain permissioning plugin");
    txDeniedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "transactions_denied",
            "Number of transactions denied by the on-chain permissioning plugin");

    nodeCheckCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "nodes_checked",
            "Number of node connections checked");
    nodePermittedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "nodes_permitted",
            "Number of node connections permitted");
    nodeDeniedCounter =
        metricsSystem.createCounter(
            PermissioningMetricCategory.PERMISSIONING,
            "nodes_denied",
            "Number of node connections denied");
    LOG.info("PermissioningPlugin metrics counters registered successfully.");
  }

  @Override
  public void start() {
    LOG.info("On-Chain Permissioning Plugin started.");

    if (serviceManager != null) {
      serviceManager.getService(MetricsSystem.class).ifPresent(this::setupMetrics);
      this.synchronizationService =
          serviceManager.getService(SynchronizationService.class).orElse(null);
      if (synchronizationService == null) {
        LOG.warn("SynchronizationService unavailable. Pre-sync node permissioning disabled.");
      }
      loadFixedNodes();
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

  /**
   * Permits only an ABI-encoded {@code bool true} (exactly 32 bytes, value 1). ABI {@code false},
   * failed calls and malformed outputs are denied (fail-close, PERM-240).
   */
  boolean simulateAndGetBool(final Address target, final Bytes payload) {
    Optional<Bytes> output = callContract(target, payload);
    if (output.isEmpty()) {
      LOG.error("PermissioningPlugin: call to {} failed. Denying.", target);
      return false;
    }
    if (output.get().equals(PermissioningPluginFunctions.V2_TRUE_RESPONSE)) {
      return true;
    }
    if (!output.get().equals(PermissioningPluginFunctions.V2_FALSE_RESPONSE)) {
      LOG.error(
          "PermissioningPlugin: Unexpected output from {}: {}. Denying.", target, output.get());
    }
    return false;
  }

  void loadFixedNodes() {
    Set<Bytes> ids = new HashSet<>();
    addEnodeIds(System.getenv("BESU_BOOTNODES"), ids);
    addEnodeIds(System.getenv("BESU_PERMISSIONS_FIXED_NODES"), ids);
    serviceManager
        .getService(BesuConfiguration.class)
        .ifPresent(
            config -> {
              Path staticNodes = config.getDataPath().resolve("static-nodes.json");
              if (Files.exists(staticNodes)) {
                try {
                  addEnodeIds(Files.readString(staticNodes), ids);
                } catch (IOException e) {
                  LOG.error("Could not read {}: {}", staticNodes, e.getMessage());
                }
              }
            });
    setFixedNodes(ids, resolveLocalNodeId());
  }

  /**
   * Sets the nodes permitted before the node first reaches sync. The local node is always removed:
   * for inbound connections Besu passes the local node as the destination, so keeping it would
   * permit any inbound peer. If the local node ID is unknown, the pre-sync path is disabled and the
   * contract decides, as before.
   */
  void setFixedNodes(final Set<Bytes> ids, final Bytes localNodeId) {
    if (localNodeId == null) {
      if (!ids.isEmpty()) {
        LOG.warn("Could not determine the local node ID. Pre-sync node permissioning disabled.");
      }
      this.fixedNodeIds = Set.of();
      return;
    }
    Set<Bytes> withoutLocal = new HashSet<>(ids);
    if (withoutLocal.remove(localNodeId)) {
      LOG.info("Ignoring the local node's own enode in the fixed nodes list.");
    }
    this.fixedNodeIds = Set.copyOf(withoutLocal);
    LOG.info("Loaded {} fixed node(s) for pre-sync node permissioning.", fixedNodeIds.size());
  }

  private Bytes resolveLocalNodeId() {
    String configuredModule = System.getenv("BESU_SECURITY_MODULE");
    String moduleName =
        configuredModule == null || configuredModule.isEmpty()
            ? DEFAULT_SECURITY_MODULE
            : configuredModule;
    try {
      return serviceManager
          .getService(SecurityModuleService.class)
          .flatMap(service -> service.getByName(moduleName))
          .map(module -> toNodeId(module.get().getPublicKey().getW()))
          .orElse(null);
    } catch (Exception e) {
      LOG.warn("Could not read the local node public key: {}", e.getMessage());
      return null;
    }
  }

  static Bytes toNodeId(final ECPoint publicKey) {
    return Bytes.concatenate(
        Bytes.wrap(UInt256.valueOf(publicKey.getAffineX()).toArrayUnsafe()),
        Bytes.wrap(UInt256.valueOf(publicKey.getAffineY()).toArrayUnsafe()));
  }

  static void addEnodeIds(final String text, final Set<Bytes> ids) {
    if (text == null) {
      return;
    }
    Matcher matcher = ENODE_ID_PATTERN.matcher(text);
    while (matcher.find()) {
      ids.add(Bytes.fromHexString(matcher.group(1)));
    }
  }

  boolean hasFixedNodes() {
    return !fixedNodeIds.isEmpty();
  }

  boolean isFixedNode(final EnodeURL enode) {
    return fixedNodeIds.contains(enode.getNodeId());
  }

  /** One-way latch: once the node has reached sync, it never goes back to pre-sync mode. */
  boolean hasReachedSync() {
    if (reachedSync.get()) {
      return true;
    }
    SynchronizationService sync = synchronizationService;
    if (sync == null) {
      return true;
    }
    if (sync.getBestPeerChainHead().isPresent()
        && sync.isInSync()
        && reachedSync.compareAndSet(false, true)) {
      LOG.info("Node reached sync. Node permissioning is now decided by the NodeRules contract.");
    }
    return reachedSync.get();
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

  void setSynchronizationService(final SynchronizationService service) {
    this.synchronizationService = service;
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
