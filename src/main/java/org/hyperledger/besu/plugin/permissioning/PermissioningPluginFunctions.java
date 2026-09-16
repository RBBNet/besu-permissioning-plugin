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

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Quantity;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.EnodeURL;

/**
 * Utility class for ABI encoding and selector hashing required for smart contract permissioning
 * calls.
 */
public class PermissioningPluginFunctions {

  public static final String TX_FUNCTION_SIGNATURE =
      "transactionAllowed(address,address,uint256,uint256,uint256,bytes)";
  public static final String NODE_FUNCTION_SIGNATURE =
      "connectionAllowed(bytes32,bytes32,bytes16,uint16,bytes32,bytes32,bytes16,uint16)";
  public static final String GET_CONTRACT_ADDRESS_SIGNATURE = "getContractAddress(bytes32)";
  public static final String NODE_V2_FUNCTION_SIGNATURE = "connectionAllowed(string,string,uint16)";

  public static final Bytes TRANSACTION_ALLOWED_SELECTOR = hashSignature(TX_FUNCTION_SIGNATURE);
  public static final Bytes CONNECTION_ALLOWED_SELECTOR = hashSignature(NODE_FUNCTION_SIGNATURE);
  public static final Bytes GET_CONTRACT_ADDRESS_SELECTOR =
      hashSignature(GET_CONTRACT_ADDRESS_SIGNATURE);
  public static final Bytes CONNECTION_ALLOWED_V2_SELECTOR =
      hashSignature(NODE_V2_FUNCTION_SIGNATURE);

