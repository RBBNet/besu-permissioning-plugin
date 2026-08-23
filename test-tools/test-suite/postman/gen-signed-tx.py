#!/usr/bin/env python3
"""
Signed Transaction Generator for Besu Permissioning Plugin Test Suite.

Generates EIP-155 raw transactions for use with eth_sendRawTransaction
in Postman collections and automated API test suites.

Usage:
  python3 gen-signed-tx.py                          # Uses defaults (admin account)
  python3 gen-signed-tx.py --account unauth         # Blocked account
  python3 gen-signed-tx.py --rpc-url http://localhost:9001
  python3 gen-signed-tx.py --json                   # JSON output format

Dependencies: pip3 install coincurve rlp eth-utils requests
"""

import argparse
import sys
import json
import hashlib
import requests
from typing import Tuple

try:
    import rlp as rlp_lib
    HAS_RLP = True
except ImportError:
    HAS_RLP = False

try:
    from coincurve import PrivateKey
    HAS_COINCURVE = True
except ImportError:
    HAS_COINCURVE = False

try:
    from eth_utils import keccak
    HAS_ETH_UTILS = True
except ImportError:
    HAS_ETH_UTILS = False


ADMIN_PK  = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
ADMIN_ADDR = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
UNAUTH_PK  = "59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
UNAUTH_ADDR = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

ACCOUNT_RULES   = "0x0e9e81bb09cdd55b607373e89e3154354a925b7d"
NODE_RULES      = "0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616"
ACCOUNT_INGRESS = "0x0000000000000000000000000000000000008888"
NODE_INGRESS    = "0x0000000000000000000000000000000000009999"
ADMIN_PROXY     = "0x181a92c9b76ab7271a03b640cc172e75a0dc3484"

DEFAULT_RPC = "http://localhost:9005"
DEFAULT_GAS_LIMIT = 21000


def rlp_encode(val) -> bytes:
    if isinstance(val, int):
        if val == 0:
            return b"\x80"
        hex_str = f"{val:x}"
        if len(hex_str) % 2 != 0:
            hex_str = "0" + hex_str
        b = bytes.fromhex(hex_str)
        if len(b) == 1 and b[0] < 0x80:
            return b
        return bytes([0x80 + len(b)]) + b
    elif isinstance(val, bytes):
        if len(val) == 1 and val[0] < 0x80:
            return val
        elif len(val) < 55:
            return bytes([0x80 + len(val)]) + val
        else:
            len_b = bytes.fromhex(f"{len(val):x}")
            return bytes([0xB7 + len(len_b)]) + len_b + val
    elif isinstance(val, list):
        payload = b"".join(rlp_encode(x) for x in val)
        if len(payload) < 55:
            return bytes([0xC0 + len(payload)]) + payload
        else:
            len_b = bytes.fromhex(f"{len(payload):x}")
            return bytes([0xF7 + len(len_b)]) + len_b + payload
    raise TypeError(f"Unsupported RLP type: {type(val)}")


def keccak256_hash(data: bytes) -> bytes:
    if HAS_ETH_UTILS:
        return keccak(data)
    else:
        try:
            from Crypto.Hash import keccak as pycryptodome_keccak
            k = pycryptodome_keccak.new(digest_bits=256)
            k.update(data)
            return k.digest()
        except ImportError:
            sys.exit("❌ Error: Install dependencies via: pip3 install pycryptodome or eth-utils")


def sign_hash_secp256k1(msg_hash: bytes, private_key_bytes: bytes) -> Tuple[int, bytes, bytes]:
    if HAS_COINCURVE:
        pk = PrivateKey(private_key_bytes)
        sig = pk.sign_recoverable(msg_hash, hasher=None)
        r = sig[:32]
        s = sig[32:64]
        v_rec = sig[64]
        return v_rec, r, s
    else:
        sys.exit("❌ Error: Install coincurve via: pip3 install coincurve")


def build_signed_transaction(
    private_key_hex: str,
    to_addr: str,
    nonce: int,
    gas_price: int,
    gas_limit: int,
    value: int,
    chain_id: int,
    data: bytes = b"",
) -> str:
    pk_bytes = bytes.fromhex(private_key_hex)
    to_bytes = bytes.fromhex(to_addr[2:]) if to_addr.startswith("0x") else bytes.fromhex(to_addr)

    unsigned_items = [
        nonce,
        gas_price,
        gas_limit,
        to_bytes,
        value,
        data,
        chain_id,
        b"",
        b"",
    ]
    encoded_unsigned = rlp_encode(unsigned_items)
    tx_hash = keccak256_hash(encoded_unsigned)

    v_rec, r_bytes, s_bytes = sign_hash_secp256k1(tx_hash, pk_bytes)
    v_eip155 = v_rec + 35 + (chain_id * 2)

    signed_items = [
        nonce,
        gas_price,
        gas_limit,
        to_bytes,
        value,
        data,
        v_eip155,
        r_bytes.lstrip(b"\x00"),
        s_bytes.lstrip(b"\x00"),
    ]
    encoded_signed = rlp_encode(signed_items)
    return "0x" + encoded_signed.hex()


