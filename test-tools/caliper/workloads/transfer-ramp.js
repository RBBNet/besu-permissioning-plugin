'use strict';

const { WorkloadModuleBase } = require('@hyperledger/caliper-core');

class TransferRampWorkload extends WorkloadModuleBase {
    constructor() {
        super();
        this.txIndex = 0;
        this.currentRate = 0;
        this.lastRateUpdate = 0;
    }

    async initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext) {
        await super.initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext);
        this.accounts = roundArguments.accounts || 20;
        this.value = roundArguments.value || '0x1';
        this.toAddress = roundArguments.toAddress || '0x70997970C51812dc3A010C7d01b50e0d17dc79C8';
    }

    async submitTransaction() {
        this.txIndex = (this.txIndex + 1) % this.accounts;

        const tx = {
            from: '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266',
            to: this.toAddress,
            value: this.value,
            gas: 21000,
            gasPrice: '0x0'
        };

        await this.sutAdapter.sendTransaction(tx);
    }
}

function createWorkloadModule() {
    return new TransferRampWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
