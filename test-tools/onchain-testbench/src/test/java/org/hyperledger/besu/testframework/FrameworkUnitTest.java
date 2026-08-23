package org.hyperledger.besu.testframework;

import org.hyperledger.besu.testframework.contracts.PermissioningStrategy;
import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.core.ConsensusTopology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the framework that don't require Docker.
 */
class FrameworkUnitTest {

    @Test
    @DisplayName("Genesis strategy uses standard pre-deployed addresses")
    void testGenesisStrategyAddresses() {
        PermissioningStrategy strategy = PermissioningStrategy.genesis();

        assertThat(strategy.getAccountIngressAddress())
            .isEqualTo("0x0000000000000000000000000000000000008888");
        assertThat(strategy.getNodeIngressAddress())
            .isEqualTo("0x0000000000000000000000000000000000009999");
    }

    @Test
    @DisplayName("QBFT topology requires N >= 3f+1 validators")
    void testQbftValidatorMath() {
        ConsensusTopology qbft = ConsensusTopology.QBFT;

        // To tolerate f=1 failure, need at least 4 validators
        assertThat(qbft.minValidatorsForFaultTolerance(1)).isEqualTo(4);

        // To tolerate f=2 failures, need at least 7 validators
        assertThat(qbft.minValidatorsForFaultTolerance(2)).isEqualTo(7);

        // To tolerate f=0 failures, need at least 1 validator
        assertThat(qbft.minValidatorsForFaultTolerance(0)).isEqualTo(1);
    }

    @Test
    @DisplayName("BlockchainNetwork builder requires genesis path")
    void testBuilderRequiresGenesis() {
        assertThrows(IllegalStateException.class, () -> {
            org.hyperledger.besu.testframework.core.BlockchainNetwork.builder()
                .withValidators(4)
                .build();
        });
    }

    @Test
    @DisplayName("BesuNode role enum has expected values")
    void testNodeRoles() {
        assertThat(BesuNode.Role.values()).contains(
            BesuNode.Role.VALIDATOR,
            BesuNode.Role.RPC,
            BesuNode.Role.BOOTNODE,
            BesuNode.Role.ROGUE);
    }

    @Test
    @DisplayName("PermissioningContext tracks contract addresses")
    void testPermissioningContext() {
        var ctx = new org.hyperledger.besu.testframework.core.PermissioningContext();
        ctx.setAccountIngressAddress("0x8888");
        ctx.setNodeIngressAddress("0x9999");
        ctx.setAdminAddress("0xaaaa");

        assertThat(ctx.isFullyConfigured()).isTrue();
    }
}