def rpc_call(url: str, method: str, params: list):
    payload = {"jsonrpc": "2.0", "method": method, "params": params, "id": 1}
    try:
        resp = requests.post(url, json=payload, timeout=5)
        resp.raise_for_status()
        res = resp.json()
        if "error" in res:
            raise RuntimeError(f"RPC Error: {res['error']}")
        return res["result"]
    except requests.exceptions.RequestException as e:
        sys.exit(f"❌ Failed connecting to node RPC ({url}): {e}")


def fetch_chain_id(url: str) -> int:
    res = rpc_call(url, "eth_chainId", [])
    return int(res, 16)


def fetch_nonce(url: str, addr: str) -> int:
    res = rpc_call(url, "eth_getTransactionCount", [addr, "latest"])
    return int(res, 16)


def fetch_gas_price(url: str) -> int:
    res = rpc_call(url, "eth_gasPrice", [])
    return int(res, 16)


def main():
    parser = argparse.ArgumentParser(
        description="Signed Transaction Generator for Besu Permissioning Test Suite",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # ADMIN account transaction (permitted)
  python3 gen-signed-tx.py

  # UNAUTH account transaction (blocked)
  python3 gen-signed-tx.py --account unauth

  # Target specific RPC endpoint
  python3 gen-signed-tx.py --rpc-url http://localhost:9001

  # Output JSON format
  python3 gen-signed-tx.py --json
        """,
    )
    parser.add_argument("--account", choices=["admin", "unauth"], default="admin",
                        help="Signing account (admin=permitted, unauth=blocked). Default: admin")
    parser.add_argument("--rpc-url", default=DEFAULT_RPC,
                        help=f"Besu node RPC URL. Default: {DEFAULT_RPC}")
    parser.add_argument("--nonce", type=int, default=None,
                        help="Transaction nonce (auto-detected if omitted)")
    parser.add_argument("--gas-price", type=int, default=None,
                        help="Gas price in wei (auto-detected if omitted)")
    parser.add_argument("--gas-limit", type=int, default=DEFAULT_GAS_LIMIT,
                        help=f"Gas limit. Default: {DEFAULT_GAS_LIMIT}")
    parser.add_argument("--value", type=int, default=1,
                        help="Value in wei. Default: 1")
    parser.add_argument("--to", type=str, default=None,
                        help="Target address (default: self-transfer)")
    parser.add_argument("--json", action="store_true",
                        help="Output JSON format")
    parser.add_argument("--chain-id", type=int, default=None,
                        help="Chain ID (auto-detected if omitted)")
    args = parser.parse_args()

    if args.account == "admin":
        pk = ADMIN_PK
        addr = ADMIN_ADDR
    else:
        pk = UNAUTH_PK
        addr = UNAUTH_ADDR

    to_addr = args.to if args.to else addr

    if args.chain_id is not None:
        chain_id = args.chain_id
    else:
        chain_id = fetch_chain_id(args.rpc_url)

    if args.nonce is not None:
        nonce = args.nonce
    else:
        nonce = fetch_nonce(args.rpc_url, addr)

    if args.gas_price is not None:
        gas_price = args.gas_price
    else:
        gas_price = fetch_gas_price(args.rpc_url)

    raw_tx = build_signed_transaction(
        private_key_hex=pk,
        to_addr=to_addr,
        nonce=nonce,
        gas_price=gas_price,
        gas_limit=args.gas_limit,
        value=args.value,
        chain_id=chain_id,
        data=b"",
    )

    if args.json:
        output = {
            "signed_raw_tx": raw_tx,
            "account": args.account,
            "from_addr": addr,
            "to_addr": to_addr,
            "nonce": nonce,
            "gas_price": gas_price,
            "gas_limit": args.gas_limit,
            "value": args.value,
            "chain_id": chain_id,
            "gas_price_gwei": round(gas_price / 1e9, 2),
        }
        print(json.dumps(output, indent=2))
    else:
        print(f"""
\033[92m{'='*70}\033[0m
\033[1m  GENERATED SIGNED TRANSACTION\033[0m
\033[92m{'='*70}\033[0m
  Account:   {args.account.upper()} ({addr})
  Target:    {to_addr}
  Chain ID:  {chain_id} (0x{chain_id:x})
  Nonce:     {nonce}
  Gas Price: {gas_price} wei ({gas_price/1e9:.1f} gwei)
  Gas Limit: {args.gas_limit}
  Value:     {args.value} wei

\033[1m  Raw Transaction (eth_sendRawTransaction):\033[0m
\033[96m  {raw_tx}\033[0m
""")


if __name__ == "__main__":
    main()
