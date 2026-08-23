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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Quantity;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.EnodeURL;
import org.junit.jupiter.api.Test;

class PermissioningPluginFunctionsTest {

  @Test
  void hashSignatureReturnsFourBytes() {
    Bytes hash = PermissioningPluginFunctions.hashSignature("transfer(address,uint256)");
    assertEquals(4, hash.size(), "function selector must be exactly 4 bytes");
  }

  @Test
  void hashSignatureIsDeterministic() {
    Bytes first = PermissioningPluginFunctions.hashSignature("transfer(address,uint256)");
    Bytes second = PermissioningPluginFunctions.hashSignature("transfer(address,uint256)");
    assertEquals(first, second);
  }

  @Test
  void hashSignatureDifferentFunctionsYieldDifferentSelectors() {
    Bytes selector1 = PermissioningPluginFunctions.hashSignature("foo()");
    Bytes selector2 = PermissioningPluginFunctions.hashSignature("bar()");
    assertFalse(selector1.equals(selector2));
  }

  @Test
  void hashSignaturePreservesStandardEthereumSelectors() {
    Bytes txSelector =
        PermissioningPluginFunctions.hashSignature(
            PermissioningPluginFunctions.TX_FUNCTION_SIGNATURE);
    Bytes nodeSelector =
        PermissioningPluginFunctions.hashSignature(
            PermissioningPluginFunctions.NODE_FUNCTION_SIGNATURE);
    Bytes addrSelector =
        PermissioningPluginFunctions.hashSignature(
            PermissioningPluginFunctions.GET_CONTRACT_ADDRESS_SIGNATURE);

    for (Bytes s : new Bytes[] {txSelector, nodeSelector, addrSelector}) {
      assertEquals(4, s.size());
      assertFalse(s.isZero(), "selector must not be zero");
    }

    assertFalse(txSelector.equals(nodeSelector));
    assertFalse(txSelector.equals(addrSelector));
    assertFalse(nodeSelector.equals(addrSelector));
  }

  @Test
  void createGetContractAddressPayloadRules() {
    Bytes payload = PermissioningPluginFunctions.createGetContractAddressPayload("rules");

    assertEquals(4 + 32, payload.size());

    Bytes expectedSelector =
        PermissioningPluginFunctions.hashSignature(
            PermissioningPluginFunctions.GET_CONTRACT_ADDRESS_SIGNATURE);
    assertEquals(expectedSelector, payload.slice(0, 4));

    byte[] rulesBytes = new byte[32];
    byte[] utf8 = "rules".getBytes(UTF_8);
    System.arraycopy(utf8, 0, rulesBytes, 0, utf8.length);
    assertArrayEquals(rulesBytes, payload.slice(4, 32).toArrayUnsafe());
  }

  @Test
  void createConnectionAllowedPayloadStructure() throws UnknownHostException {
    Bytes enodeId = Bytes.concatenate(Bytes.random(32), Bytes.random(32));
    InetAddress mockAddr = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});

    EnodeURL sourceEnode = mock(EnodeURL.class);
    when(sourceEnode.getNodeId()).thenReturn(enodeId);
    when(sourceEnode.getIp()).thenReturn(mockAddr);
    when(sourceEnode.getListeningPortOrZero()).thenReturn(30303);

    EnodeURL destEnode = mock(EnodeURL.class);
    when(destEnode.getNodeId()).thenReturn(enodeId);
    when(destEnode.getIp()).thenReturn(mockAddr);
    when(destEnode.getListeningPortOrZero()).thenReturn(30304);

    Bytes payload =
        PermissioningPluginFunctions.createConnectionAllowedPayload(sourceEnode, destEnode);

    assertEquals(4 + 8 * 32, payload.size());

    Bytes expectedSelector =
        PermissioningPluginFunctions.hashSignature(
            PermissioningPluginFunctions.NODE_FUNCTION_SIGNATURE);
    assertEquals(expectedSelector, payload.slice(0, 4));
    assertEquals(enodeId.slice(0, 32), payload.slice(4, 32));
    assertEquals(enodeId.slice(32, 32), payload.slice(36, 32));
  }

  @Test
  void createTransactionAllowedPayloadStructure() {
    Address sender = Address.fromHexString("0x1234567890123456789012345678901234567890");
    Address to = Address.fromHexString("0x0987654321098765432109876543210987654321");
    BigInteger value = BigInteger.valueOf(100);
    BigInteger gasPrice = BigInteger.valueOf(200);
    long gasLimit = 21000L;
    Bytes txPayload = Bytes.fromHexString("0xaabbccdd");

    Quantity valueQty = mock(Quantity.class);
    when(valueQty.getAsBigInteger()).thenReturn(value);
    Quantity gasPriceQty = mock(Quantity.class);
    when(gasPriceQty.getAsBigInteger()).thenReturn(gasPrice);

    Transaction tx = mock(Transaction.class);
    when(tx.getSender()).thenReturn(sender);
    doReturn(Optional.of(to)).when(tx).getTo();
    when(tx.getValue()).thenReturn(valueQty);
    doReturn(Optional.of(gasPriceQty)).when(tx).getGasPrice();
    when(tx.getGasLimit()).thenReturn(gasLimit);
    when(tx.getPayload()).thenReturn(txPayload);

    Bytes result = PermissioningPluginFunctions.createTransactionAllowedPayload(tx);

    Bytes expectedSelector =
        PermissioningPluginFunctions.hashSignature(
            PermissioningPluginFunctions.TX_FUNCTION_SIGNATURE);
    assertEquals(expectedSelector, result.slice(0, 4));
    assertTrue(result.size() > 4 + 7 * 32);
  }

  @Test
  void v2TrueResponseIsAbiEncodedBoolTrue() {
    assertEquals(
        Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001"),
        PermissioningPluginFunctions.V2_TRUE_RESPONSE);
  }

  @Test
  void v2FalseResponseIsAbiEncodedBoolFalse() {
    assertEquals(
        Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000"),
        PermissioningPluginFunctions.V2_FALSE_RESPONSE);
  }

  @Test
  void encodeConnectionAllowedV2Structure() {
    String hexNodeId =
        "6f8a80d14311c39f35f516fa664deaaaa13e85b2f7493f37f6144d86991ec012937307647bd3b9a82abe2974e1407241d54947bbb39763a4cac9f77166ad92a0";
    String host = "10.3.58.6";
    int port = 30303;

    Bytes result = PermissioningPluginFunctions.encodeConnectionAllowedV2(hexNodeId, host, port);

    assertEquals(PermissioningPluginFunctions.CONNECTION_ALLOWED_V2_SELECTOR, result.slice(0, 4));
    assertTrue(result.size() >= 324, "V2 payload size must be at least 324 bytes");
  }
}
