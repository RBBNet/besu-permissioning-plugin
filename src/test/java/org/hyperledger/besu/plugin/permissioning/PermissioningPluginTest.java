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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.security.spec.ECPoint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.EnodeURL;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSimulationResult;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.PermissioningService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.sync.SynchronizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PermissioningPluginTest {

  private static final Address VALID_ADDRESS =
      Address.fromHexString("0x1234567890123456789012345678901234567890");

  private ServiceManager serviceManager;
  private TransactionSimulationService simulationService;
  private BlockchainService blockchainService;
  private PermissioningService permissioningService;
  private BlockHeader chainHead;

  @BeforeEach
  void setUpMocks() {
    serviceManager = mock(ServiceManager.class);
    simulationService = mock(TransactionSimulationService.class);
    blockchainService = mock(BlockchainService.class);
    permissioningService = mock(PermissioningService.class);
    chainHead = mock(BlockHeader.class);

    when(serviceManager.getService(TransactionSimulationService.class))
        .thenReturn(Optional.of(simulationService));
    when(serviceManager.getService(BlockchainService.class))
        .thenReturn(Optional.of(blockchainService));
    when(blockchainService.getChainHeadHeader()).thenReturn(chainHead);
    when(chainHead.getNumber()).thenReturn(100L);
  }

  private static final Map<String, String> VALID_CONFIG =
      Map.of(
          PermissioningPlugin.ENV_ACCOUNTS_ADDRESS, "0x0000000000000000000000000000000000008888",
          PermissioningPlugin.ENV_NODES_ADDRESS, "0x0000000000000000000000000000000000009999");

  private static Map<String, String> configWith(final String key, final String value) {
    Map<String, String> config = new HashMap<>(VALID_CONFIG);
    if (value == null) {
      config.remove(key);
    } else {
      config.put(key, value);
    }
    return config;
  }

  private IllegalStateException registerFails(final Map<String, String> config) {
    when(serviceManager.getService(PermissioningService.class))
        .thenReturn(Optional.of(permissioningService));
    PermissioningPlugin plugin = new PermissioningPlugin(config::get);
    return assertThrows(IllegalStateException.class, () -> plugin.register(serviceManager));
  }

  @Test
  void registerFailsWhenTheAccountIngressAddressIsMissing() {
    IllegalStateException e =
        registerFails(configWith(PermissioningPlugin.ENV_ACCOUNTS_ADDRESS, null));
    assertTrue(e.getMessage().contains(PermissioningPlugin.ENV_ACCOUNTS_ADDRESS));
    verify(permissioningService, never()).registerTransactionPermissioningProvider(any());
  }

  @Test
  void registerFailsWhenTheAccountIngressAddressIsBlank() {
    registerFails(configWith(PermissioningPlugin.ENV_ACCOUNTS_ADDRESS, ""));
  }

  @Test
  void registerFailsWhenTheAccountIngressAddressIsInvalid() {
    registerFails(configWith(PermissioningPlugin.ENV_ACCOUNTS_ADDRESS, "0xnot-an-address"));
  }

  @Test
  void registerFailsWhenTheAccountIngressAddressIsTheZeroAddress() {
    IllegalStateException e =
        registerFails(
            configWith(
                PermissioningPlugin.ENV_ACCOUNTS_ADDRESS,
                "0x0000000000000000000000000000000000000000"));
    assertTrue(e.getMessage().contains("zero address"));
  }

  @Test
  void registerFailsWhenTheNodeIngressAddressIsTheZeroAddress() {
    IllegalStateException e =
        registerFails(
            configWith(
                PermissioningPlugin.ENV_NODES_ADDRESS,
                "0x0000000000000000000000000000000000000000"));
    assertTrue(e.getMessage().contains("zero address"));
  }

  @Test
  void registerFailsWhenTheNodeIngressAddressIsInvalid() {
    registerFails(configWith(PermissioningPlugin.ENV_NODES_ADDRESS, "9999"));
  }

  @Test
  void registerFailsWhenTheNodeContractVersionIsInvalid() {
    registerFails(configWith(PermissioningPlugin.ENV_NODES_VERSION, "3"));
    registerFails(configWith(PermissioningPlugin.ENV_NODES_VERSION, "v1"));
  }

  @Test
  void registerFailsWhenTheSimulationGasLimitIsInvalid() {
    registerFails(configWith(PermissioningPlugin.ENV_GAS_LIMIT, "0"));
    registerFails(configWith(PermissioningPlugin.ENV_GAS_LIMIT, "lots"));
  }

  @Test
  void registerReportsAllConfigurationErrorsAtOnce() {
    Map<String, String> config = configWith(PermissioningPlugin.ENV_ACCOUNTS_ADDRESS, null);
    config.put(PermissioningPlugin.ENV_NODES_VERSION, "7");
    IllegalStateException e = registerFails(config);
    assertTrue(e.getMessage().contains(PermissioningPlugin.ENV_ACCOUNTS_ADDRESS));
    assertTrue(e.getMessage().contains(PermissioningPlugin.ENV_NODES_VERSION));
  }

  @Test
  void registerFailsWhenThePermissioningServiceIsUnavailable() {
    when(serviceManager.getService(PermissioningService.class)).thenReturn(Optional.empty());
    PermissioningPlugin plugin = new PermissioningPlugin(VALID_CONFIG::get);
    assertThrows(IllegalStateException.class, () -> plugin.register(serviceManager));
  }

  @Test
  void registerAcceptsAMissingNodeIngressAddress() {
    when(serviceManager.getService(PermissioningService.class))
        .thenReturn(Optional.of(permissioningService));
    PermissioningPlugin plugin =
        new PermissioningPlugin(configWith(PermissioningPlugin.ENV_NODES_ADDRESS, null)::get);
    assertDoesNotThrow(() -> plugin.register(serviceManager));
  }

  @Test
  void registerWithValidServices() {
    when(serviceManager.getService(PermissioningService.class))
        .thenReturn(Optional.of(permissioningService));

    PermissioningPlugin plugin = new PermissioningPlugin(VALID_CONFIG::get);
    assertDoesNotThrow(() -> plugin.register(serviceManager));

    verify(permissioningService).registerTransactionPermissioningProvider(any());
    verify(permissioningService).registerNodePermissioningProvider(any());
  }

  @Test
  void startAndStopDoNotThrow() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    assertDoesNotThrow(plugin::start);
    assertDoesNotThrow(plugin::stop);
  }

  @Test
  void isPermittedReturnsFalseWhenAccountIngressNull() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setAccountIngressAddress(null);

    OnChainTransactionPermissioningProvider txProvider =
        new OnChainTransactionPermissioningProvider(plugin);
    Transaction tx = mock(Transaction.class);

    boolean result = txProvider.isPermitted(tx);
    assertFalse(result, "Must deny when Account Ingress is unconfigured (fail-close)");
  }

  @Test
  void isPermittedReturnsFalseWhenIngressCallFails() {
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setAccountIngressAddress(VALID_ADDRESS);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    OnChainTransactionPermissioningProvider txProvider =
        new OnChainTransactionPermissioningProvider(plugin);
    Transaction tx = mock(Transaction.class);
    when(tx.getSender()).thenReturn(VALID_ADDRESS);

    boolean result = txProvider.isPermitted(tx);
    assertFalse(result, "Must deny when the Account Ingress call fails (fail-close)");
  }

  @Test
  void isConnectionPermittedReturnsFalseWhenNodeIngressNull() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setNodeIngressAddress(null);

    OnChainNodePermissioningProvider nodeProvider = new OnChainNodePermissioningProvider(plugin);
    EnodeURL source = mock(EnodeURL.class);
    EnodeURL dest = mock(EnodeURL.class);

    boolean result = nodeProvider.isConnectionPermitted(source, dest);
    assertFalse(result, "Must deny when Node Ingress is unconfigured (fail-close)");
  }

  @Test
  void isConnectionPermittedHandlesV1TripwireResponse() throws UnknownHostException {
    TransactionProcessingResult connProcResult = mock(TransactionProcessingResult.class);
    when(connProcResult.isSuccessful()).thenReturn(true);
    when(connProcResult.getOutput()).thenReturn(PermissioningPluginFunctions.TRUE_RESPONSE);
    TransactionSimulationResult connSimResult =
        new TransactionSimulationResult(mock(Transaction.class), connProcResult);

    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(connSimResult));

    Bytes nodeId = Bytes.concatenate(Bytes.random(32), Bytes.random(32));
    InetAddress mockAddr = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});

    EnodeURL source = mock(EnodeURL.class);
    when(source.getNodeId()).thenReturn(nodeId);
    when(source.getIp()).thenReturn(mockAddr);
    when(source.getListeningPortOrZero()).thenReturn(30303);

    EnodeURL dest = mock(EnodeURL.class);
    when(dest.getNodeId()).thenReturn(nodeId);
    when(dest.getIp()).thenReturn(mockAddr);
    when(dest.getListeningPortOrZero()).thenReturn(30304);

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setNodeIngressAddress(VALID_ADDRESS);
    plugin.setNodeContractVersion(1);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    OnChainNodePermissioningProvider nodeProvider = new OnChainNodePermissioningProvider(plugin);
    boolean result = nodeProvider.isConnectionPermitted(source, dest);

    assertTrue(result, "V1 tripwire TRUE_RESPONSE must permit connection");
  }

  @Test
  void isConnectionPermittedV2UsesPerEnodeCalls() throws UnknownHostException {
    TransactionProcessingResult srcProcResult = mock(TransactionProcessingResult.class);
    when(srcProcResult.isSuccessful()).thenReturn(true);
    when(srcProcResult.getOutput()).thenReturn(PermissioningPluginFunctions.V2_TRUE_RESPONSE);
    TransactionSimulationResult srcSimResult =
        new TransactionSimulationResult(mock(Transaction.class), srcProcResult);

    TransactionProcessingResult dstProcResult = mock(TransactionProcessingResult.class);
    when(dstProcResult.isSuccessful()).thenReturn(true);
    when(dstProcResult.getOutput()).thenReturn(PermissioningPluginFunctions.V2_TRUE_RESPONSE);
    TransactionSimulationResult dstSimResult =
        new TransactionSimulationResult(mock(Transaction.class), dstProcResult);

    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(srcSimResult))
        .thenReturn(Optional.of(dstSimResult));

    Bytes nodeId =
        Bytes.fromHexString(
            "0x6f8a80d14311c39f35f516fa664deaaaa13e85b2f7493f37f6144d86991ec012937307647bd3b9a82abe2974e1407241d54947bbb39763a4cac9f77166ad92a0");
    InetAddress mockAddr = InetAddress.getByAddress(new byte[] {10, 3, 58, 6});

    EnodeURL source = mock(EnodeURL.class);
    when(source.getNodeId()).thenReturn(nodeId);
    when(source.getIp()).thenReturn(mockAddr);
    when(source.getListeningPortOrZero()).thenReturn(30303);

    EnodeURL dest = mock(EnodeURL.class);
    when(dest.getNodeId()).thenReturn(nodeId);
    when(dest.getIp()).thenReturn(mockAddr);
    when(dest.getListeningPortOrZero()).thenReturn(30304);

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setNodeIngressAddress(VALID_ADDRESS);
    plugin.setNodeContractVersion(2);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    OnChainNodePermissioningProvider nodeProvider = new OnChainNodePermissioningProvider(plugin);
    boolean result = nodeProvider.isConnectionPermitted(source, dest);

    assertTrue(result, "V2: both nodes permitted must return true");
  }

  private TransactionSimulationResult simulationReturning(final Bytes output) {
    TransactionProcessingResult procResult = mock(TransactionProcessingResult.class);
    when(procResult.isSuccessful()).thenReturn(true);
    when(procResult.getOutput()).thenReturn(output);
    return new TransactionSimulationResult(mock(Transaction.class), procResult);
  }

  private static EnodeURL enode(final int port) throws UnknownHostException {
    EnodeURL enode = mock(EnodeURL.class);
    when(enode.getNodeId()).thenReturn(Bytes.concatenate(Bytes.random(32), Bytes.random(32)));
    when(enode.getIp()).thenReturn(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    when(enode.getListeningPortOrZero()).thenReturn(port);
    return enode;
  }

  @Test
  void transactionIsCheckedWithASingleCallToTheAccountIngress() {
    Address accountIngress = Address.fromHexString("0x0000000000000000000000000000000000008888");
    TransactionSimulationResult resposta =
        simulationReturning(PermissioningPluginFunctions.V2_TRUE_RESPONSE);
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(resposta));

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setAccountIngressAddress(accountIngress);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    Transaction tx = mock(Transaction.class);
    when(tx.getSender()).thenReturn(VALID_ADDRESS);
    doReturn(Optional.empty()).when(tx).getTo();
    when(tx.getPayload()).thenReturn(Bytes.EMPTY);

    assertTrue(new OnChainTransactionPermissioningProvider(plugin).isPermitted(tx));

    ArgumentCaptor<CallParameter> call = ArgumentCaptor.forClass(CallParameter.class);
    verify(simulationService, times(1)).simulate(call.capture(), any(), any(), any(), any());
    assertEquals(Optional.of(accountIngress), call.getValue().getTo());
    assertEquals(
        PermissioningPluginFunctions.TRANSACTION_ALLOWED_SELECTOR,
        call.getValue().getPayload().orElseThrow().slice(0, 4));
  }

  @Test
  void transactionIsDeniedWhenTheAccountIngressReturnsFalse() {
    TransactionSimulationResult resposta =
        simulationReturning(PermissioningPluginFunctions.V2_FALSE_RESPONSE);
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(resposta));

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setAccountIngressAddress(VALID_ADDRESS);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    Transaction tx = mock(Transaction.class);
    when(tx.getSender()).thenReturn(VALID_ADDRESS);
    doReturn(Optional.empty()).when(tx).getTo();
    when(tx.getPayload()).thenReturn(Bytes.EMPTY);

    assertFalse(new OnChainTransactionPermissioningProvider(plugin).isPermitted(tx));
  }

  /**
   * Regression test for a node syncing from genesis: before the permissioning contracts are
   * deployed, the Ingress has no Rules contract set and (in networks such as RBB) answers
   * "allowed". The plugin must follow the Ingress answer instead of resolving the Rules address and
   * rejecting because it is zero.
   */
  @Test
  void connectionFollowsTheNodeIngressAnswerWhenNoRulesContractIsSet() throws UnknownHostException {
    Address nodeIngress = Address.fromHexString("0x0000000000000000000000000000000000009999");
    TransactionSimulationResult resposta =
        simulationReturning(PermissioningPluginFunctions.TRUE_RESPONSE);
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(resposta));

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setNodeIngressAddress(nodeIngress);
    plugin.setNodeContractVersion(1);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    assertTrue(
        new OnChainNodePermissioningProvider(plugin)
            .isConnectionPermitted(enode(30303), enode(30304)));

    ArgumentCaptor<CallParameter> call = ArgumentCaptor.forClass(CallParameter.class);
    verify(simulationService, times(1)).simulate(call.capture(), any(), any(), any(), any());
    assertEquals(Optional.of(nodeIngress), call.getValue().getTo());
    assertEquals(
        PermissioningPluginFunctions.CONNECTION_ALLOWED_SELECTOR,
        call.getValue().getPayload().orElseThrow().slice(0, 4));
  }

  @Test
  void connectionIsDeniedWhenTheNodeIngressReturnsTheDeniedTripwire() throws UnknownHostException {
    TransactionSimulationResult resposta =
        simulationReturning(PermissioningPluginFunctions.FALSE_RESPONSE);
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(resposta));

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setNodeIngressAddress(VALID_ADDRESS);
    plugin.setNodeContractVersion(1);
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    assertFalse(
        new OnChainNodePermissioningProvider(plugin)
            .isConnectionPermitted(enode(30303), enode(30304)));
  }  

  @Test
  void metricCategoryProducesTheBesuPermissioningPrefix() {
    PermissioningMetricCategory category = PermissioningMetricCategory.PERMISSIONING;
    assertEquals(
        "besu_permissioning",
        category.getApplicationPrefix().orElse("") + category.getName(),
        "Exposed metric names must start with besu_permissioning_");
  }

  @Test
  void countersAreRegisteredWithTheDocumentedNames() {
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    when(metricsSystem.createCounter(any(), any(), any())).thenReturn(mock(Counter.class));

    new PermissioningPlugin().setupMetrics(metricsSystem);

    for (String name :
        List.of(
            "transactions_checked",
            "transactions_permitted",
            "transactions_denied",
            "nodes_checked",
            "nodes_permitted",
            "nodes_denied")) {
      verify(metricsSystem)
          .createCounter(eq(PermissioningMetricCategory.PERMISSIONING), eq(name), any());
    }
  }
  // --- transactionAllowed output validation (#9) ---

  private PermissioningPlugin pluginWithSimulatedOutput(final Bytes output) {
    TransactionProcessingResult processingResult = mock(TransactionProcessingResult.class);
    when(processingResult.isSuccessful()).thenReturn(true);
    when(processingResult.getOutput()).thenReturn(output);
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new TransactionSimulationResult(mock(Transaction.class), processingResult)));

    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);
    return plugin;
  }

  // --- Pre-sync node permissioning (#8) ---

  private static final Bytes LOCAL_ID = Bytes.fromHexString("0x" + "11".repeat(64));
  private static final Bytes FIXED_ID = Bytes.fromHexString("0x" + "22".repeat(64));
  private static final Bytes OTHER_ID = Bytes.fromHexString("0x" + "33".repeat(64));

  private Counter nodePermittedCounter;
  private Counter nodeDeniedCounter;

  private static EnodeURL enodeWithId(final Bytes nodeId) {
    EnodeURL enode = mock(EnodeURL.class);
    when(enode.getNodeId()).thenReturn(nodeId);
    when(enode.getIp()).thenReturn(InetAddress.getLoopbackAddress());
    when(enode.getListeningPortOrZero()).thenReturn(30303);
    return enode;
  }

  private static SynchronizationService syncService(final boolean hasPeer, final boolean inSync) {
    Optional<Long> bestPeerHead = hasPeer ? Optional.of(100L) : Optional.empty();
    SynchronizationService sync = mock(SynchronizationService.class);
    when(sync.getBestPeerChainHead()).thenReturn(bestPeerHead);
    when(sync.isInSync()).thenReturn(inSync);
    return sync;
  }

  /** Started plugin with node counters, not yet in sync, with FIXED_ID as the only fixed node. */
  private PermissioningPlugin preSyncPlugin() {
    nodePermittedCounter = mock(Counter.class);
    nodeDeniedCounter = mock(Counter.class);
    MetricsSystem metricsSystem = mock(MetricsSystem.class);
    when(metricsSystem.createCounter(any(), contains("permitted"), any()))
        .thenReturn(nodePermittedCounter);
    when(metricsSystem.createCounter(any(), contains("denied"), any()))
        .thenReturn(nodeDeniedCounter);
    when(serviceManager.getService(MetricsSystem.class)).thenReturn(Optional.of(metricsSystem));
    when(serviceManager.getService(PermissioningService.class))
        .thenReturn(Optional.of(permissioningService));

    PermissioningPlugin plugin = new PermissioningPlugin(VALID_CONFIG::get);
    plugin.register(serviceManager);
    plugin.start();
    plugin.setNodeIngressAddress(VALID_ADDRESS);
    plugin.setSynchronizationService(syncService(false, false));
    plugin.setFixedNodes(Set.of(FIXED_ID), LOCAL_ID);
    return plugin;
  }

  @Test
  void simulateAndGetBoolPermitsAbiTrue() {
    PermissioningPlugin plugin =
        pluginWithSimulatedOutput(PermissioningPluginFunctions.V2_TRUE_RESPONSE);
    assertTrue(plugin.simulateAndGetBool(VALID_ADDRESS, Bytes.EMPTY));
  }

  @Test
  void simulateAndGetBoolDeniesAbiFalse() {
    PermissioningPlugin plugin =
        pluginWithSimulatedOutput(PermissioningPluginFunctions.V2_FALSE_RESPONSE);
    assertFalse(plugin.simulateAndGetBool(VALID_ADDRESS, Bytes.EMPTY));
  }

  @Test
  void simulateAndGetBoolDeniesFailedCall() {
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setSimulationService(simulationService);
    plugin.setBlockchainService(blockchainService);

    assertFalse(plugin.simulateAndGetBool(VALID_ADDRESS, Bytes.EMPTY));
  }

  @Test
  void simulateAndGetBoolDeniesMalformedOutput() {
    Bytes abiTrue = PermissioningPluginFunctions.V2_TRUE_RESPONSE;
    List<Bytes> malformedOutputs =
        List.of(
            Bytes.EMPTY,
            Bytes.of(1),
            Bytes.of(2),
            Bytes32.leftPad(Bytes.of(2)),
            PermissioningPluginFunctions.TRUE_RESPONSE,
            Bytes.concatenate(abiTrue, abiTrue));

    for (Bytes output : malformedOutputs) {
      assertFalse(
          pluginWithSimulatedOutput(output).simulateAndGetBool(VALID_ADDRESS, Bytes.EMPTY),
          "Malformed output must be denied: " + output);
    }
  }

  @Test
  void addEnodeIdsIgnoresNullAndEmptyText() {
    Set<Bytes> ids = new HashSet<>();
    PermissioningPlugin.addEnodeIds(null, ids);
    PermissioningPlugin.addEnodeIds("", ids);
    assertTrue(ids.isEmpty());
  }

  @Test
  void addEnodeIdsParsesSeveralSeparatorsAndMixedCaseHex() {
    String text =
        "enode://"
            + "AB".repeat(64)
            + "@10.0.0.1:30303,enode://"
            + "cd".repeat(64)
            + "@host:30303 [\"enode://"
            + "eF".repeat(64)
            + "@node:30303\"]\n"
            + "enode://1234@too-short:30303";
    Set<Bytes> ids = new HashSet<>();
    PermissioningPlugin.addEnodeIds(text, ids);

    assertEquals(
        Set.of(
            Bytes.fromHexString("0x" + "ab".repeat(64)),
            Bytes.fromHexString("0x" + "cd".repeat(64)),
            Bytes.fromHexString("0x" + "ef".repeat(64))),
        ids);
  }

  @Test
  void toNodeIdConcatenatesLeftPaddedCoordinates() {
    Bytes nodeId = PermissioningPlugin.toNodeId(new ECPoint(BigInteger.ONE, BigInteger.TWO));
    assertEquals(
        Bytes.concatenate(Bytes32.leftPad(Bytes.of(1)), Bytes32.leftPad(Bytes.of(2))), nodeId);
  }

  @Test
  void setFixedNodesRemovesTheLocalNode() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setFixedNodes(Set.of(FIXED_ID, LOCAL_ID), LOCAL_ID);
    assertTrue(plugin.isFixedNode(enodeWithId(FIXED_ID)));
    assertFalse(plugin.isFixedNode(enodeWithId(LOCAL_ID)));
  }

  @Test
  void setFixedNodesDisablesPreSyncPathWhenLocalNodeIdIsUnknown() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setFixedNodes(Set.of(FIXED_ID), null);
    assertFalse(plugin.hasFixedNodes());
  }

  @Test
  void hasReachedSyncWithoutSynchronizationServiceKeepsContractBehaviour() {
    assertTrue(new PermissioningPlugin().hasReachedSync());
  }

  @Test
  void hasReachedSyncIsFalseWithoutBestPeerHead() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setSynchronizationService(syncService(false, true));
    assertFalse(plugin.hasReachedSync(), "Besu may report in-sync with no peers at startup");
  }

  @Test
  void hasReachedSyncIsFalseWhileSyncing() {
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setSynchronizationService(syncService(true, false));
    assertFalse(plugin.hasReachedSync());
  }

  @Test
  void hasReachedSyncLatchesAndNeverReopens() {
    SynchronizationService sync = syncService(true, true);
    PermissioningPlugin plugin = new PermissioningPlugin();
    plugin.setSynchronizationService(sync);
    assertTrue(plugin.hasReachedSync());

    when(sync.isInSync()).thenReturn(false);
    when(sync.getBestPeerChainHead()).thenReturn(Optional.empty());
    assertTrue(plugin.hasReachedSync(), "The latch must never reopen");
  }

  @Test
  void preSyncPermitsOutboundToFixedNodeWithoutCallingContract() {
    OnChainNodePermissioningProvider provider =
        new OnChainNodePermissioningProvider(preSyncPlugin());

    assertTrue(provider.isConnectionPermitted(enodeWithId(LOCAL_ID), enodeWithId(FIXED_ID)));
    verify(nodePermittedCounter).inc();
    verify(nodeDeniedCounter, never()).inc();
    verify(simulationService, never())
        .simulate(any(CallParameter.class), any(), any(), any(), any());
  }

  @Test
  void preSyncDeniesOutboundToNonFixedNode() {
    OnChainNodePermissioningProvider provider =
        new OnChainNodePermissioningProvider(preSyncPlugin());

    assertFalse(provider.isConnectionPermitted(enodeWithId(LOCAL_ID), enodeWithId(OTHER_ID)));
    verify(nodeDeniedCounter).inc();
    verify(nodePermittedCounter, never()).inc();
    verify(simulationService, never())
        .simulate(any(CallParameter.class), any(), any(), any(), any());
  }

  @Test
  void preSyncDeniesInboundEvenWhenLocalNodeIsInSharedStaticNodes() {
    PermissioningPlugin plugin = preSyncPlugin();
    plugin.setFixedNodes(Set.of(FIXED_ID, LOCAL_ID), LOCAL_ID);
    OnChainNodePermissioningProvider provider = new OnChainNodePermissioningProvider(plugin);

    // Inbound: Besu passes (remote, local)
    assertFalse(provider.isConnectionPermitted(enodeWithId(OTHER_ID), enodeWithId(LOCAL_ID)));
    assertFalse(provider.isConnectionPermitted(enodeWithId(FIXED_ID), enodeWithId(LOCAL_ID)));
  }

  @Test
  void afterSyncTheContractDecides() {
    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    PermissioningPlugin plugin = preSyncPlugin();
    plugin.setSynchronizationService(syncService(true, true));
    OnChainNodePermissioningProvider provider = new OnChainNodePermissioningProvider(plugin);

    assertFalse(
        provider.isConnectionPermitted(enodeWithId(LOCAL_ID), enodeWithId(FIXED_ID)),
        "After sync, a fixed node is no longer permitted without the contract");
    verify(simulationService, atLeastOnce())
        .simulate(any(CallParameter.class), any(), any(), any(), any());
  }
}
