'use strict';

const { WorkloadModuleBase } = require('@hyperledger/caliper-core');

class MixedWorkload extends WorkloadModuleBase {
    constructor() {
        super();
        this.txIndex = 0;
    }

    async initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext) {
        await super.initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext);
        this.accounts = roundArguments.accounts || 20;
        this.value = roundArguments.value || '0x1';
        this.mixRatio = roundArguments.mixRatio || { transfer: 0.8, contractCall: 0.2 };
        this.contractAddress = roundArguments.contractAddress || '0x70997970C51812dc3A010C7d01b50e0d17dc79C8';
    }

    async submitTransaction() {
        this.txIndex = (this.txIndex + 1) % this.accounts;
        const isTransfer = Math.random() < this.mixRatio.transfer;

        if (isTransfer) {
            const tx = {
                from: '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266',
                to: this.contractAddress,
                value: this.value,
                gas: 21000,
                gasPrice: '0x0'
            };
            await this.sutAdapter.sendTransaction(tx);
        } else {
            const tx = {
                from: '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266',
                to: this.contractAddress,
                gas: 100000,
                gasPrice: '0x0',
                value: '0x0',
                data: '0x60fe47b1' + // set(uint256)
                      '000000000000000000000000000000000000000000000000000000000000002a'
            };
            await this.sutAdapter.sendTransaction(tx);
        }
    }
}

function createWorkloadModule() {
    return new MixedWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
