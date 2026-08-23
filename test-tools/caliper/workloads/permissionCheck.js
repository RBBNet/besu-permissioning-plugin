'use strict';

const { WorkloadModuleBase } = require('@hyperledger/caliper-core');

class PermissionCheckWorkload extends WorkloadModuleBase {
    constructor() {
        super();
        this.txIndex = 0;
    }

    async initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext) {
        await super.initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext);
        this.targetContract = roundArguments.targetContract || '0x0e9e81bb09cdd55b607373e89e3154354a925b7d';
        this.accounts = roundArguments.accounts || 20;
    }

    async submitTransaction() {
        this.txIndex = (this.txIndex + 1) % this.accounts;

        const tx = {
            from: '0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266',
            to: this.targetContract,
            gas: 3000000,
            gasPrice: '0x0',
            value: '0x0',
            data: '0x936421d5' +
                  '000000000000000000000000f39Fd6e51aad88F6F4ce6aB8827279cffFb92266' +
                  '00000000000000000000000070997970C51812dc3A010C7d01b50e0d17dc79C8' +
                  '0000000000000000000000000000000000000000000000000000000000000000' +
                  '0000000000000000000000000000000000000000000000000000000000000000' +
                  '0000000000000000000000000000000000000000000000000000000000000000' +
                  '00000000000000000000000000000000000000000000000000000000000000c0' +
                  '0000000000000000000000000000000000000000000000000000000000000000'
        };

        await this.sutAdapter.sendTransaction(tx);
    }
}

function createWorkloadModule() {
    return new PermissionCheckWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;
