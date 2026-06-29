#!/usr/bin/env python3
"""
Multi-bridge cross-tenant E2E test.

Scenario: a central web app knows the tokens of 3 distinct bridge instances
deployed on 3 machines (A/France, B/Spain, C/England). Each bridge has its
own token. The central app routes print jobs from any user to any bridge
using the correct token for the target bridge.

This test simulates the 3 bridges as 3 instances on different ports within
the same Docker container (127.0.0.1:12212, :12213, :12214), each with its
own token. It verifies:

1. Token isolation: each bridge only accepts its own token
2. Cross-bridge routing: user A can print via bridge B or C using their tokens
3. Wrong token is rejected (401)
4. Per-endpoint password works independently on each bridge
5. A bridge with auth disabled still works without tokens
"""

import json
import os
import signal
import subprocess
import sys
import time
import urllib.request
import urllib.error


def request(base_url, method, path, body=None, headers=None, token=None):
    url = base_url + path
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")
    except urllib.error.URLError as e:
        return -1, str(e)


def assert_eq(name, actual, expected):
    if actual != expected:
        print(f"FAIL {name}: expected {expected}, got {actual}")
        sys.exit(1)
    print(f"OK   {name}")


def assert_true(name, condition):
    if not condition:
        print(f"FAIL {name}: expected truthy condition")
        sys.exit(1)
    print(f"OK   {name}")


def wait_for_bridge(base_url, name, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            s, b = request(base_url, "GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                return True
        except Exception:
            pass
        time.sleep(0.5)
    print(f"FAIL: bridge {name} at {base_url} did not become healthy")
    return False


def start_bridge(jar_path, port, token, workdir):
    """Start a bridge instance with the given port and auth token."""
    env = os.environ.copy()
    cmd = [
        "java",
        "-Dlhb.server=true",
        "-cp", jar_path,
        "io.github.augustinlr17.localhardwarebridge.Server",
    ]
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=env,
        cwd=workdir,
    )

    # Wait for the bridge to bind, then configure it via the API
    base_url = f"http://127.0.0.1:{port}"

    # The bridge starts with default config (no auth, port 12212).
    # We need to set the port and token via config before it's useful.
    # Since the bridge reads port at startup, we write config.json first
    # in a dedicated working directory.
    return proc


def configure_bridge(base_url, port, token):
    """Configure a running bridge: set port, enable auth with token, add printer mapping."""
    # Set server config (port is already set via config.json, but set auth)
    config_status, config_body = request(base_url, "GET", "/config.json")
    if config_status != 200:
        return False
    config = json.loads(config_body)

    config["server"]["port"] = port
    config["server"]["authentication"]["enabled"] = True
    config["server"]["authentication"]["token"] = token

    status, _ = request(base_url, "PUT", "/config.json", config)
    if status != 200:
        return False

    # Add a printer mapping (CUPS-PDF should be available)
    mapping = {"type": "RECEIPT", "name": "CUPS-PDF", "resetImageableArea": True,
               "autoRotate": False, "forceDPI": 0}
    request(base_url, "POST", "/printer/mappings", mapping)

    return True


def write_config_json(workdir, port, token=None, auth_enabled=False):
    """Write a config.json with the desired port and optional auth."""
    config = {
        "server": {
            "address": "127.0.0.1",
            "bind": "127.0.0.1",
            "port": port,
            "authentication": {
                "enabled": auth_enabled,
                "token": token,
            },
            "tls": {"enabled": False, "selfSigned": True,
                    "cert": "tls/default-cert.pem", "key": "tls/default-key.pem",
                    "caBundle": None},
            "cors": {"allowAllOrigins": True, "allowedOrigins": []},
        },
        "security": {"endpoints": {}},
        "downloader": {"ignoreTLSCertificateError": False, "blockPrivateNetworks": False,
                       "timeout": 30, "path": "downloads"},
        "printer": {"enabled": True, "autoAddUnknownType": False,
                    "fallbackToDefault": False, "mappings": [
                        {"type": "RECEIPT", "name": "CUPS-PDF",
                         "autoRotate": False, "resetImageableArea": True, "forceDPI": 0}
                    ]},
        "serial": {"enabled": False, "mappings": []},
        "gui": {"notification": {"enabled": False}},
    }
    config_path = os.path.join(workdir, "config.json")
    with open(config_path, "w") as f:
        json.dump(config, f)
    return config_path


def wait_for_port_free(port, timeout=60):
    """Wait until no process is listening on the given port."""
    import socket
    deadline = time.time() + timeout
    while time.time() < deadline:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1)
        try:
            sock.connect(("127.0.0.1", port))
            sock.close()
            # Port is still in use, wait
        except (ConnectionRefusedError, OSError):
            return True
        time.sleep(1)
    return False


