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
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.services.permissioning.TransactionPermissioningProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction permissioning provider evaluating EVM transaction validation rules against smart
 * contracts.
 */
class OnChainTransactionPermissioningProvider implements TransactionPermissioningProvider {
  private static final Logger LOG =
      LoggerFactory.getLogger(OnChainTransactionPermissioningProvider.class);

  private final PermissioningPlugin plugin;

  OnChainTransactionPermissioningProvider(final PermissioningPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean isPermitted(final Transaction transaction) {
    if (plugin.getTxCheckCounter() != null) {
      plugin.getTxCheckCounter().inc();
    }

    if (plugin.getAccountIngressAddress() == null) {
      LOG.warn(
          "PermissioningPlugin: Transaction rejected — missing Account Ingress address (fail-close).");
      if (plugin.getTxDeniedCounter() != null) {
        plugin.getTxDeniedCounter().inc();
      }
      return false;
    }

    Address sender = transaction.getSender();
    if (sender == null) {
      LOG.warn("PermissioningPlugin: Transaction rejected — null transaction sender (fail-close).");
      if (plugin.getTxDeniedCounter() != null) {
        plugin.getTxDeniedCounter().inc();
      }
      return false;
    }

    try {
      Address rulesAddress = plugin.getAccountRulesAddress();
      if (rulesAddress == null || rulesAddress.equals(Address.ZERO)) {
        LOG.error(
            "PermissioningPlugin: Could not resolve AccountRules contract address. Rejecting transaction.");
        if (plugin.getTxDeniedCounter() != null) {
          plugin.getTxDeniedCounter().inc();
        }
        return false;
      }

      Bytes payload =
          Bytes.concatenate(
              PermissioningPluginFunctions.TRANSACTION_ALLOWED_SELECTOR,
              PermissioningPluginFunctions.encodeTransactionAllowed(transaction));

      boolean permitted = plugin.simulateAndGetBool(rulesAddress, payload);

      if (permitted) {
        if (plugin.getTxPermittedCounter() != null) {
          plugin.getTxPermittedCounter().inc();
        }
      } else {
        if (plugin.getTxDeniedCounter() != null) {
          plugin.getTxDeniedCounter().inc();
        }
      }
      return permitted;
    } catch (Exception e) {
      LOG.error(
          "PermissioningPlugin: Exception during transaction permissioning evaluation. Denying by default.",
          e);
      if (plugin.getTxDeniedCounter() != null) {
        plugin.getTxDeniedCounter().inc();
      }
      return false;
    }
  }
}
