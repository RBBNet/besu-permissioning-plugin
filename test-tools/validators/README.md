# Validator Configuration for E2E Test Suite

## Purpose

Guidance and configuration templates for QBFT consensus testing with the Besu On-Chain Permissioning Plugin.

## Security Notice

Plaintext private key files are NOT committed to version control. Keys must be generated dynamically during test setup or injected securely via environment variables.

## Validator Addresses

| Validator | Address |
| :--- | :--- |
| validator1 | `0x77C003cA05b858949e6b07D02866C892d0BDf2bc` |
| validator2 | `0x3860065E759d13a414cB46F4B3f21D8FC7bA2eB2` |
| validator3 | `0xC1690E283f14a0d409c7cd989EfAfFe6c8c3634f` |

## Dynamic Key Generation Example

To generate ephemeral keys for local testing:

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
```