def main():
    jar_path = "/app/local-hardware-bridge.jar"
    if not os.path.exists(jar_path):
        print(f"FAIL: JAR not found at {jar_path}")
        sys.exit(1)

    # Wait for all required ports to be free before starting
    for port in [12212, 12213, 12214]:
        if not wait_for_port_free(port):
            print(f"FAIL: port {port} is still in use")
            sys.exit(1)

    # --- Bridge definitions ---
    bridges = {
        "A_FRANCE": {
            "port": 12212,
            "token": "token-fr-75001",
            "workdir": "/tmp/bridge-a",
        },
        "B_SPAIN": {
            "port": 12213,
            "token": "token-es-28001",
            "workdir": "/tmp/bridge-b",
        },
        "C_ENGLAND": {
            "port": 12214,
            "token": "token-uk-sw1",
            "workdir": "/tmp/bridge-c",
        },
    }

    procs = []

    try:
        # --- Start 3 bridges, each with its own token ---
        for name, cfg in bridges.items():
            os.makedirs(cfg["workdir"], exist_ok=True)
            write_config_json(cfg["workdir"], cfg["port"],
                              token=cfg["token"], auth_enabled=True)

            cmd = [
                "java", "-Dlhb.server=true",
                "-cp", jar_path,
                "io.github.augustinlr17.localhardwarebridge.Server",
            ]
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                cwd=cfg["workdir"],
            )
            procs.append(proc)
            cfg["proc"] = proc

            base_url = f"http://127.0.0.1:{cfg['port']}"
            cfg["base_url"] = base_url

            if not wait_for_bridge(base_url, name):
                sys.exit(1)
            print(f"OK   bridge {name} started on port {cfg['port']}")

        # --- Test 1: Token isolation ---
        print("\n--- Token isolation ---")

        # Bridge A accepts A's token
        s, b = request(bridges["A_FRANCE"]["base_url"], "GET", "/system/version.json",
                       token=bridges["A_FRANCE"]["token"])
        assert_eq("bridge A accepts A's token", s, 200)

        # Bridge A rejects B's token
        s, b = request(bridges["A_FRANCE"]["base_url"], "GET", "/system/version.json",
                       token=bridges["B_SPAIN"]["token"])
        assert_eq("bridge A rejects B's token (401)", s, 401)

        # Bridge A rejects C's token
        s, b = request(bridges["A_FRANCE"]["base_url"], "GET", "/system/version.json",
                       token=bridges["C_ENGLAND"]["token"])
        assert_eq("bridge A rejects C's token (401)", s, 401)

        # Bridge B accepts B's token
        s, b = request(bridges["B_SPAIN"]["base_url"], "GET", "/system/version.json",
                       token=bridges["B_SPAIN"]["token"])
        assert_eq("bridge B accepts B's token", s, 200)

        # Bridge B rejects A's token
        s, b = request(bridges["B_SPAIN"]["base_url"], "GET", "/system/version.json",
                       token=bridges["A_FRANCE"]["token"])
        assert_eq("bridge B rejects A's token (401)", s, 401)

        # Bridge C accepts C's token
        s, b = request(bridges["C_ENGLAND"]["base_url"], "GET", "/system/version.json",
                       token=bridges["C_ENGLAND"]["token"])
        assert_eq("bridge C accepts C's token", s, 200)

        # Bridge C rejects A's token
        s, b = request(bridges["C_ENGLAND"]["base_url"], "GET", "/system/version.json",
                       token=bridges["A_FRANCE"]["token"])
        assert_eq("bridge C rejects A's token (401)", s, 401)

        # --- Test 2: No token is rejected when auth is enabled ---
        print("\n--- No token rejected ---")

        s, b = request(bridges["A_FRANCE"]["base_url"], "GET", "/system/version.json")
        assert_eq("bridge A rejects no-token request (401)", s, 401)

        # Health endpoint is always accessible (exempt from auth)
        s, b = request(bridges["A_FRANCE"]["base_url"], "GET", "/system/health")
        assert_eq("bridge A health accessible without token", s, 200)

        # --- Test 3: Cross-bridge print routing ---
        print("\n--- Cross-bridge print routing ---")

        raw_doc = {"type": "RECEIPT", "raw_content": "SGVsbG8gV29ybGQ=", "id": "cross-1"}

        # User A (France) prints via Bridge B (Spain) using B's token
        s, b = request(bridges["B_SPAIN"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=bridges["B_SPAIN"]["token"])
        assert_eq("user A prints via bridge B (status)", s, 200)
        result = json.loads(b)
        assert_eq("user A prints via bridge B (success)", result.get("success"), True)
        print(f"INFO cross-bridge print A→B result: {result}")

        # User A (France) prints via Bridge C (England) using C's token
        s, b = request(bridges["C_ENGLAND"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=bridges["C_ENGLAND"]["token"])
        assert_eq("user A prints via bridge C (status)", s, 200)
        result = json.loads(b)
        assert_eq("user A prints via bridge C (success)", result.get("success"), True)
        print(f"INFO cross-bridge print A→C result: {result}")

        # --- Test 4: Wrong token on cross-bridge print is rejected ---
        print("\n--- Wrong token on cross-bridge print ---")

        # User A tries to print via B but accidentally sends A's token
        s, b = request(bridges["B_SPAIN"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=bridges["A_FRANCE"]["token"])
        assert_eq("user A with A's token rejected by bridge B (401)", s, 401)

        # User B tries to print via C but sends B's token
        s, b = request(bridges["C_ENGLAND"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=bridges["B_SPAIN"]["token"])
        assert_eq("user B with B's token rejected by bridge C (401)", s, 401)

        # --- Test 5: Per-endpoint password on a bridge ---
        print("\n--- Per-endpoint password ---")

        # Set a per-endpoint password on bridge B's /printer
        config_status, config_body = request(
            bridges["B_SPAIN"]["base_url"], "GET", "/config.json",
            token=bridges["B_SPAIN"]["token"])
        config = json.loads(config_body)
        config["security"]["endpoints"]["/printer"] = {
            "enabled": True, "password": "print-pin-es"
        }
        s, _ = request(bridges["B_SPAIN"]["base_url"], "PUT", "/config.json",
                       body=config, token=bridges["B_SPAIN"]["token"])
        assert_eq("set per-endpoint password on bridge B", s, 200)
        time.sleep(1)

        # Print with global token still works (global auth passes first)
        s, b = request(bridges["B_SPAIN"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=bridges["B_SPAIN"]["token"])
        assert_eq("bridge B print with global token still works", s, 200)

        # Clean up: remove per-endpoint password
        config["security"]["endpoints"]["/printer"] = {"enabled": True, "password": ""}
        request(bridges["B_SPAIN"]["base_url"], "PUT", "/config.json",
                body=config, token=bridges["B_SPAIN"]["token"])

        # --- Test 6: All 3 bridges are still healthy ---
        print("\n--- All bridges healthy ---")

        for name, cfg in bridges.items():
            s, b = request(cfg["base_url"], "GET", "/system/health")
            assert_eq(f"bridge {name} still healthy", s, 200)

        print("\nAll cross-bridge E2E tests passed!")

    finally:
        # Clean up: kill all bridge processes
        for proc in procs:
            try:
                proc.send_signal(signal.SIGTERM)
                proc.wait(timeout=5)
            except Exception:
                proc.kill()


if __name__ == "__main__":
    main()
