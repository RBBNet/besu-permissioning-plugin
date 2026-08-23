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

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.plugin.data.EnodeURL;
import org.hyperledger.besu.plugin.services.permissioning.NodeConnectionPermissioningProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node connection permissioning provider evaluating P2P handshakes against V1/V2 node rules smart
 * contracts.
 */
class OnChainNodePermissioningProvider implements NodeConnectionPermissioningProvider {
  private static final Logger LOG = LoggerFactory.getLogger(OnChainNodePermissioningProvider.class);

  private final PermissioningPlugin plugin;

  OnChainNodePermissioningProvider(final PermissioningPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean isConnectionPermitted(
      final EnodeURL sourceEnode, final EnodeURL destinationEnode) {
    if (plugin.getNodeCheckCounter() != null) {
      plugin.getNodeCheckCounter().inc();
    }

    if (plugin.getNodeIngressAddress() == null) {
      LOG.warn(
          "PermissioningPlugin: P2P connection rejected — missing Node Ingress address (fail-close).");
      if (plugin.getNodeDeniedCounter() != null) {
        plugin.getNodeDeniedCounter().inc();
      }
      return false;
    }

    try {
      Address rulesAddress = plugin.getNodeRulesAddress();
      if (rulesAddress == null || rulesAddress.equals(Address.ZERO)) {
        LOG.error(
            "PermissioningPlugin: Could not resolve NodeRules contract address. Rejecting connection.");
        if (plugin.getNodeDeniedCounter() != null) {
          plugin.getNodeDeniedCounter().inc();
        }
        return false;
      }

      int version = plugin.getNodeContractVersion();
      boolean permitted;

      if (version == 2) {
        permitted =
            isNodePermittedV2(sourceEnode, rulesAddress)
                && isNodePermittedV2(destinationEnode, rulesAddress);
      } else {
        Bytes payload =
            Bytes.concatenate(
                PermissioningPluginFunctions.CONNECTION_ALLOWED_SELECTOR,
                PermissioningPluginFunctions.encodeConnectionAllowed(
                    sourceEnode, destinationEnode));

        permitted =
            plugin
                .callContract(rulesAddress, payload)
                .map(
                    result -> {
                      if (result.equals(PermissioningPluginFunctions.TRUE_RESPONSE)) {
                        return true;
                      } else if (result.equals(PermissioningPluginFunctions.FALSE_RESPONSE)) {
                        LOG.warn(
                            "PermissioningPlugin: P2P connection DENIED by NodeRules smart contract.");
                        return false;
                      } else {
                        LOG.error(
                            "PermissioningPlugin: Unexpected response from NodeRules: {}. Denying.",
                            result);
                        return false;
                      }
                    })
                .orElse(false);
      }

      if (permitted) {
        if (plugin.getNodePermittedCounter() != null) {
          plugin.getNodePermittedCounter().inc();
        }
      } else {
        if (plugin.getNodeDeniedCounter() != null) {
          plugin.getNodeDeniedCounter().inc();
        }
      }
      return permitted;
    } catch (Exception e) {
      LOG.error(
          "PermissioningPlugin: Exception during node connection permissioning evaluation. Denying by default.",
          e);
      if (plugin.getNodeDeniedCounter() != null) {
        plugin.getNodeDeniedCounter().inc();
      }
      return false;
    }
  }

  private boolean isNodePermittedV2(final EnodeURL enode, final Address rulesAddress) {
    try {
      String hexNodeId = enode.getNodeId().toUnprefixedHexString();
      String host = enode.getIp().getHostAddress();
      int port = enode.getListeningPortOrZero();

      Bytes payload = PermissioningPluginFunctions.encodeConnectionAllowedV2(hexNodeId, host, port);
      return plugin
          .callContract(rulesAddress, payload)
          .map(
              result -> {
                if (result.equals(PermissioningPluginFunctions.V2_TRUE_RESPONSE)) {
                  return true;
                } else if (result.equals(PermissioningPluginFunctions.V2_FALSE_RESPONSE)) {
                  return false;
                } else {
                  LOG.error(
                      "PermissioningPlugin: Unexpected V2 response from NodeRules: {}. Denying.",
                      result);
                  return false;
                }
              })
          .orElse(false);
    } catch (Exception e) {
      LOG.error("PermissioningPlugin: V2 node check failed for {}: {}", enode, e.getMessage());
      return false;
    }
  }
}
