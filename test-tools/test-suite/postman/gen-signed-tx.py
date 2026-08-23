#!/usr/bin/env python3
"""
Gerador de Transações Assinadas para o Plugin Permissioning Test Suite.

Gera raw transactions válidas (EIP-155) para usar com eth_sendRawTransaction
na coleção Postman. Assina com a chainId correta da rede Besu local.

Uso:
  python3 gen-signed-tx.py                          # usa defaults (admin, rpc=9005)
  python3 gen-signed-tx.py --account unauth         # conta bloqueada
  python3 gen-signed-tx.py --rpc-url http://localhost:9001  # outro nó
  python3 gen-signed-tx.py --nonce 5 --gas-price 1000000000  # valores específicos
  python3 gen-signed-tx.py --json                   # saída JSON para Postman

Dependências: pip3 install coincurve rlp eth-utils requests
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


# =============================================================================
# Constantes — endereços e chaves do test-suite
# =============================================================================
ADMIN_PK  = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
ADMIN_ADDR = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
UNAUTH_PK  = "59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
UNAUTH_ADDR = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

# Contratos pré-deployados no genesis (cenario-valioso.json)
ACCOUNT_RULES   = "0x0e9e81bb09cdd55b607373e89e3154354a925b7d"
NODE_RULES      = "0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616"
ACCOUNT_INGRESS = "0x0000000000000000000000000000000000008888"
NODE_INGRESS    = "0x0000000000000000000000000000000000009999"
ADMIN_PROXY     = "0x181a92c9b76ab7271a03b640cc172e75a0dc3484"

DEFAULT_RPC = "http://localhost:9005"
DEFAULT_GAS_LIMIT = 21000


# =============================================================================
# Helpers
# =============================================================================

def die(msg: str) -> None:
    print(f"\033[91m[ERRO]\033[0m {msg}")
    if "ImportError" in msg or "pip3" in msg:
        print("   Instale as dependências: pip3 install coincurve rlp eth-utils requests")
    sys.exit(1)


def keccak256(data: bytes) -> bytes:
    """Hash Keccak-256."""
    if HAS_ETH_UTILS:
        return keccak(data)
    try:
        from Crypto.Hash import keccak as lib_keccak
        k = lib_keccak.new(digest_bits=256)
        k.update(data)
        return k.digest()
    except ImportError:
        die("Precisa de eth-utils ou pycryptodome. Instale: pip3 install eth-utils pycryptodome")


def _int_to_bytes(value: int) -> bytes:
    """Converte inteiro para bytes big-endian mínimos (sem prefixo RLP)."""
    if value == 0:
        return b""
    hex_str = hex(value)[2:]
    if len(hex_str) % 2:
        hex_str = "0" + hex_str
    raw = bytes.fromhex(hex_str)
    return raw.lstrip(b"\x00")


def _rlp_encode_item(item: bytes) -> bytes:
    """RLP-encode a single byte-string item."""
    if len(item) == 1 and item[0] < 0x80:
        return item
    elif len(item) < 56:
        return bytes([0x80 + len(item)]) + item
    else:
        return bytes([0xb7 + 1, len(item)]) + item


def _rlp_encode_list(items: list) -> bytes:
    """RLP-encode a list of raw byte strings."""
    payload = b"".join(
        _rlp_encode_item(i) if isinstance(i, bytes) else _rlp_encode_list(i)
        for i in items
    )
    if len(payload) < 56:
        return bytes([0xc0 + len(payload)]) + payload
    else:
        return bytes([0xf7 + 1, len(payload)]) + payload


def _rlp_encode_transaction(fields: list) -> bytes:
    """
    Codifica os 9 campos de transação Ethereum em RLP.
    Campos: nonce, gasPrice, gasLimit, to, value, data, v, r, s.
    Aceita ints (serão convertidos para bytes) e bytes.
    """
    encoded = []
    for f in fields:
        if isinstance(f, int):
            encoded.append(_int_to_bytes(f))
        elif isinstance(f, bytes):
            encoded.append(f)
        else:
            encoded.append(f)
    return _rlp_encode_list(encoded)


def rpc_call(url: str, method: str, params: list = None) -> dict:
    """Faz chamada JSON-RPC ao nó Besu."""
    payload = {
        "jsonrpc": "2.0",
        "method": method,
        "params": params or [],
        "id": 1,
    }
    try:
        r = requests.post(url, json=payload, timeout=10)
        r.raise_for_status()
        data = r.json()
        if "error" in data:
            raise Exception(f"Erro RPC: {data['error']}")
        return data["result"]
    except requests.exceptions.ConnectionError:
        die(f"Não foi possível conectar ao nó RPC em {url}. A rede está rodando?")
    except Exception as e:
        die(f"Falha na chamada RPC {method}: {e}")


def fetch_chain_id(rpc_url: str) -> int:
    """Obtém chainId do nó."""
    result = rpc_call(rpc_url, "eth_chainId")
    return int(result, 16)


def fetch_nonce(rpc_url: str, address: str) -> int:
    """Obtém nonce da conta."""
    result = rpc_call(rpc_url, "eth_getTransactionCount", [address, "pending"])
    return int(result, 16)


def fetch_gas_price(rpc_url: str) -> int:
    """Obtém gasPrice atual."""
    result = rpc_call(rpc_url, "eth_gasPrice")
    return int(result, 16)


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
    """
    Constrói e assina uma transação EIP-155.
    Retorna o hex da transação assinada (pronto para eth_sendRawTransaction).
    """
    if not HAS_COINCURVE:
        die("Precisa de coincurve. Instale: pip3 install coincurve")

    to_bytes = bytes.fromhex(to_addr[2:].lower())

    # 9 campos da transação EIP-155 (unsigned: chainId no campo v, r/s vazios)
    unsigned_fields = [
        nonce,           # 0: nonce
        gas_price,       # 1: gasPrice
        gas_limit,       # 2: gasLimit
        to_bytes,        # 3: to
        value,           # 4: value
        data,            # 5: data
        chain_id,        # 6: v (chainId para o hash de signing)
        b"",             # 7: r (vazio)
        b"",             # 8: s (vazio)
    ]

    if HAS_RLP:
        raw_unsigned = rlp_lib.encode(unsigned_fields)
    else:
        raw_unsigned = _rlp_encode_transaction(unsigned_fields)

    # Hash da transação
    tx_hash = keccak256(raw_unsigned)

    # Assinar com coincurve (RFC6979 deterministic)
    privkey = PrivateKey(bytes.fromhex(private_key_hex))
    sig_recoverable = privkey.sign_recoverable(tx_hash, hasher=None)
    # sig_recoverable: 65 bytes = r(32) + s(32) + rec_id(1)

    r = int.from_bytes(sig_recoverable[:32], "big")
    s_val = int.from_bytes(sig_recoverable[32:64], "big")
    rec_id = sig_recoverable[64]

    # EIP-2: low-s — se s > n/2, usar n-s e flipar rec_id
    n_secp = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
    if s_val > n_secp // 2:
        s_val = n_secp - s_val
        rec_id ^= 1

    # EIP-155: v = chain_id * 2 + 35 + rec_id
    v_eip155 = chain_id * 2 + 35 + rec_id

    # Montar transação assinada
    signed_fields = [
        nonce,
        gas_price,
        gas_limit,
        to_bytes,
        value,
        data,
        v_eip155,
        r,
        s_val,
    ]

    if HAS_RLP:
        raw_signed = rlp_lib.encode(signed_fields)
    else:
        raw_signed = _rlp_encode_transaction(signed_fields)

    return "0x" + raw_signed.hex()


# =============================================================================
# Main
# =============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Gerador de transações assinadas para o Plugin Permissioning Test Suite",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemplos:
  # Transação da conta ADMIN (permitida)
  python3 gen-signed-tx.py

  # Transação da conta UNAUTH (bloqueada)
  python3 gen-signed-tx.py --account unauth

  # Para outro endpoint RPC
  python3 gen-signed-tx.py --rpc-url http://localhost:9001

  # Especificar nonce e gas price manualmente (sem consultar nó)
  python3 gen-signed-tx.py --nonce 0 --gas-price 0

  # Saída JSON para uso em scripts
  python3 gen-signed-tx.py --json
        """,
    )
    parser.add_argument("--account", choices=["admin", "unauth"], default="admin",
                        help="Conta que assina (admin=permitida, unauth=bloqueada). Default: admin")
    parser.add_argument("--rpc-url", default=DEFAULT_RPC,
                        help=f"URL RPC do nó Besu. Default: {DEFAULT_RPC}")
    parser.add_argument("--nonce", type=int, default=None,
                        help="Nonce da transação (auto-detecta se omitido)")
    parser.add_argument("--gas-price", type=int, default=None,
                        help="Gas price em wei (auto-detecta se omitido)")
    parser.add_argument("--gas-limit", type=int, default=DEFAULT_GAS_LIMIT,
                        help=f"Gas limit. Default: {DEFAULT_GAS_LIMIT}")
    parser.add_argument("--value", type=int, default=1,
                        help="Valor em wei a enviar. Default: 1")
    parser.add_argument("--to", type=str, default=None,
                        help="Endereço de destino (default: self-transfer)")
    parser.add_argument("--json", action="store_true",
                        help="Saída em formato JSON")
    parser.add_argument("--chain-id", type=int, default=None,
                        help="Chain ID (auto-detecta se omitido)")
    args = parser.parse_args()

    # Selecionar conta
    if args.account == "admin":
        pk = ADMIN_PK
        addr = ADMIN_ADDR
    else:
        pk = UNAUTH_PK
        addr = UNAUTH_ADDR

    to_addr = args.to if args.to else addr  # self-transfer por padrão

    # Obter chain ID
    if args.chain_id is not None:
        chain_id = args.chain_id
    else:
        chain_id = fetch_chain_id(args.rpc_url)

    # Obter nonce
    if args.nonce is not None:
        nonce = args.nonce
    else:
        nonce = fetch_nonce(args.rpc_url, addr)

    # Obter gas price
    if args.gas_price is not None:
        gas_price = args.gas_price
    else:
        gas_price = fetch_gas_price(args.rpc_url)

    # Construir transação assinada
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
\033[1m  TRANSAÇÃO ASSINADA GERADA\033[0m
\033[92m{'='*70}\033[0m
  Conta:     {args.account.upper()} ({addr})
  Destino:   {to_addr}
  Chain ID:  {chain_id} (0x{chain_id:x})
  Nonce:     {nonce}
  Gas Price: {gas_price} wei ({gas_price/1e9:.1f} gwei)
  Gas Limit: {args.gas_limit}
  Valor:     {args.value} wei

\033[1m  Raw Transaction (eth_sendRawTransaction):\033[0m
\033[96m  {raw_tx}\033[0m

\033[90m  # Copie o hex acima para a variável da coleção Postman
  #   ADMIN_SIGNED_TX (para --account admin)
  #   UNAUTH_SIGNED_TX (para --account unauth)\033[0m
""")


if __name__ == "__main__":
    main()
