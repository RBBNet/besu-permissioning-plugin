#!/usr/bin/env python3
"""
Stress Test for Permissioning Plugin.
Phase 1: Generates transactions serially (without concurrency)
Phase 2: Sends transactions concurrently (measures actual plugin performance)
"""

import asyncio
import aiohttp
import json
import time
import sys
from dataclasses import dataclass, field
from typing import List, Tuple

@dataclass
class StressResult:
    total: int = 0
    permitted: int = 0
    denied: int = 0
    errors: int = 0
    durations: List[float] = field(default_factory=list)
    
@dataclass
class BenchmarkResult:
    generate_time: float = 0
    send_time: float = 0
    total_time: float = 0
    stress: StressResult = field(default_factory=StressResult)

ACCOUNTS = {
    "admin": "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    "unauth": "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
}

# Account to force block mining (drains gas, invalidates cache)
DRAINER_ACCOUNT = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC"

async def get_current_block(session: aiohttp.ClientSession, rpc_url: str) -> int:
    """Gets current block number."""
    async with session.post(rpc_url, json={
        "jsonrpc": "2.0",
        "method": "eth_blockNumber",
        "params": [],
        "id": 0
    }) as resp:
        data = await resp.json()
        return int(data.get("result", "0x0"), 16)

async def wait_for_new_block(session: aiohttp.ClientSession, rpc_url: str, 
                             current_block: int, timeout: float = 5.0) -> bool:
    """Waits for a new block to be mined."""
    start = time.time()
    while time.time() - start < timeout:
        new_block = await get_current_block(session, rpc_url)
        if new_block > current_block:
            return True
        await asyncio.sleep(0.1)
    return False

async def generate_transaction(rpc_url: str, account: str, nonce: int) -> str:
    """Generates a signed transaction with specific nonce."""
    proc = await asyncio.create_subprocess_exec(
        sys.executable, "postman/gen-signed-tx.py",
        "--account", account,
        "--rpc-url", rpc_url,
        "--nonce", str(nonce),
        "--json",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE
    )
    stdout, _ = await proc.communicate()
    if proc.returncode != 0:
        return None
    data = json.loads(stdout.decode())
    return data.get("signed_raw_tx")

async def get_nonce(session: aiohttp.ClientSession, rpc_url: str, account: str) -> int:
    """Gets current account nonce."""
    address = ACCOUNTS.get(account, account)
    async with session.post(rpc_url, json={
        "jsonrpc": "2.0",
        "method": "eth_getTransactionCount",
        "params": [address, "pending"],
        "id": 0
    }) as resp:
        data = await resp.json()
        return int(data.get("result", "0x0"), 16)

async def generate_all_transactions(rpc_url: str, account: str, 
                                    total_txs: int) -> List[str]:
    """Generates all transactions serially (no concurrency)."""
    print(f"\n📝 PHASE 1: Generating {total_txs} transactions...")
    start = time.time()
    
    async with aiohttp.ClientSession() as session:
        nonce = await get_nonce(session, rpc_url, account)
    
    print(f"   Initial nonce: {nonce}")
    
    transactions = []
    for i in range(total_txs):
        raw_tx = await generate_transaction(rpc_url, account, nonce + i)
        if raw_tx:
            transactions.append(raw_tx)
        else:
            print(f"   ⚠️  Error generating tx nonce={nonce + i}")
    
    elapsed = time.time() - start
    print(f"   ✅ Generated: {len(transactions)}/{total_txs} ({elapsed:.1f}s)")
    return transactions

async def send_single_transaction(session: aiohttp.ClientSession, url: str,
                                   raw_tx: str, tx_id: int, 
                                   result: StressResult, 
                                   semaphore: asyncio.Semaphore,
                                   no_cache: bool = False):
    """Sends a single transaction under semaphore control."""
    async with semaphore:
        start = time.time()
        
        # If no_cache, force invalidation by sending dummy tx and waiting for new block
        if no_cache:
            current_block = await get_current_block(session, url)
            # Send dummy tx (zero value, no side effects)
            dummy_tx = await generate_transaction(url, "admin", 999999999)
            if dummy_tx:
                await session.post(url, json={
                    "jsonrpc": "2.0",
                    "method": "eth_sendRawTransaction",
                    "params": [dummy_tx],
                    "id": 0
                }, timeout=aiohttp.ClientTimeout(total=5))
            # Wait for new block (invalidates cache)
            await wait_for_new_block(session, url, current_block, timeout=10.0)
        
        # Measure ONLY the RPC call duration (excluding block wait)
        tx_start = time.time()
        payload = {
            "jsonrpc": "2.0",
            "method": "eth_sendRawTransaction",
            "params": [raw_tx],
            "id": tx_id
        }
        try:
            async with session.post(url, json=payload, 
                                   timeout=aiohttp.ClientTimeout(total=10)) as resp:
                data = await resp.json()
        except Exception as e:
            data = {"error": {"message": str(e)}}
        
        tx_duration = time.time() - tx_start
        total_duration = time.time() - start
        
        # Store actual tx duration (without cache invalidation overhead)
        result.durations.append(tx_duration)
        result.total += 1
        
        if "error" in data:
            code = data["error"].get("code", 0)
            msg = data["error"].get("message", "")
            if code == -32007 or "not authorized" in msg.lower():
                result.denied += 1
            else:
                result.errors += 1
        elif "result" in data:
            result.permitted += 1

