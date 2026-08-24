# Extension Guide: Adding New Smart Contracts to the Permissioning Test Framework

This guide explains how to extend the test framework to support new smart contracts developed for permissioned Besu networks.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Step-by-Step: Adding a New Contract](#2-step-by-step)
3. [Practical Example: Simple Whitelist Contract](#3-practical-example)
4. [Creating a New Permissioning Strategy](#4-creating-a-new-permissioning-strategy)
5. [Creating Test Scenarios for the New Contract](#5-creating-test-scenarios)
6. [Extension Points Summary Table](#6-extension-points-summary-table)

---

## 1. Architecture Overview

The framework provides three main extension points for integrating new smart contracts:

```
┌──────────────────────────────────────────────────────┐
│                  NEW SMART CONTRACT                  │
│                                                      │
│   PermissioningStrategy    → deploy & interaction   │
│   Genesis (pre-deploy)     → fixed genesis address  │
│   Scenario                → test scenario           │
└──────────────────────────────────────────────────────┘
```

| Extension Point | Action Required | Usage Scenario |
| :--- | :--- | :--- |
| ** Strategy** | Implement new class for `PermissioningStrategy` | Custom deployment & governance logic |
| ** Genesis** | Add address & bytecode to `genesis.json` | Contract must be available at block 0 |
| ** Scenario** | Create new class under `scenarios/` | Custom contract test scenario |

---

## 2. Step-by-Step Guide

### 2.1 Contract Classification

Before starting, classify the smart contract:

- **Permissioning Contract** — Modifies access rules (Accounts/Nodes).
  - → Create a new `PermissioningStrategy`.
- **Governance Contract** — Manages administrators or organizations.
  - → Extend `PermissioningContext` + create test scenario.
- **Utility Contract** — Provides auxiliary functions (e.g., registry).
  - → Add to genesis + create scenario.

### 2.2 Extension Workflow

1. Generate ABI and bytecode (via `solc` or `foundry`).
2. Define pre-deployed address in `genesis.json`.
3. Implement `PermissioningStrategy` interface.
4. Add scenario class to `scenarios/`.
5. Run test suite verification (`./gradlew test`).
