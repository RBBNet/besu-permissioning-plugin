#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Dynamic Plugin Sandbox Test Orchestrator
Manages the lifecycle of customizable Besu networks, supporting
multiple node versions, selective activation of the permissioning plugin,
and on-chain contract verification.

Evolution v2: Support for GEN1/GEN2 contracts pre-deployed in genesis,
on-chain verification, and configurable automatic setup.
"""

import os
import sys
import json
import shutil
import argparse
import subprocess
import time
from datetime import datetime

# ANSI terminal colors
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
BLUE = "\033[94m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RUN_DIR = os.path.join(BASE_DIR, "run-data")
TEMPLATES_DIR = os.path.join(BASE_DIR, "templates")
SCRIPTS_DIR = os.path.join(BASE_DIR, "scripts")
LOGS_DIR = os.path.join(BASE_DIR, "logs")

# Standard contract addresses pre-deployed in genesis (genesis-evolution.json)
# Admin + AccountRules + NodeRules pre-configured with storage:
#   - Admin registered in Ingresses ("admin" key -> Admin contract)
#   - Rules registered in Ingresses ("rules" key -> AccountRules/NodeRules)
#   - Admin (0xf39Fd6e5...) in allowlist of Admin contract and AccountRules
DEFAULT_CONTRACTS = {
    "account_ingress":      "0x0000000000000000000000000000000000008888",
    "node_ingress":         "0x0000000000000000000000000000000000009999",
    "admin_contract":       "0x181a92c9b76ab7271a03b640cc172e75a0dc3484",
    "account_rules":        "0x0e9e81bb09cdd55b607373e89e3154354a925b7d",
    "node_rules":           "0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616",
    "admin_addr":           "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    # Standard Anvil/Hardhat public test key — DO NOT USE IN PRODUCTION
    "admin_pk":             "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    "unauth_addr":          "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
    # Standard Anvil/Hardhat public test key — DO NOT USE IN PRODUCTION
    "unauth_pk":            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
}

def log_info(msg):
    print(f"{BLUE}[INFO]{RESET} {msg}")

def log_success(msg):
    print(f"{GREEN}[SUCCESS]{RESET} {msg}")

def log_warning(msg):
    print(f"{YELLOW}[WARNING]{RESET} {msg}")

def log_error(msg):
    print(f"{RED}[ERROR]{RESET} {msg}")

def check_docker():
    """Validates that Docker and docker-compose are installed and running."""
    try:
        subprocess.run(["docker", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    except Exception:
        log_error("Docker not found! Make sure Docker is installed and available in your PATH.")
        sys.exit(1)

    try:
        subprocess.run(["docker", "compose", "version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        return "docker compose"
    except Exception:
        try:
            subprocess.run(["docker-compose", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            return "docker-compose"
        except Exception:
            log_error("Command 'docker compose' or 'docker-compose' not found!")
            sys.exit(1)

def build_plugin_if_needed(plugin_path):
    """Verifies that the plugin JAR exists, or attempts to compile it."""
    abs_plugin_path = os.path.abspath(os.path.join(BASE_DIR, plugin_path))
    if os.path.exists(abs_plugin_path):
        log_success(f"Plugin found at: {abs_plugin_path}")
        return abs_plugin_path

    log_warning(f"Plugin JAR not found at: {abs_plugin_path}")
    plugin_project_dir = os.path.abspath(os.path.join(BASE_DIR, "../.."))

    if os.path.exists(os.path.join(plugin_project_dir, "gradlew")):
        print(f"\n{YELLOW}Attempting to build plugin automatically using Gradle...{RESET}")
        try:
            subprocess.run(["./gradlew", "shadowJar"], cwd=plugin_project_dir, check=True)
            candidate = os.path.join(plugin_project_dir, "build/libs/besu-plugin-permissioning.jar")
            if os.path.exists(candidate):
                log_success("Plugin compiled successfully!")
                return candidate
        except Exception as e:
            log_error(f"Failed to build plugin: {e}")

    log_error("Cannot proceed without compiled plugin JAR.")
    log_info("Please compile the plugin by running './gradlew shadowJar' in the plugin root directory.")
    sys.exit(1)

def load_config(config_path):
    """Loads network configuration JSON and merges default contracts."""
    if not os.path.exists(config_path):
        log_error(f"Configuration file not found: {config_path}")
        sys.exit(1)
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
    except Exception as e:
        log_error(f"Error parsing JSON configuration file: {e}")
        sys.exit(1)

    # Merge default contracts (scenario config overrides defaults)
    if "contracts" not in config:
        config["contracts"] = {}
    for k, v in DEFAULT_CONTRACTS.items():
        if k not in config["contracts"]:
            config["contracts"][k] = v

    return config

def rpc_call(rpc_url, method, params=None):
    """Executes a JSON-RPC call and returns the parsed result."""
    import urllib.request
    payload = {"jsonrpc": "2.0", "method": method, "params": params or [], "id": 1}
    data = json.dumps(payload).encode("utf-8")
    try:
        req = urllib.request.Request(rpc_url, data=data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            return result.get("result")
    except Exception:
        return None

def wait_for_blocks(config, timeout=120):
    """Waits until at least one node is producing blocks.
    Prefers nodes without plugin (for deployment), then any node with RPC."""
    nos = config.get("nodes", []) or config.get("nos", [])

    # Priority: nodes without plugin first (for deployment), then any RPC node
    deploy_nodes = [n for n in nos if ("rpc_port" in n or "porta_rpc" in n) and not (n.get("use_plugin") if "use_plugin" in n else n.get("usar_plugin", False))]
    plugin_nodes = [n for n in nos if ("rpc_port" in n or "porta_rpc" in n) and (n.get("use_plugin") if "use_plugin" in n else n.get("usar_plugin", False))]
    check_order = deploy_nodes + plugin_nodes

    if not check_order:
        check_order = [n for n in nos if ("rpc_port" in n or "porta_rpc" in n)]

    log_info(f"Waiting for network to start (timeout: {timeout}s)...")
    start = time.time()

    while time.time() - start < timeout:
        for no in check_order:
            port = no.get("rpc_port") or no.get("porta_rpc")
            name = no.get("name") or no.get("nome")
            use_plugin = no.get("use_plugin") if "use_plugin" in no else no.get("usar_plugin", False)
            rpc_url = f"http://localhost:{port}"
            block = rpc_call(rpc_url, "eth_blockNumber")
            if block and block != "0x0":
                elapsed = int(time.time() - start)
                plugin_status = "without plugin" if not use_plugin else "with plugin"
                log_success(f"Network ready! Block {block} on {name} ({plugin_status}, port {port}) after {elapsed}s")
                return True, rpc_url, block
        time.sleep(3)

    log_warning(f"Timeout ({timeout}s): network produced no blocks.")
    log_warning("  This is expected in Fail-Close scenarios (FC-01, FC-02) where the plugin blocks everything.")
    return False, None, "0x0"

def verify_contracts(rpc_url, contracts, config):
    """Verifies that pre-deployed contracts are accessible on-chain."""
    c = contracts
    nos = config.get("nos", []) or config.get("nodes", [])

    # Determine if this is a fail-close scenario (empty or invalid ingress)
    primeiro_no = nos[0] if nos else {}
    ingress = primeiro_no.get("ingress_address", "")

    # In fail-close scenarios, network might not produce blocks
    if not rpc_url:
        if ingress == "":
            log_info("Scenario FC-01 (no ingress): on-chain checks not applicable (fail-close active).")
        elif ingress not in [c.get("account_ingress"), c.get("node_ingress")]:
            log_info("Scenario FC-02 (invalid ingress): on-chain checks not applicable (fail-close active).")
        return True  # Expected behavior, not a failure

    log_info("Verifying contracts on-chain...")
    all_ok = True

    to_check = []
    if c.get("account_ingress"):
        to_check.append((c["account_ingress"], "Account Ingress"))
    if c.get("node_ingress"):
        to_check.append((c["node_ingress"], "Node Ingress"))
    if c.get("admin_contract"):
        to_check.append((c["admin_contract"], "Admin"))
    if c.get("account_rules"):
        to_check.append((c["account_rules"], "AccountRules"))
    if c.get("node_rules"):
        to_check.append((c["node_rules"], "NodeRules"))

    if not to_check:
        log_info("  No contracts configured for verification.")
        return True

    for addr, label in to_check:
        code = rpc_call(rpc_url, "eth_getCode", [addr, "latest"])
        if code and len(code) > 4:
            log_success(f"  ✓ {label} ({addr[:10]}...) — {len(code)} chars bytecode")
        else:
            log_warning(f"  ⚠ {label} ({addr[:10]}...) — not found (may not be deployed yet)")
            all_ok = False

    # Verify if Admin is authorized in AccountRules (if deployed)
    if c.get("account_rules") and c.get("admin_addr"):
        permitted = rpc_call(rpc_url, "eth_call", [
            {"to": c["account_rules"],
             "data": "0xfe9fbb80" + c["admin_addr"][2:].zfill(64)},
            "latest"
        ])
        if permitted and permitted != "0x" and int(permitted, 16) == 1:
            log_success(f"  ✓ Admin ({c['admin_addr'][:10]}...) is PERMITTED in AccountRules")
        else:
            log_warning(f"  ⚠ Admin ({c['admin_addr'][:10]}...) NOT in allowlist (may be expected)")

    return all_ok

def generate_keys_and_get_bootnode(nos, run_dir, compose_cmd):
    """Generates deterministic keys for each node and exports the bootnode public key."""
    bootnode_pubkey = None
    bootnode_service_name = None

    for i, no in enumerate(nos):
        no_name = no.get("name") or no.get("nome")
        if not no_name:
            no_name = f"node-{i + 1}"
        no_dir = os.path.join(run_dir, "volumes", no_name)
        os.makedirs(no_dir, exist_ok=True)

        private_key = no.get("private_key")
        if not private_key:
            private_key = f"{i + 1:064x}"

        key_file_path = os.path.join(no_dir, "key")
        with open(key_file_path, 'w', encoding='utf-8') as f:
            f.write(private_key)

        tipo = no.get("role") or no.get("type") or no.get("tipo", "")
        if tipo == "bootnode":
            bootnode_service_name = no_name
            pubkey_file = os.path.join(no_dir, "key.pub")

            log_info(f"Exporting bootnode public key ({no_name})...")
            versao = no.get("besu_version") or no.get("versao_besu", "25.12.0")
            image_name = f"hyperledger/besu:{versao}"
            try:
                subprocess.run([
                    "docker", "run", "--rm",
                    "-v", f"{no_dir}:/key-dir",
                    image_name,
                    "public-key", "export",
                    "--node-private-key-file=/key-dir/key",
                    "--to=/key-dir/key.pub"
                ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

                if os.path.exists(pubkey_file):
                    with open(pubkey_file, 'r', encoding='utf-8') as f:
                        bootnode_pubkey = f.read().strip()
                        if bootnode_pubkey.startswith("0x"):
                            bootnode_pubkey = bootnode_pubkey[2:]
                    log_success(f"Bootnode public key retrieved: {bootnode_pubkey[:10]}...")
            except Exception as e:
                log_error(f"Error exporting bootnode public key: {e}")
                log_warning("Using fallback static public key corresponding to private key '1'")
                bootnode_pubkey = "7a6d8011244d2d9a65f0992f2272de9f3c7fa6e0b741004a43eb37651a541603ba05b0c950a7c490a1608888b15a6b4d238ff944bacb478cbed5efcae784d7bf"

    return bootnode_pubkey, bootnode_service_name

def generate_docker_compose(config, plugin_path, bootnode_pubkey, bootnode_service):
    """Generates the docker-compose.yml file dynamically with zero-gas and contract support."""
    nome_rede = config.get("network_name") or config.get("nome_rede", "plugin-testnet")
    nos = config.get("nodes") or config.get("nos", [])

    compose = {
        "version": "3.4",
        "networks": {
            "plugin-net": {
                "driver": "bridge",
                "name": f"{nome_rede}-net"
            }
        },
        "services": {}
    }

    for no in nos:
        no_name = no.get("name") or no.get("nome")
        tipo = no.get("role") or no.get("type") or no.get("tipo", "")
        versao = no.get("besu_version") or no.get("versao_besu", "latest")
        usar_plugin = no.get("use_plugin") if "use_plugin" in no else no.get("usar_plugin", False)

        image = f"hyperledger/besu:{versao}" if versao != "latest" else "hyperledger/besu:latest"

        volumes = [
            f"./volumes/{no_name}:/var/lib/besu",
            "./genesis.json:/var/lib/besu/genesis.json:ro",
            "./log.xml:/var/lib/besu/log.xml:ro",
            f"./volumes/{no_name}/key:/var/lib/besu/key:ro"
        ]

        if usar_plugin and plugin_path:
            volumes.append(f"{plugin_path}:/opt/besu/plugins/permissioning-plugin.jar:ro")

        cmd_args = [
            "--genesis-file=/var/lib/besu/genesis.json",
            "--data-path=/var/lib/besu",
            "--node-private-key-file=/var/lib/besu/key",
            "--data-storage-format=FOREST",
            f"--sync-min-peers={config.get('sync_min_peers', 0)}",
            "--Xdns-enabled=true",
            "--Xdns-update-enabled=true"
        ]

        if tipo in ["bootnode", "validator"]:
            cmd_args.append("--rpc-http-enabled=true")
            cmd_args.append("--rpc-http-api=ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            cmd_args.append("--rpc-http-host=0.0.0.0")
            cmd_args.append("--rpc-http-cors-origins=*")
            cmd_args.append('--host-allowlist="*"')
        elif tipo == "rpc":
            cmd_args.append("--rpc-http-enabled=true")
            cmd_args.append("--rpc-http-api=ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            cmd_args.append("--rpc-http-host=0.0.0.0")
            cmd_args.append("--rpc-http-cors-origins=*")
            cmd_args.append('--host-allowlist="*"')
        elif tipo == "rogue":
            cmd_args.append("--rpc-http-enabled=false")

        if tipo != "bootnode" and bootnode_pubkey and bootnode_service:
            cmd_args.append(f"--bootnodes=enode://{bootnode_pubkey}@{bootnode_service}:30303")

        cmd_args.append("--metrics-enabled=true")
        cmd_args.append("--metrics-host=0.0.0.0")
        cmd_args.append("--metrics-port=9545")

        # Base environment
        env = {
            "LOG4J_CONFIGURATION_FILE": "/var/lib/besu/log.xml",
            "BESU_MIN_GAS_PRICE": "0",
        }

        # Disable balance check for zero-gas tx (accounts without ETH can transact)
        if usar_plugin:
            env["BESU_TX_POOL_ENABLE_BALANCE_CHECK"] = "false"

        # Permissioning: supports 3 modes
        #   "plugin"  — usar_plugin=true + ingress (default when usar_plugin=true)
        #   "native"  — usar_plugin=false + ingress (Besu native, no plugin JAR)
        #   "none"    — usar_plugin=false + no ingress (transparent)
        perm_mode = no.get("permissioning_mode",
                   "plugin" if usar_plugin else "none")

        if perm_mode in ("plugin", "native"):
            ingress = no.get("ingress_address")
            node_ingress = no.get("node_ingress_address")

            if ingress:
                env["BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS"] = ingress
            if node_ingress:
                env["BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS"] = node_ingress

            acc_enabled = no.get("account_permissions_enabled",
                         config.get("account_permissions_enabled", True))
            node_enabled = no.get("node_permissions_enabled",
                          config.get("node_permissions_enabled", False))

            env["BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ENABLED"] = str(acc_enabled).lower()
            env["BESU_PERMISSIONS_NODES_CONTRACT_ENABLED"] = str(node_enabled).lower()

        ports = []
        rpc_p = no.get("rpc_port") or no.get("porta_rpc")
        if rpc_p:
            ports.append(f"{rpc_p}:8545")
        metrics_p = no.get("metrics_port") or no.get("porta_metrics")
        if metrics_p:
            ports.append(f"{metrics_p}:9545")

        service_cfg = {
            "image": image,
            "container_name": f"{nome_rede}-{no_name}",
            "environment": env,
            "volumes": volumes,
            "ports": ports,
            "command": cmd_args,
            "networks": ["plugin-net"],
            "restart": "unless-stopped",
            "logging": {
                "driver": "json-file",
                "options": {
                    "max-size": "10m",
                    "max-file": "3"
                }
            }
        }

        compose["services"][no_name] = service_cfg

    compose_path = os.path.join(RUN_DIR, "docker-compose.yml")
    with open(compose_path, 'w', encoding='utf-8') as f:
        json.dump(compose, f, indent=2)

    return compose_path

def setup_files(config):
    """Prepares genesis.json and log.xml files."""
    os.makedirs(RUN_DIR, exist_ok=True)

    genesis_template = os.path.join(TEMPLATES_DIR, "genesis.json")
    genesis_dest = os.path.join(RUN_DIR, "genesis.json")
    if os.path.exists(genesis_template):
        shutil.copy2(genesis_template, genesis_dest)
        # Check pre-deployed contracts in genesis
        try:
            with open(genesis_dest, 'r') as f:
                gen = json.load(f)
            alloc_addrs = list(gen.get("alloc", {}).keys())
            contratos_no_genesis = []
            for addr in alloc_addrs:
                data = gen["alloc"][addr]
                if data.get("code") and len(data.get("code", "")) > 10:
                    comment = data.get("comment", "")
                    contratos_no_genesis.append(f"  {addr} — {comment}" if comment else f"  {addr}")
            if contratos_no_genesis:
                log_success(f"Genesis loaded with {len(contratos_no_genesis)} pre-deployed contracts:")
                for c in contratos_no_genesis:
                    print(f"    {c}")
        except Exception:
            pass
    else:
        log_error(f"Genesis template file not found at: {genesis_template}")
        sys.exit(1)

    log_template = os.path.join(TEMPLATES_DIR, "log.xml")
    log_dest = os.path.join(RUN_DIR, "log.xml")
    if os.path.exists(log_template):
        shutil.copy2(log_template, log_dest)

def capture_logs(config, run_tag=None):
    """Captures Docker logs from all containers and saves them in logs/<timestamp>/."""
    if not run_tag:
        run_tag = datetime.now().strftime("%Y%m%d-%H%M%S")
    log_dir = os.path.join(LOGS_DIR, run_tag)
    os.makedirs(log_dir, exist_ok=True)

    nome_rede = config.get("network_name") or config.get("nome_rede", "plugin-testnet")
    nos = config.get("nodes") or config.get("nos", [])

    log_info(f"Capturing Docker logs in: {log_dir}")

    for no in nos:
        no_name = no.get("name") or no.get("nome")
        container_name = f"{nome_rede}-{no_name}"
        log_file = os.path.join(log_dir, f"{no_name}.log")

        try:
            result = subprocess.run(
                ["docker", "logs", container_name],
                capture_output=True, text=True, timeout=30
            )
            with open(log_file, 'w', encoding='utf-8') as f:
                f.write(result.stdout)
            lines = result.stdout.count('\n')
            log_success(f"  {no_name}: {lines} lines → {log_file}")
        except subprocess.TimeoutExpired:
            log_warning(f"  {no_name}: timeout (logs too large), capturing tail...")
            try:
                result = subprocess.run(
                    ["docker", "logs", "--tail", "5000", container_name],
                    capture_output=True, text=True, timeout=15
                )
                with open(log_file, 'w', encoding='utf-8') as f:
                    f.write(f"# TRUNCATED LOG (last 5000 lines) — container: {container_name}\n")
                    f.write(result.stdout)
                log_warning(f"  {no_name}: {result.stdout.count(chr(10))} lines (truncated) → {log_file}")
            except Exception:
                log_error(f"  {no_name}: total failure capturing logs")
        except Exception as e:
            log_error(f"  {no_name}: error capturing logs — {e}")

    # Save run metadata
    meta = {
        "run_tag": run_tag,
        "timestamp": datetime.now().isoformat(),
        "config": config.get("network_name") or config.get("nome_rede", "unknown"),
        "description": config.get("description") or config.get("descricao", ""),
        "nodes": [{"name": n.get("name") or n.get("nome"),
                    "type": n.get("role") or n.get("type") or n.get("tipo"),
                    "besu_version": n.get("besu_version") or n.get("versao_besu"),
                    "use_plugin": n.get("use_plugin") if "use_plugin" in n else n.get("usar_plugin"),
                    "rpc_port": n.get("rpc_port") or n.get("porta_rpc")}
                  for n in nos],
        "contracts": config.get("contracts", {}),
    }
    meta_path = os.path.join(log_dir, "run-metadata.json")
    with open(meta_path, 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=2, default=str)
    log_info(f"  Metadata: {meta_path}")

    return log_dir

def run_setup_script(config, rpc_url):
    """Executes post-genesis setup script if configured in scenario."""
    setup_script = config.get("setup_script")
    if not setup_script:
        return True

    script_path = os.path.join(BASE_DIR, setup_script)
    if not os.path.exists(script_path):
        log_warning(f"Setup script not found: {script_path}")
        return False

    log_info(f"Executing setup script: {setup_script}")

    c = config.get("contracts", {})
    env = os.environ.copy()
    env.update({
        "RPC_URL": rpc_url or "",
        "ACCOUNT_INGRESS": c.get("account_ingress", ""),
        "NODE_INGRESS": c.get("node_ingress", ""),
        "ADMIN_CONTRACT": c.get("admin_contract", ""),
        "ACCOUNT_RULES": c.get("account_rules", ""),
        "NODE_RULES": c.get("node_rules", ""),
        "ADMIN_PK": c.get("admin_pk", ""),
        "ADMIN_ADDR": c.get("admin_addr", ""),
        "UNAUTH_PK": c.get("unauth_pk", ""),
        "UNAUTH_ADDR": c.get("unauth_addr", ""),
    })

    try:
        result = subprocess.run(
            ["bash", script_path],
            env=env,
            cwd=BASE_DIR,
            capture_output=True,
            text=True,
            timeout=120
        )
        if result.stdout:
            print(result.stdout)
        if result.returncode == 0:
            log_success("Setup script completed.")
            return True
        else:
            log_error(f"Setup script failed (exit={result.returncode})")
            if result.stderr:
                log_error(result.stderr[:500])
            return False
    except subprocess.TimeoutExpired:
        log_error("Setup script timed out (120s)")
        return False
    except Exception as e:
        log_error(f"Error executing setup script: {e}")
        return False

def print_endpoints_summary(config):
    """Displays endpoint, contract, and test account summary on console."""
    nos = config.get("nodes") or config.get("nos", [])
    c = config.get("contracts", {})
    nome_rede = config.get("network_name") or config.get("nome_rede", "plugin-testnet")

    print("\n" + "="*80)
    print(f" {BOLD}{GREEN}THE BLOCKCHAIN NETWORK {nome_rede} IS UP AND RUNNING!{RESET}")
    print("="*80)

    print(f"\n{BOLD}{CYAN}--- RPC ENDPOINTS (Configure in POSTMAN/Newman) ---{RESET}")
    for no in nos:
        no_name = no.get("name") or no.get("nome")
        role = (no.get("role") or no.get("type") or no.get("tipo", "")).upper()
        ver = no.get("besu_version") or no.get("versao_besu", "latest")
        use_plugin = no.get("use_plugin") if "use_plugin" in no else no.get("usar_plugin", False)
        plugin_status = f"{GREEN}Active{RESET}" if use_plugin else f"{RED}Inactive{RESET}"
        ingress = no.get("ingress_address", "N/A")

        rpc_p = no.get("rpc_port") or no.get("porta_rpc")
        metrics_p = no.get("metrics_port") or no.get("porta_metrics")
        rpc_url = f"http://localhost:{rpc_p}" if rpc_p else "N/A"
        metrics_url = f"http://localhost:{metrics_p}/metrics" if metrics_p else "N/A"

        print(f"🔹 {BOLD}{no_name}{RESET} [{role} | Besu: {ver} | Plugin: {plugin_status}]")
        print(f"   ↳ {BOLD}RPC URL:{RESET}     {CYAN}{rpc_url}{RESET}")
        print(f"   ↳ {BOLD}Metrics:{RESET}     {metrics_url}")
        if use_plugin:
            print(f"   ↳ {BOLD}Ingress:{RESET}     {ingress}")

    print(f"\n{BOLD}{CYAN}--- SMART CONTRACTS (Pre-deployed in Genesis) ---{RESET}")
    print(f"📍 {BOLD}Account Ingress:{RESET}    {c.get('account_ingress', 'N/A')}")
    print(f"📍 {BOLD}Node Ingress:{RESET}       {c.get('node_ingress', 'N/A')}")
    print(f"📍 {BOLD}Admin (proxy):{RESET}      {c.get('admin_contract', 'N/A')}")
    print(f"📍 {BOLD}AccountRules (GEN1):{RESET} {c.get('account_rules', 'N/A')}")
    print(f"📍 {BOLD}NodeRules (GEN1):{RESET}    {c.get('node_rules', 'N/A')}")

    print(f"\n{BOLD}{CYAN}--- TEST KEYS AND ACCOUNTS ---{RESET}")
    print(f"🔑 {BOLD}PERMITTED ACCOUNT (ADMIN):{RESET}")
    print(f"   ↳ Address: {GREEN}{c.get('admin_addr', 'N/A')}{RESET} (Balance: 100k ETH)")
    print(f"   ↳ Private Key: {c.get('admin_pk', 'N/A')}")
    print(f"🔑 {BOLD}BLOCKED ACCOUNT (UNAUTH):{RESET}")
    print(f"   ↳ Address: {RED}{c.get('unauth_addr', 'N/A')}{RESET}")
    print(f"   ↳ Private Key: {c.get('unauth_pk', 'N/A')}")

    # Permissioning status
    primeiro_no = nos[0] if nos else {}
    ingress = primeiro_no.get("ingress_address", "")
    if ingress == "":
        perm_status = f"{RED}FAIL-CLOSE — All transactions BLOCKED (no Ingress configured){RESET}"
    elif ingress not in [c.get("account_ingress", ""), c.get("node_ingress", "")]:
        perm_status = f"{RED}FAIL-CLOSE — Invalid/non-existent Ingress ({ingress}){RESET}"
    else:
        perm_status = f"{GREEN}ACTIVE — Contracts registered, allowlists enforced{RESET}"

    print(f"\n{BOLD}{CYAN}--- PERMISSIONING STATUS ---{RESET}")
    print(f"🔒 {perm_status}")

    print(f"\n{BOLD}{YELLOW}--- TEST INSTRUCTIONS ---{RESET}")
    print("1. Import the Postman collection in 'test-suite/postman/' into Postman.")
    print("2. Set the node URL (e.g. http://localhost:9005 for rpc-node-1).")
    print("3. Run tests PF-01 (ADMIN allowed) and PF-02 (UNAUTH rejected).")
    print("4. Monitor permissioning logs:")
    print(f"   {CYAN}docker logs -f {nome_rede}-rpc-node-1 | grep -E 'Permission check|Sender'{RESET}")
    print("="*80 + "\n")

def start_network(compose_path, compose_cmd):
    """Executes docker-compose up."""
    log_info("Starting Besu network Docker containers...")
    try:
        cmd = compose_cmd.split() + ["-f", compose_path, "up", "-d"]
        subprocess.run(cmd, check=True)
        log_success("Containers created and started successfully.")
    except Exception as e:
        log_error(f"Error starting Docker network: {e}")
        sys.exit(1)

def stop_network(compose_path, compose_cmd):
    """Stops and removes the network and containers."""
    log_info("Stopping and removing Docker containers...")
    if not os.path.exists(compose_path):
        log_error(f"docker-compose file not found at: {compose_path}")
        return

    try:
        cmd = compose_cmd.split() + ["-f", compose_path, "down", "-v"]
        subprocess.run(cmd, check=True)
        log_success("Network stopped and container volumes cleaned.")

        volumes_dir = os.path.join(RUN_DIR, "volumes")
        if os.path.exists(volumes_dir):
            try:
                shutil.rmtree(volumes_dir)
                log_info("Local volume directories removed.")
            except Exception as e:
                log_warning(f"Could not remove all local volume directories: {e}")
                log_info("Clean manually: sudo rm -rf test-suite/run-data/volumes")
    except Exception as e:
        log_error(f"Error stopping Docker network: {e}")

def main():
    parser = argparse.ArgumentParser(
        description="Dynamic Besu + Permissioning Test Orchestrator (v2)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  ./run.sh -c configs/scenario-full-network.json -a start
  ./run.sh -c configs/scenario-full-network.json -a start --monitoring
  ./run.sh -c configs/scenario-failclose-no-ingress.json -a start --skip-verify
  ./run.sh -c configs/scenario-full-network.json -a stop
  ./run.sh -a status
        """
    )
    parser.add_argument("--config", "-c", default="configs/scenario-full-network.json",
                        help="Path to network configuration JSON (default: configs/scenario-full-network.json)")
    parser.add_argument("--action", "-a", choices=["start", "stop", "status"], default="start",
                        help="Action to perform on network (default: start)")
    parser.add_argument("--skip-verify", action="store_true",
                        help="Skip on-chain contract verification after startup")
    parser.add_argument("--skip-setup", action="store_true",
                        help="Skip post-genesis setup script execution")
    parser.add_argument("--timeout", type=int, default=120,
                        help="Timeout in seconds to wait for blocks (default: 120)")
    parser.add_argument("--monitoring", "-m", action="store_true",
                        help="Start monitoring stack (Prometheus + Grafana)")

    args = parser.parse_args()

    compose_cmd = check_docker()

    config_path = os.path.join(BASE_DIR, args.config)
    config = load_config(config_path)

    compose_path = os.path.join(RUN_DIR, "docker-compose.yml")

    if args.action == "stop":
        # Stop monitoring if present
        monitoring_dir = os.path.join(BASE_DIR, "monitoring")
        if os.path.exists(os.path.join(monitoring_dir, "monitoring.sh")):
            subprocess.run([os.path.join(monitoring_dir, "monitoring.sh"), "stop"], 
                         capture_output=True)
        
        stop_network(compose_path, compose_cmd)
        sys.exit(0)

    elif args.action == "status":
        if os.path.exists(compose_path):
            cmd = compose_cmd.split() + ["-f", compose_path, "ps"]
            subprocess.run(cmd)
        else:
            log_warning("No active network currently configured.")
        
        # Show monitoring status
        monitoring_dir = os.path.join(BASE_DIR, "monitoring")
        if os.path.exists(os.path.join(monitoring_dir, "monitoring.sh")):
            print(f"\n{BOLD}{CYAN}--- MONITORING ---{RESET}")
            subprocess.run([os.path.join(monitoring_dir, "monitoring.sh"), "status"])
        
        sys.exit(0)

    elif args.action == "start":
        print(f"\n{BOLD}{BLUE}====================================================================={RESET}")
        print(f"{BOLD}{BLUE}⚙️  STARTING NETWORK PREPARATION: {config.get('nome_rede') or config.get('network_name')} {RESET}")
        print(f"{BOLD}{BLUE}====================================================================={RESET}\n")

        plugin_path = config.get("plugin_jar_path",
                                 "../../build/libs/besu-plugin-permissioning.jar")

        nos = config.get("nos", []) or config.get("nodes", [])
        precisa_plugin = any(no.get("usar_plugin", no.get("use_plugin", False)) for no in nos)
        resolved_plugin_path = None
        if precisa_plugin:
            resolved_plugin_path = build_plugin_if_needed(plugin_path)

        setup_files(config)

        bootnode_pubkey, bootnode_service = generate_keys_and_get_bootnode(
            nos, RUN_DIR, compose_cmd
        )

        compose_file = generate_docker_compose(
            config, resolved_plugin_path, bootnode_pubkey, bootnode_service
        )

        log_success(f"Docker Compose configuration generated at: {compose_file}")

        start_network(compose_file, compose_cmd)

        # Wait for network blocks
        blocks_ok, rpc_url, block = wait_for_blocks(config, timeout=args.timeout)

        # Run post-genesis setup script (deploy contracts, GEN2, etc.)
        setup_ok = True
        if not args.skip_setup and blocks_ok:
            setup_ok = run_setup_script(config, rpc_url)
            if not setup_ok:
                log_warning("Post-genesis setup failed — some scenarios may not function.")

        # Verify on-chain contracts
        contracts_ok = True
        if not args.skip_verify:
            contracts_ok = verify_contracts(rpc_url, config.get("contracts", {}), config)

        # Capture Docker logs
        run_tag = datetime.now().strftime("%Y%m%d-%H%M%S")
        log_dir = capture_logs(config, run_tag=run_tag)

        # Start monitoring if requested
        if args.monitoring:
            monitoring_dir = os.path.join(BASE_DIR, "monitoring")
            if os.path.exists(os.path.join(monitoring_dir, "monitoring.sh")):
                print(f"\n{BOLD}{BLUE}--- STARTING MONITORING ---{RESET}")
                subprocess.run([os.path.join(monitoring_dir, "monitoring.sh"), "start"])
                print(f"{CYAN}   Grafana:{RESET}    http://localhost:3000 (admin/admin)")
                print(f"{CYAN}   Prometheus:{RESET} http://localhost:9090")
            else:
                log_warning("Script monitoring/monitoring.sh not found.")

        print_endpoints_summary(config)

        # Final summary
        print(f"{BOLD}{CYAN}--- EXECUTION LOGS ---{RESET}")
        print(f"📁 {log_dir}")
        if blocks_ok:
            if contracts_ok:
                log_success("Network ready for permissioning tests!")
            else:
                log_warning("Network started but contract verification encountered issues.")
        else:
            log_warning("Network started without blocks (possible fail-close scenario).")

if __name__ == "__main__":
    main()
