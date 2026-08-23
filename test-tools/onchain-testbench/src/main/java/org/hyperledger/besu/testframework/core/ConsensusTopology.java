package org.hyperledger.besu.testframework.core;

/**
 * Defines supported consensus topologies for the Besu network.
 * QBFT is the standard for Besu permissioned networks.
 */
public enum ConsensusTopology {
    QBFT,
    IBFT2,
    CLIQUE;

    public int minValidatorsForFaultTolerance(int f) {
        return switch (this) {
            case QBFT, IBFT2 -> 3 * f + 1;
            case CLIQUE -> 2 * f + 1;
        };
    }
}
