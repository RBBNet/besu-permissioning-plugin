#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Orquestrador Dinâmico de Testes do Plugin Sandbox
Gerencia o ciclo de vida de redes Besu customizáveis, suportando
múltiplas versões de nós, ativação seletiva do plugin de permissionamento
e verificação de contratos on-chain.

Evolução v2: Suporte a contratos GEN1/GEN2 pré-deployados no genesis,
verificação on-chain, e setup automático configurável.
"""

import os
import sys
import json
import shutil
import argparse
import subprocess
import time
from datetime import datetime

# Cores ANSI para o terminal
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

# Endereços padrão dos contratos pré-deployados no genesis (genesis-evolution.json)
# Endereços dos contratos pré-deployados no genesis (genesis-evolution.json)
# Admin + AccountRules + NodeRules já vêm com storage configurado:
#   - Admin registrado nos Ingresses (chave "admin" → Admin contract)
#   - Rules registradas nos Ingresses (chave "rules" → AccountRules/NodeRules)
#   - Admin (0xf39Fd6e5...) no allowlist do Admin contract e AccountRules
DEFAULT_CONTRACTS = {
    "account_ingress":      "0x0000000000000000000000000000000000008888",
    "node_ingress":         "0x0000000000000000000000000000000000009999",
    "admin_contract":       "0x181a92c9b76ab7271a03b640cc172e75a0dc3484",
    "account_rules":        "0x0e9e81bb09cdd55b607373e89e3154354a925b7d",
    "node_rules":           "0xf01d20a2c5d466cc6a2bafd13bebac815aa5a616",
    "admin_addr":           "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    "admin_pk":             "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    "unauth_addr":          "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
    "unauth_pk":            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
}

def log_info(msg):
    print(f"{BLUE}[INFO]{RESET} {msg}")

def log_success(msg):
    print(f"{GREEN}[SUCESSO]{RESET} {msg}")

def log_warning(msg):
    print(f"{YELLOW}[AVISO]{RESET} {msg}")

def log_error(msg):
    print(f"{RED}[ERRO]{RESET} {msg}")

def check_docker():
    """Valida se o docker e docker-compose estão instalados e rodando."""
    try:
        subprocess.run(["docker", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    except Exception:
        log_error("Docker não encontrado! Certifique-se de que o Docker está instalado e no seu PATH.")
        sys.exit(1)

    try:
        subprocess.run(["docker", "compose", "version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        return "docker compose"
    except Exception:
        try:
            subprocess.run(["docker-compose", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            return "docker-compose"
        except Exception:
            log_error("Comando 'docker compose' ou 'docker-compose' não encontrado!")
            sys.exit(1)

def build_plugin_if_needed(plugin_path):
    """Verifica se o plugin JAR existe, senão avisa ou tenta compilar."""
    abs_plugin_path = os.path.abspath(os.path.join(BASE_DIR, plugin_path))
    if os.path.exists(abs_plugin_path):
        log_success(f"Plugin encontrado em: {abs_plugin_path}")
        return abs_plugin_path

    log_warning(f"Plugin JAR não encontrado em: {abs_plugin_path}")
    plugin_project_dir = os.path.abspath(os.path.join(BASE_DIR, "../.."))

    if os.path.exists(os.path.join(plugin_project_dir, "gradlew")):
        print(f"\n{YELLOW}Tentando compilar o plugin automaticamente usando Gradle...{RESET}")
        try:
            subprocess.run(["./gradlew", "shadowJar"], cwd=plugin_project_dir, check=True)
            candidate = os.path.join(plugin_project_dir, "build/libs/onchain-permissioning-plugin.jar")
            if os.path.exists(candidate):
                log_success("Plugin compilado com sucesso!")
                return candidate
        except Exception as e:
            log_error(f"Falha ao rodar build do plugin: {e}")

    log_error("Impossível prosseguir sem o plugin JAR compilado.")
    log_info("Por favor, compile o plugin executando './gradlew shadowJar' no diretório do plugin.")
    sys.exit(1)

def load_config(config_path):
    """Carrega o JSON de configuração da rede e mescla contratos padrão."""
    if not os.path.exists(config_path):
        log_error(f"Arquivo de configuração não encontrado: {config_path}")
        sys.exit(1)
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
    except Exception as e:
        log_error(f"Erro ao parsear arquivo JSON: {e}")
        sys.exit(1)

    # Mesclar contratos padrão (config do cenário sobrescreve defaults)
    if "contracts" not in config:
        config["contracts"] = {}
    for k, v in DEFAULT_CONTRACTS.items():
        if k not in config["contracts"]:
            config["contracts"][k] = v

    return config

def rpc_call(rpc_url, method, params=None):
    """Faz chamada JSON-RPC e retorna o resultado parseado."""
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
    """Aguarda até que pelo menos um nó esteja produzindo blocos.
    Prefere nós sem plugin (para deploy), depois qualquer nó com RPC."""
    nos = config.get("nos", [])

    # Prioridade: nós sem plugin primeiro (para deploy), depois qualquer nó com RPC
    deploy_nodes = [n for n in nos if "porta_rpc" in n and not n.get("usar_plugin", False)]
    plugin_nodes = [n for n in nos if "porta_rpc" in n and n.get("usar_plugin", False)]
    check_order = deploy_nodes + plugin_nodes

    if not check_order:
        check_order = [n for n in nos if "porta_rpc" in n]

    log_info(f"Aguardando rede iniciar (timeout: {timeout}s)...")
    start = time.time()

    while time.time() - start < timeout:
        for no in check_order:
            rpc_url = f"http://localhost:{no['porta_rpc']}"
            block = rpc_call(rpc_url, "eth_blockNumber")
            if block and block != "0x0":
                elapsed = int(time.time() - start)
                plugin_status = "sem plugin" if not no.get("usar_plugin", False) else "com plugin"
                log_success(f"Rede pronta! Bloco {block} em {no['nome']} ({plugin_status}, porta {no['porta_rpc']}) após {elapsed}s")
                return True, rpc_url, block
        time.sleep(3)

    log_warning(f"Timeout ({timeout}s): rede não produziu blocos.")
    log_warning("  Isso é esperado em cenários Fail-Close (FC-01, FC-02) onde o plugin bloqueia tudo.")
    return False, None, "0x0"

def verify_contracts(rpc_url, contracts, config):
    """Verifica se os contratos pré-deployados estão acessíveis on-chain."""
    c = contracts
    nos = config.get("nos", [])

    # Determinar se é cenário fail-close (ingress vazio ou inválido)
    primeiro_no = nos[0] if nos else {}
    ingress = primeiro_no.get("ingress_address", "")
    usar_plugin = primeiro_no.get("usar_plugin", False)

    # Em cenários fail-close, a rede pode não produzir blocos
    if not rpc_url:
        if ingress == "":
            log_info("Cenário FC-01 (sem ingress): verificações on-chain não se aplicam (fail-close ativo).")
        elif ingress not in [c["account_ingress"], c["node_ingress"]]:
            log_info("Cenário FC-02 (ingress inválido): verificações on-chain não se aplicam (fail-close ativo).")
        return True  # Não é falha, é comportamento esperado

    log_info("Verificando contratos on-chain...")
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
        log_info("  Nenhum contrato configurado para verificação.")
        return True

    for addr, label in to_check:
        code = rpc_call(rpc_url, "eth_getCode", [addr, "latest"])
        if code and len(code) > 4:
            log_success(f"  ✓ {label} ({addr[:10]}...) — {len(code)} chars de bytecode")
        else:
            log_warning(f"  ⚠ {label} ({addr[:10]}...) — não encontrado (pode não ter sido deployado ainda)")
            all_ok = False

    # Verificar se Admin está autorizado no AccountRules (se deployado)
    if c.get("account_rules") and c.get("admin_addr"):
        permitted = rpc_call(rpc_url, "eth_call", [
            {"to": c["account_rules"],
             "data": "0xfe9fbb80" + c["admin_addr"][2:].zfill(64)},
            "latest"
        ])
        if permitted and permitted != "0x" and int(permitted, 16) == 1:
            log_success(f"  ✓ Admin ({c['admin_addr'][:10]}...) está PERMITIDO no AccountRules")
        else:
            log_warning(f"  ⚠ Admin ({c['admin_addr'][:10]}...) NÃO está no allowlist (pode ser esperado)")

    return all_ok

def generate_keys_and_get_bootnode(nos, run_dir, compose_cmd):
    """Gera chaves determinísticas para cada nó e exporta a chave pública do bootnode."""
    bootnode_pubkey = None
    bootnode_service_name = None

    for i, no in enumerate(nos):
        no_name = no.get("nome") or no.get("name")
        if not no_name:
            no_name = f"no-{i + 1}"
        no_dir = os.path.join(run_dir, "volumes", no_name)
        os.makedirs(no_dir, exist_ok=True)

        private_key = no.get("private_key")
        if not private_key:
            private_key = f"{i + 1:064x}"

        key_file_path = os.path.join(no_dir, "key")
        with open(key_file_path, 'w', encoding='utf-8') as f:
            f.write(private_key)

        tipo = no.get("tipo") or no.get("role", "")
        if tipo == "bootnode":
            bootnode_service_name = no_name
            pubkey_file = os.path.join(no_dir, "key.pub")

            log_info(f"Exportando chave pública do bootnode ({no_name})...")
            image_name = f"hyperledger/besu:{no.get('versao_besu', '25.12.0')}"
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
                    log_success(f"Chave pública do bootnode obtida: {bootnode_pubkey[:10]}...")
            except Exception as e:
                log_error(f"Erro ao exportar chave pública do bootnode: {e}")
                log_warning("Usando chave pública estática fallback correspondente à chave privada '1'")
                bootnode_pubkey = "7a6d8011244d2d9a65f0992f2272de9f3c7fa6e0b741004a43eb37651a541603ba05b0c950a7c490a1608888b15a6b4d238ff944bacb478cbed5efcae784d7bf"

    return bootnode_pubkey, bootnode_service_name

def generate_docker_compose(config, plugin_path, bootnode_pubkey, bootnode_service):
    """Gera o arquivo docker-compose.yml dinamicamente com suporte a zero-gas e contratos."""
    nome_rede = config.get("nome_rede", "plugin-testnet")
    nos = config.get("nos", [])

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
        no_name = no.get("nome") or no.get("name")
        tipo = no.get("tipo") or no.get("role", "")
        versao = no.get("versao_besu", "latest")
        usar_plugin = no.get("usar_plugin", False)

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

        # Ambiente base
        env = {
            "LOG4J_CONFIGURATION_FILE": "/var/lib/besu/log.xml",
            "BESU_MIN_GAS_PRICE": "0",
        }

        # Desabilita verificação de saldo para tx zero-gas (contas sem ETH podem transacionar)
        if usar_plugin:
            env["BESU_TX_POOL_ENABLE_BALANCE_CHECK"] = "false"

        # Permissionamento: suporta 3 modos
        #   "plugin"  — usar_plugin=true + ingress (padrão quando usar_plugin=true)
        #   "native"  — usar_plugin=false + ingress (Besu nativo, sem plugin JAR)
        #   "none"    — usar_plugin=false + sem ingress (transparente)
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
        if "porta_rpc" in no:
            ports.append(f"{no['porta_rpc']}:8545")
        if "porta_metrics" in no:
            ports.append(f"{no['porta_metrics']}:9545")

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
    """Prepara os arquivos genesis e log.xml."""
    os.makedirs(RUN_DIR, exist_ok=True)

    genesis_template = os.path.join(TEMPLATES_DIR, "genesis.json")
    genesis_dest = os.path.join(RUN_DIR, "genesis.json")
    if os.path.exists(genesis_template):
        shutil.copy2(genesis_template, genesis_dest)
        # Verificar quais contratos estão no genesis
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
                log_success(f"Genesis carregado com {len(contratos_no_genesis)} contratos pré-deployados:")
                for c in contratos_no_genesis:
                    print(f"    {c}")
        except Exception:
            pass
    else:
        log_error(f"Arquivo de genesis modelo não encontrado em: {genesis_template}")
        sys.exit(1)

    log_template = os.path.join(TEMPLATES_DIR, "log.xml")
    log_dest = os.path.join(RUN_DIR, "log.xml")
    if os.path.exists(log_template):
        shutil.copy2(log_template, log_dest)

def capture_logs(config, run_tag=None):
    """Captura logs Docker de todos os contêineres e salva em logs/<timestamp>/."""
    if not run_tag:
        run_tag = datetime.now().strftime("%Y%m%d-%H%M%S")
    log_dir = os.path.join(LOGS_DIR, run_tag)
    os.makedirs(log_dir, exist_ok=True)

    nome_rede = config.get("nome_rede", "plugin-testnet")
    nos = config.get("nos", [])

    log_info(f"Capturando logs Docker em: {log_dir}")

    for no in nos:
        no_name = no.get("nome") or no.get("name")
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
            log_success(f"  {no_name}: {lines} linhas → {log_file}")
        except subprocess.TimeoutExpired:
            log_warning(f"  {no_name}: timeout (logs muito grandes), capturando head...")
            try:
                result = subprocess.run(
                    ["docker", "logs", "--tail", "5000", container_name],
                    capture_output=True, text=True, timeout=15
                )
                with open(log_file, 'w', encoding='utf-8') as f:
                    f.write(f"# LOG TRUNCADO (últimas 5000 linhas) — container: {container_name}\n")
                    f.write(result.stdout)
                log_warning(f"  {no_name}: {result.stdout.count(chr(10))} linhas (truncado) → {log_file}")
            except Exception:
                log_error(f"  {no_name}: falha total ao capturar logs")
        except Exception as e:
            log_error(f"  {no_name}: erro ao capturar logs — {e}")

    # Salvar metadata da execução
    meta = {
        "run_tag": run_tag,
        "timestamp": datetime.now().isoformat(),
        "config": config.get("nome_rede", "unknown"),
        "descricao": config.get("descricao", ""),
        "nodes": [{"nome": n.get("nome"), "tipo": n.get("tipo"),
                    "versao_besu": n.get("versao_besu"),
                    "usar_plugin": n.get("usar_plugin"),
                    "porta_rpc": n.get("porta_rpc")}
                  for n in nos],
        "contracts": config.get("contracts", {}),
    }
    meta_path = os.path.join(log_dir, "run-metadata.json")
    with open(meta_path, 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=2, default=str)
    log_info(f"  Metadata: {meta_path}")

    return log_dir

def run_setup_script(config, rpc_url):
    """Executa script de setup pós-genesis se configurado no cenário."""
    setup_script = config.get("setup_script")
    if not setup_script:
        return True

    script_path = os.path.join(BASE_DIR, setup_script)
    if not os.path.exists(script_path):
        log_warning(f"Script de setup não encontrado: {script_path}")
        return False

    log_info(f"Executando script de setup: {setup_script}")

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
            log_success("Script de setup concluído.")
            return True
        else:
            log_error(f"Script de setup falhou (exit={result.returncode})")
            if result.stderr:
                log_error(result.stderr[:500])
            return False
    except subprocess.TimeoutExpired:
        log_error("Script de setup excedeu timeout (120s)")
        return False
    except Exception as e:
        log_error(f"Erro ao executar script de setup: {e}")
        return False

def print_endpoints_summary(config):
    """Exibe na tela o sumário de endpoints, contratos e credenciais de teste."""
    nos = config.get("nos", [])
    c = config.get("contracts", {})

    print("\n" + "="*80)
    print(f" {BOLD}{GREEN}A REDE BLOCKCHAIN Plugin Permissioning ESTÁ DE PÉ E OPERACIONAL!{RESET}")
    print("="*80)

    print(f"\n{BOLD}{CYAN}--- ENDPOINTS RPC (Para configurar no POSTMAN/Newman) ---{RESET}")
    for no in nos:
        no_name = no.get("nome") or no.get("name")
        role = (no.get("tipo") or no.get("role", "")).upper()
        ver = no.get("versao_besu", "latest")
        plugin_status = f"{GREEN}Ativo{RESET}" if no.get("usar_plugin") else f"{RED}Inativo{RESET}"
        ingress = no.get("ingress_address", "N/A")

        rpc_url = f"http://localhost:{no['porta_rpc']}" if "porta_rpc" in no else "N/A"
        metrics_url = f"http://localhost:{no['porta_metrics']}/metrics" if "porta_metrics" in no else "N/A"

        print(f"🔹 {BOLD}{no_name}{RESET} [{role} | Besu: {ver} | Plugin: {plugin_status}]")
        print(f"   ↳ {BOLD}RPC URL:{RESET}     {CYAN}{rpc_url}{RESET}")
        print(f"   ↳ {BOLD}Métricas:{RESET}    {metrics_url}")
        if no.get("usar_plugin"):
            print(f"   ↳ {BOLD}Ingress:{RESET}    {ingress}")

    print(f"\n{BOLD}{CYAN}--- CONTRATOS INTELIGENTES (Pré-deployados no Genesis) ---{RESET}")
    print(f"📍 {BOLD}Account Ingress:{RESET}    {c.get('account_ingress', 'N/A')}")
    print(f"📍 {BOLD}Node Ingress:{RESET}       {c.get('node_ingress', 'N/A')}")
    print(f"📍 {BOLD}Admin (proxy):{RESET}      {c.get('admin_contract', 'N/A')}")
    print(f"📍 {BOLD}AccountRules (GEN1):{RESET} {c.get('account_rules', 'N/A')}")
    print(f"📍 {BOLD}NodeRules (GEN1):{RESET}    {c.get('node_rules', 'N/A')}")

    print(f"\n{BOLD}{CYAN}--- CHAVES E CONTAS DE TESTE ---{RESET}")
    print(f"🔑 {BOLD}CONTA PERMITIDA (ADMIN):{RESET}")
    print(f"   ↳ Endereço: {GREEN}{c.get('admin_addr', 'N/A')}{RESET} (Saldo: 100k ETH)")
    print(f"   ↳ Chave Privada: {c.get('admin_pk', 'N/A')}")
    print(f"🔑 {BOLD}CONTA BLOQUEADA (UNAUTH):{RESET}")
    print(f"   ↳ Endereço: {RED}{c.get('unauth_addr', 'N/A')}{RESET} (Saldo: Financiar via teste)")
    print(f"   ↳ Chave Privada: {c.get('unauth_pk', 'N/A')}")

    # Status do permissionamento
    primeiro_no = nos[0] if nos else {}
    ingress = primeiro_no.get("ingress_address", "")
    if ingress == "":
        perm_status = f"{RED}FAIL-CLOSE — Todas as transações BLOQUEADAS (sem Ingress){RESET}"
    elif ingress not in [c.get("account_ingress", ""), c.get("node_ingress", "")]:
        perm_status = f"{RED}FAIL-CLOSE — Ingress inválido/inexistente ({ingress}){RESET}"
    else:
        perm_status = f"{GREEN}ATIVO — Contratos registrados, allowlists em vigor{RESET}"

    print(f"\n{BOLD}{CYAN}--- STATUS DO PERMISSIONAMENTO ---{RESET}")
    print(f"🔒 {perm_status}")

    print(f"\n{BOLD}{YELLOW}--- INSTRUÇÕES DE TESTE ---{RESET}")
    print("1. Importe a coleção Postman em 'test-suite/postman/' para o seu Postman.")
    print("2. Configure a URL do nó (ex: http://localhost:9005 para rpc-node-1).")
    print("3. Execute os testes PF-01 (ADMIN aprovada) e PF-02 (UNAUTH rejeitada).")
    print("4. Monitore os logs de permissionamento:")
    print(f"   {CYAN}docker logs -f {config.get('nome_rede', 'plugin-testnet')}-rpc-node-1 | grep -E 'Permission check|Sender'{RESET}")
    print("="*80 + "\n")

def start_network(compose_path, compose_cmd):
    """Executa o docker-compose up."""
    log_info("Subindo contêineres Docker da rede Besu...")
    try:
        cmd = compose_cmd.split() + ["-f", compose_path, "up", "-d"]
        subprocess.run(cmd, check=True)
        log_success("Contêineres criados e iniciados com sucesso.")
    except Exception as e:
        log_error(f"Erro ao iniciar a rede Docker: {e}")
        sys.exit(1)

def stop_network(compose_path, compose_cmd):
    """Para e remove a rede e os contêineres."""
    log_info("Parando e removendo contêineres Docker...")
    if not os.path.exists(compose_path):
        log_error(f"Arquivo docker-compose não encontrado em: {compose_path}")
        return

    try:
        cmd = compose_cmd.split() + ["-f", compose_path, "down", "-v"]
        subprocess.run(cmd, check=True)
        log_success("Rede parada e volumes de contêineres limpos.")

        volumes_dir = os.path.join(RUN_DIR, "volumes")
        if os.path.exists(volumes_dir):
            try:
                shutil.rmtree(volumes_dir)
                log_info("Diretórios de volumes locais removidos.")
            except Exception as e:
                log_warning(f"Não foi possível remover todos os volumes locais: {e}")
                log_info("Limpe manualmente: sudo rm -rf test-suite/run-data/volumes")
    except Exception as e:
        log_error(f"Erro ao parar a rede Docker: {e}")

def main():
    parser = argparse.ArgumentParser(
        description="Orquestrador Dinâmico de Testes Besu + Permissionamento (v2)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemplos:
  ./run.sh -c configs/cenario-valioso.json -a start
  ./run.sh -c configs/cenario-valioso.json -a start --monitoring
  ./run.sh -c configs/cenario-failclose-sem-ingress.json -a start --skip-verify
  ./run.sh -c configs/cenario-valioso.json -a stop
  ./run.sh -a status
        """
    )
    parser.add_argument("--config", "-c", default="configs/cenario-valioso.json",
                        help="Caminho do JSON de configuração da rede (default: configs/cenario-valioso.json)")
    parser.add_argument("--action", "-a", choices=["start", "stop", "status"], default="start",
                        help="Ação a ser realizada na rede (default: start)")
    parser.add_argument("--skip-verify", action="store_true",
                        help="Pular verificação de contratos on-chain após startup")
    parser.add_argument("--skip-setup", action="store_true",
                        help="Pular execução de script de setup pós-genesis")
    parser.add_argument("--timeout", type=int, default=120,
                        help="Timeout em segundos para aguardar blocos (default: 120)")
    parser.add_argument("--monitoring", "-m", action="store_true",
                        help="Iniciar stack de monitoramento (Prometheus + Grafana)")

    args = parser.parse_args()

    compose_cmd = check_docker()

    config_path = os.path.join(BASE_DIR, args.config)
    config = load_config(config_path)

    compose_path = os.path.join(RUN_DIR, "docker-compose.yml")

    if args.action == "stop":
        # Parar monitoramento se existir
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
            log_warning("Nenhuma rede ativa configurada no momento.")
        
        # Mostrar status do monitoramento
        monitoring_dir = os.path.join(BASE_DIR, "monitoring")
        if os.path.exists(os.path.join(monitoring_dir, "monitoring.sh")):
            print(f"\n{BOLD}{CYAN}--- MONITORAMENTO ---{RESET}")
            subprocess.run([os.path.join(monitoring_dir, "monitoring.sh"), "status"])
        
        sys.exit(0)

    elif args.action == "start":
        print(f"\n{BOLD}{BLUE}====================================================================={RESET}")
        print(f"{BOLD}{BLUE}⚙️  INICIANDO PREPARAÇÃO DA REDE: {config.get('nome_rede')} {RESET}")
        print(f"{BOLD}{BLUE}====================================================================={RESET}\n")

        plugin_path = config.get("plugin_jar_path",
                                 "../../build/libs/onchain-permissioning-plugin.jar")

        precisa_plugin = any(no.get("usar_plugin", False) for no in config.get("nos", []))
        resolved_plugin_path = None
        if precisa_plugin:
            resolved_plugin_path = build_plugin_if_needed(plugin_path)

        setup_files(config)

        bootnode_pubkey, bootnode_service = generate_keys_and_get_bootnode(
            config.get("nos", []), RUN_DIR, compose_cmd
        )

        compose_file = generate_docker_compose(
            config, resolved_plugin_path, bootnode_pubkey, bootnode_service
        )

        log_success(f"Configuração do Docker Compose gerada em: {compose_file}")

        start_network(compose_file, compose_cmd)

        # Aguardar rede ficar pronta
        blocks_ok, rpc_url, block = wait_for_blocks(config, timeout=args.timeout)

        # Executar script de setup pós-genesis (deploy contratos, GEN2, etc.)
        # Roda ANTES da verificação para que contratos deployados existam on-chain
        setup_ok = True
        if not args.skip_setup and blocks_ok:
            setup_ok = run_setup_script(config, rpc_url)
            if not setup_ok:
                log_warning("Setup pós-genesis falhou — alguns cenários podem não funcionar.")

        # Verificar contratos on-chain (após setup)
        contracts_ok = True
        if not args.skip_verify:
            contracts_ok = verify_contracts(rpc_url, config.get("contracts", {}), config)

        # Capturar logs Docker
        run_tag = datetime.now().strftime("%Y%m%d-%H%M%S")
        log_dir = capture_logs(config, run_tag=run_tag)

        # Iniciar monitoramento se solicitado
        if args.monitoring:
            monitoring_dir = os.path.join(BASE_DIR, "monitoring")
            if os.path.exists(os.path.join(monitoring_dir, "monitoring.sh")):
                print(f"\n{BOLD}{BLUE}--- INICIANDO MONITORAMENTO ---{RESET}")
                subprocess.run([os.path.join(monitoring_dir, "monitoring.sh"), "start"])
                print(f"{CYAN}   Grafana:{RESET}    http://localhost:3000 (admin/admin)")
                print(f"{CYAN}   Prometheus:{RESET} http://localhost:9090")
            else:
                log_warning("Script monitoring/monitoring.sh não encontrado.")

        print_endpoints_summary(config)

        # Resumo final
        print(f"{BOLD}{CYAN}--- LOGS DA EXECUÇÃO ---{RESET}")
        print(f"📁 {log_dir}")
        if blocks_ok:
            if contracts_ok:
                log_success("Rede pronta para testes de permissionamento!")
            else:
                log_warning("Rede iniciada mas verificação de contratos encontrou problemas.")
        else:
            log_warning("Rede iniciada sem blocos (possível cenário fail-close).")

if __name__ == "__main__":
    main()