  /** V1 tripwire return values: All-ones = true, MSB-clear = false. */
  public static final Bytes TRUE_RESPONSE =
      Bytes.fromHexString("0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

  public static final Bytes FALSE_RESPONSE =
      Bytes.fromHexString("0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

  /** V2 ABI-encoded boolean return values. */
  public static final Bytes V2_TRUE_RESPONSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001");

  public static final Bytes V2_FALSE_RESPONSE =
      Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000");

  public static final String RULES_NAME_KEY = "rules";
  public static final Bytes RULES_NAME_KEY_BYTES = encodeBytes32String(RULES_NAME_KEY);

  private PermissioningPluginFunctions() {}

  /**
   * Computes the 4-byte Keccak-256 function selector from a Solidity function signature string.
   *
   * @param signature Solidity function signature string
   * @return 4-byte selector
   */
  public static Bytes hashSignature(final String signature) {
    byte[] input = signature.getBytes(UTF_8);
    KeccakDigest digest = new KeccakDigest(256);
    digest.update(input, 0, input.length);
    byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return Bytes.wrap(out).slice(0, 4);
  }

  /**
   * Encodes a transaction payload for calling transactionAllowed on an account rules contract.
   *
   * @param transaction Besu transaction instance
   * @return ABI-encoded bytes payload
   */
  public static Bytes encodeTransactionAllowed(final Transaction transaction) {
    Address sender = transaction.getSender();
    Address target = transaction.getTo().map(Address.class::cast).orElse(Address.ZERO);
    BigInteger value = extractTransactionValue(transaction);
    BigInteger gasPrice = extractTransactionGasPrice(transaction);
    BigInteger gasLimit = BigInteger.valueOf(transaction.getGasLimit());

    return Bytes.concatenate(
        encodeAddress(sender != null ? sender : Address.ZERO),
        encodeAddress(target),
        encodeUInt256(value),
        encodeUInt256(gasPrice),
        encodeUInt256(gasLimit),
        encodeUInt256(BigInteger.valueOf(32L * 6L)),
        encodeBytes(transaction.getPayload()));
  }

  /**
   * Safely extracts the transaction value as a {@link BigInteger}.
   *
   * <p>Uses reflection with fallback to direct method invocation to ensure binary compatibility
   * across different Hyperledger Besu releases (e.g., 24.x vs 25.x), where the return type of
   * {@code Transaction.getValue()} may vary between {@code Wei} and {@code Quantity} at the
   * bytecode level.
   *
   * @param transaction the Besu transaction instance
   * @return the transaction value as a BigInteger, or {@link BigInteger#ZERO} if unresolvable
   */
  private static BigInteger extractTransactionValue(final Transaction transaction) {
    try {
      final Method getValueMethod = transaction.getClass().getMethod("getValue");
      final Object valueObject = getValueMethod.invoke(transaction);
      if (valueObject != null) {
        return extractBigIntegerFromObject(valueObject);
      }
    } catch (final ReflectiveOperationException reflectionException) {
      try {
        return transaction.getValue().getAsBigInteger();
      } catch (final Throwable fallbackThrowable) {
        // Fallback attempt failed
      }
    }
    return BigInteger.ZERO;
  }

  /**
   * Safely extracts the transaction gas price as a {@link BigInteger}.
   *
   * <p>Uses reflection with fallback to direct method invocation to ensure binary compatibility
   * across different Hyperledger Besu releases, where the return type of {@code
   * Transaction.getGasPrice()} may differ at the bytecode level.
   *
   * @param transaction the Besu transaction instance
   * @return the gas price as a BigInteger, or {@link BigInteger#ZERO} if missing or unresolvable
   */
  private static BigInteger extractTransactionGasPrice(final Transaction transaction) {
    try {
      final Method getGasPriceMethod = transaction.getClass().getMethod("getGasPrice");
      final Object gasPriceObject = getGasPriceMethod.invoke(transaction);
      if (gasPriceObject instanceof Optional<?> gasPriceOptional && gasPriceOptional.isPresent()) {
        return extractBigIntegerFromObject(gasPriceOptional.get());
      }
    } catch (final ReflectiveOperationException reflectionException) {
      try {
        return transaction.getGasPrice().map(Quantity::getAsBigInteger).orElse(BigInteger.ZERO);
      } catch (final Throwable fallbackThrowable) {
        // Fallback attempt failed
      }
    }
    return BigInteger.ZERO;
  }

  /**
   * Converts a numeric value object returned by Besu domain models (e.g. {@code Wei}, {@code
   * Quantity}, {@code UInt256}) into a {@link BigInteger}.
   *
   * @param valueObject the object instance to convert
   * @return the resolved BigInteger representation, or {@link BigInteger#ZERO} if conversion fails
   */
  private static BigInteger extractBigIntegerFromObject(final Object valueObject) {
    if (valueObject instanceof BigInteger bigIntegerValue) {
      return bigIntegerValue;
    }
    try {
      final Method getAsBigIntegerMethod = valueObject.getClass().getMethod("getAsBigInteger");
      return (BigInteger) getAsBigIntegerMethod.invoke(valueObject);
    } catch (final ReflectiveOperationException reflectionException) {
      try {
        final Method toBigIntegerMethod = valueObject.getClass().getMethod("toBigInteger");
        return (BigInteger) toBigIntegerMethod.invoke(valueObject);
      } catch (final ReflectiveOperationException fallbackException) {
        return BigInteger.ZERO;
      }
    }
  }

  /**
   * Encodes a connection allowed payload for V1 connection permissioning contracts.
   *
   * @param sourceEnode Source node Enode URL
   * @param destinationEnode Destination node Enode URL
   * @return ABI-encoded parameters payload
   */
  public static Bytes encodeConnectionAllowed(
      final EnodeURL sourceEnode, final EnodeURL destinationEnode) {
    return Bytes.concatenate(
        sourceEnode.getNodeId().slice(0, 32),
        sourceEnode.getNodeId().slice(32, 32),
        encodeIp(sourceEnode.getIp()),
        encodePort(sourceEnode.getListeningPortOrZero()),
        destinationEnode.getNodeId().slice(0, 32),
        destinationEnode.getNodeId().slice(32, 32),
        encodeIp(destinationEnode.getIp()),
        encodePort(destinationEnode.getListeningPortOrZero()));
  }

  /**
   * Encodes a connection allowed payload for V2 per-enode connection permissioning contracts.
   *
   * @param hexNodeId Unprefixed hex node ID string
   * @param host Node IP or host string
   * @param port Node listening port
   * @return Full payload including selector and dynamic ABI parameters
   */
  public static Bytes encodeConnectionAllowedV2(
      final String hexNodeId, final String host, final int port) {
    Bytes nodeIdBytes = Bytes.of(hexNodeId.getBytes(UTF_8));
    Bytes hostBytes = Bytes.of(host.getBytes(UTF_8));

    int nodeIdPaddedSize = ((nodeIdBytes.size() + 31) / 32) * 32;
    int hostOffset = 96 + 32 + nodeIdPaddedSize;

    return Bytes.concatenate(
        CONNECTION_ALLOWED_V2_SELECTOR,
        encodeUInt256(BigInteger.valueOf(96)),
        encodeUInt256(BigInteger.valueOf(hostOffset)),
        encodeUInt256(BigInteger.valueOf(port)),
        encodeBytes(nodeIdBytes),
        encodeBytes(hostBytes));
  }

  /**
   * Encodes a getContractAddress call payload for Ingress resolution.
   *
   * @param contractName Target contract key string (e.g. "rules")
   * @return Full payload including selector
   */
  public static Bytes createGetContractAddressPayload(final String contractName) {
    return Bytes.concatenate(GET_CONTRACT_ADDRESS_SELECTOR, encodeBytes32String(contractName));
  }

  /**
   * Helper to create transactionAllowed payload with selector included.
   *
   * @param transaction Besu transaction instance
   * @return Full payload including selector
   */
  public static Bytes createTransactionAllowedPayload(final Transaction transaction) {
    return Bytes.concatenate(TRANSACTION_ALLOWED_SELECTOR, encodeTransactionAllowed(transaction));
  }

  /**
   * Helper to create connectionAllowed payload for V1 contracts with selector included.
   *
   * @param sourceEnode Source node Enode URL
   * @param destinationEnode Destination node Enode URL
   * @return Full payload including selector
   */
  public static Bytes createConnectionAllowedPayload(
      final EnodeURL sourceEnode, final EnodeURL destinationEnode) {
    return Bytes.concatenate(
        CONNECTION_ALLOWED_SELECTOR, encodeConnectionAllowed(sourceEnode, destinationEnode));
  }

  private static Bytes encodeUInt256(final BigInteger value) {
    return Bytes.wrap(UInt256.valueOf(value).toArrayUnsafe());
  }

  private static Bytes encodeAddress(final Address address) {
    return Bytes.concatenate(zeroBytes(12), Bytes.fromHexString(address.toHexString()));
  }

  private static Bytes encodeBytes32String(final String value) {
    byte[] bytes = value.getBytes(UTF_8);
    byte[] padded = new byte[32];
    System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 32));
    return Bytes.wrap(padded);
  }

  private static Bytes encodeBytes(final Bytes value) {
    int paddedSize = ((value.size() + 31) / 32) * 32;
    return Bytes.concatenate(
        encodeUInt256(BigInteger.valueOf(value.size())),
        value,
        zeroBytes(paddedSize - value.size()));
  }

  private static Bytes zeroBytes(final int size) {
    return Bytes.wrap(new byte[size]);
  }

  private static Bytes encodeIp(final InetAddress addr) {
    byte[] res = new byte[32];
    byte[] address = addr.getAddress();
    if (address.length == 4) {
      res[10] = (byte) 0xFF;
      res[11] = (byte) 0xFF;
      System.arraycopy(address, 0, res, 12, 4);
    } else {
      System.arraycopy(address, 0, res, 0, address.length);
    }
    return Bytes.wrap(res);
  }

  private static Bytes encodePort(final int port) {
    byte[] res = new byte[32];
    res[31] = (byte) (port & 0xFF);
    res[30] = (byte) ((port >> 8) & 0xFF);
    return Bytes.wrap(res);
  }
}
