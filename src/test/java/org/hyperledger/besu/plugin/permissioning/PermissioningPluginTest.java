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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.EnodeURL;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSimulationResult;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.PermissioningService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

  @Test
  void registerWithValidServices() {
    when(serviceManager.getService(PermissioningService.class))
        .thenReturn(Optional.of(permissioningService));

    PermissioningPlugin plugin = new PermissioningPlugin();
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
  void isPermittedReturnsFalseWhenRulesResolutionFails() {
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
    assertFalse(result, "Must deny when Rules contract address resolution fails (fail-close)");
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
    Bytes ingressOutput =
        Bytes.concatenate(
            Bytes.wrap(new byte[12]),
            Address.fromHexString("0x9999999999999999999999999999999999999999"));
    TransactionProcessingResult ingressProcResult = mock(TransactionProcessingResult.class);
    when(ingressProcResult.isSuccessful()).thenReturn(true);
    when(ingressProcResult.getOutput()).thenReturn(ingressOutput);
    TransactionSimulationResult ingressSimResult =
        new TransactionSimulationResult(mock(Transaction.class), ingressProcResult);

    TransactionProcessingResult connProcResult = mock(TransactionProcessingResult.class);
    when(connProcResult.isSuccessful()).thenReturn(true);
    when(connProcResult.getOutput()).thenReturn(PermissioningPluginFunctions.TRUE_RESPONSE);
    TransactionSimulationResult connSimResult =
        new TransactionSimulationResult(mock(Transaction.class), connProcResult);

    when(simulationService.simulate(any(CallParameter.class), any(), any(), any(), any()))
        .thenReturn(Optional.of(ingressSimResult))
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
    Bytes ingressOutput =
        Bytes.concatenate(
            Bytes.wrap(new byte[12]),
            Address.fromHexString("0x9999999999999999999999999999999999999999"));
    TransactionProcessingResult ingressProcResult = mock(TransactionProcessingResult.class);
    when(ingressProcResult.isSuccessful()).thenReturn(true);
    when(ingressProcResult.getOutput()).thenReturn(ingressOutput);
    TransactionSimulationResult ingressSimResult =
        new TransactionSimulationResult(mock(Transaction.class), ingressProcResult);

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
        .thenReturn(Optional.of(ingressSimResult))
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
}