async def send_all_transactions(rpc_urls: List[str], transactions: List[str],
                                 concurrency: int, no_cache: bool = False) -> StressResult:
    """Sends all transactions concurrently."""
    cache_msg = " (NO CACHE)" if no_cache else ""
    print(f"\n🚀 PHASE 2: Sending {len(transactions)} transactions{cache_msg} (concurrency={concurrency})...")
    start = time.time()
    
    result = StressResult()
    semaphore = asyncio.Semaphore(concurrency)
    
    async with aiohttp.ClientSession() as session:
        tasks = []
        for i, raw_tx in enumerate(transactions):
            url = rpc_urls[i % len(rpc_urls)]
            tasks.append(send_single_transaction(
                session, url, raw_tx, i + 1, result, semaphore, no_cache
            ))
        await asyncio.gather(*tasks)
    
    result.durations = result.durations  # ensure list exists
    return result

async def run_benchmark(rpc_urls: List[str], account: str,
                        total_txs: int, concurrency: int,
                        label: str, no_cache: bool = False) -> BenchmarkResult:
    """Executes complete benchmark: generation + sending."""
    cache_msg = " [NO CACHE]" if no_cache else ""
    print(f"\n{'='*60}")
    print(f"🔥 BENCHMARK: {label}{cache_msg}")
    print(f"   Total: {total_txs} | Concurrency: {concurrency}")
    print(f"   Nodes: {len(rpc_urls)} ({', '.join(rpc_urls)})")
    print(f"{'='*60}")
    
    total_start = time.time()
    
    # Phase 1: Generate transactions serially
    gen_start = time.time()
    transactions = await generate_all_transactions(rpc_urls[0], account, total_txs)
    gen_time = time.time() - gen_start
    
    if not transactions:
        print("❌ No transactions generated")
        return BenchmarkResult()
    
    # Phase 2: Send transactions concurrently
    send_start = time.time()
    result = await send_all_transactions(rpc_urls, transactions, concurrency, no_cache)
    send_time = time.time() - send_start
    
    total_time = time.time() - total_start
    
    # Calculate metrics
    avg_duration = sum(result.durations) / len(result.durations) if result.durations else 0
    tx_per_sec = result.total / send_time if send_time > 0 else 0
    
    print(f"\n📊 RESULTS ({total_time:.1f}s total):")
    print(f"   ⏱️  Generation: {gen_time:.1f}s ({gen_time/total_time*100:.0f}%)")
    print(f"   ⏱️  Sending:    {send_time:.1f}s ({send_time/total_time*100:.0f}%)")
    print(f"   ─────────────────────────────────")
    print(f"   ✅ Permitted:  {result.permitted}")
    print(f"   ❌ Denied:     {result.denied}")
    print(f"   ⚠️  Errors:     {result.errors}")
    print(f"   ─────────────────────────────────")
    print(f"   ⏱️  Average latency: {avg_duration*1000:.0f}ms per tx")
    print(f"   🚀 Observed throughput: {tx_per_sec:.1f} tx/s")
    
    return BenchmarkResult(
        generate_time=gen_time,
        send_time=send_time,
        total_time=total_time,
        stress=result
    )

async def main():
    import argparse
    
    parser = argparse.ArgumentParser(
        description="Permissioning Plugin Stress Test (Accurate Benchmark)")
    parser.add_argument("--rpc", nargs="+", default=["http://localhost:9005"],
                        help="RPC URLs (default: http://localhost:9005)")
    parser.add_argument("--count", type=int, default=100,
                        help="Total transactions (default: 100)")
    parser.add_argument("--concurrency", type=int, default=10,
                        help="Concurrent requests (default: 10)")
    parser.add_argument("--account", choices=["admin", "unauth"], default="admin",
                        help="Account for testing (default: admin)")
    parser.add_argument("--no-cache", action="store_true",
                        help="Forces cache invalidation (sends dummy tx between each tx)")
    parser.add_argument("--json", action="store_true",
                        help="JSON output")
    
    args = parser.parse_args()
    
    # Check connection
    print("🔍 Checking RPC node connection...")
    await asyncio.sleep(1)  # Wait for system stabilization
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(args.rpc[0], json={
                "jsonrpc": "2.0", "method": "eth_blockNumber", "params": [], "id": 0
            }, timeout=aiohttp.ClientTimeout(total=10)) as resp:
                data = await resp.json()
                block = int(data.get("result", "0x0"), 16)
                print(f"   ✅ Connected. Current block: {block}")
    except Exception as e:
        print(f"   ❌ Connection error: {e}")
        sys.exit(1)
    
    # Executar benchmark
    benchmark = await run_benchmark(
        rpc_urls=args.rpc,
        account=args.account,
        total_txs=args.count,
        concurrency=args.concurrency,
        label=f"Conta {args.account.upper()}",
        no_cache=args.no_cache
    )
    
    if args.json:
        output = {
            "generate_time_s": benchmark.generate_time,
            "send_time_s": benchmark.send_time,
            "total_time_s": benchmark.total_time,
            "total": benchmark.stress.total,
            "permitted": benchmark.stress.permitted,
            "denied": benchmark.stress.denied,
            "errors": benchmark.stress.errors,
            "avg_latency_ms": (sum(benchmark.stress.durations) / len(benchmark.stress.durations) * 1000) if benchmark.stress.durations else 0,
            "throughput_tps": benchmark.stress.total / benchmark.send_time if benchmark.send_time > 0 else 0
        }
        print(json.dumps(output, indent=2))

if __name__ == "__main__":
    asyncio.run(main())
