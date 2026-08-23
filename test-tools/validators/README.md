# Static Validator Keys for E2E Test Suite (Suite 3)

## Purpose

Pre-generated, deterministic validator keys for QBFT consensus testing.
No runtime key generation. Reproducible, CI-compatible.

## Files

| File | Description |
|:---|:---|
| `validator1.key` | 32-byte hex-encoded secp256k1 private key (64 hex chars, no 0x prefix) |
| `validator2.key` | Same format |
| `validator3.key` | Same format |
| `genesis.json` | QBFT genesis with 3 validators + pre-deployed Ingress contracts |

## Validator Addresses

| Validator | Address |
|:---|:---|
| validator1 | `0x77C003cA05b858949e6b07D02866C892d0BDf2bc` |
| validator2 | `0x3860065E759d13a414cB46F4B3f21D8FC7bA2eB2` |
| validator3 | `0xC1690E283f14a0d409c7cd989EfAfFe6c8c3634f` |

## Usage

### In genesis.json extraData

These validators are embedded in `genesis.json` with proper RLP-encoded QBFT extraData.

### As Besu node keys

```bash
besu --node-private-key-file=test-tools/validators/validator1.key \
     --genesis-file=test-tools/validators/genesis.json
```

### In Docker Compose / Testcontainers

```java
// Mount key into container
container.withCopyFileToContainer(
    MountableFile.forHostPath("test-tools/validators/validator1.key"),
    "/opt/besu/key")
.withEnv("BESU_NODE_PRIVATE_KEY_FILE", "/opt/besu/key");
```

## Security

These keys are for TESTING ONLY. Do NOT use in production.
Keys committed to version control — no secrets here.

## Regeneration

To regenerate with different keys:

```bash
cd test-tools/validators
for i in 1 2 3; do
    openssl ecparam -name secp256k1 -genkey -noout 2>/dev/null | \
        openssl ec -text -noout 2>/dev/null | \
        awk '/priv:/{flag=1; next} /pub:/{flag=0} flag' | \
        tr -d ' :\n' > validator${i}.key
    ADDR=$(cast wallet address --private-key "0x$(cat validator${i}.key)" 2>/dev/null)
    echo "validator${i}: $ADDR"
done
# Then update genesis.json extraData with new addresses
```
