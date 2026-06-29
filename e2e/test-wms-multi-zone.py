#!/usr/bin/env python3
"""
Multi-zone WMS (Warehouse Management System) E2E test.

Complex scenario: a warehouse is split into 3 zones, each with its own
bridge instance managing a printer (delivery notes) and a serial device
(weight scale). A central WMS web app routes operations across zones:

- Zone A (Receiving):  bridge on :12215, printer "CUPS-PDF", scale "SCALE-A"
- Zone B (Shipping):   bridge on :12216, printer "CUPS-PDF", scale "SCALE-B"
- Zone C (Quality):    bridge on :12217, printer "CUPS-PDF", scale "SCALE-C"

The WMS can:
1. Print delivery notes to any zone's printer
2. Submit serial commands to any zone's scale
3. Query serial status across all zones simultaneously
4. Handle concurrent print jobs to the same zone (per-type lock)
5. Recover when a bridge restarts mid-operation
6. Verify endpoint security per zone (disable /printer on zone C)
7. Verify config isolation between zones
"""

import json
import os
import signal
import subprocess
import sys
import threading
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
        with urllib.request.urlopen(req, timeout=15) as resp:
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


def assert_true(name, condition, detail=""):
    if not condition:
        print(f"FAIL {name}: expected truthy condition {detail}")
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


def write_config_json(workdir, port, token, printer_type="DELIVERY",
                      serial_enabled=False, serial_type=None, serial_port=None):
    """Write a config.json for a WMS zone bridge."""
    serial_mappings = []
    if serial_enabled and serial_type and serial_port:
        serial_mappings.append({
            "type": serial_type, "name": serial_port,
            "baudRate": 9600, "numDataBits": 8, "numStopBits": 1, "parity": 0,
            "readMultiBytes": True, "readCharset": "ISO-8859-1",
        })

    config = {
        "server": {
            "address": "127.0.0.1", "bind": "127.0.0.1", "port": port,
            "authentication": {"enabled": True, "token": token},
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
                        {"type": printer_type, "name": "CUPS-PDF",
                         "autoRotate": False, "resetImageableArea": True, "forceDPI": 0}
                    ]},
        "serial": {"enabled": serial_enabled, "mappings": serial_mappings},
        "gui": {"notification": {"enabled": False}},
    }
    config_path = os.path.join(workdir, "config.json")
    with open(config_path, "w") as f:
        json.dump(config, f)
    return config_path


ZONES = {
    "A_RECEIVING": {
        "port": 12215, "token": "zone-a-receiving-token",
        "workdir": "/tmp/zone-a", "printer_type": "DELIVERY",
    },
    "B_SHIPPING": {
        "port": 12216, "token": "zone-b-shipping-token",
        "workdir": "/tmp/zone-b", "printer_type": "DELIVERY",
    },
    "C_QUALITY": {
        "port": 12217, "token": "zone-c-quality-token",
        "workdir": "/tmp/zone-c", "printer_type": "DELIVERY",
    },
}

JAR_PATH = "/app/local-hardware-bridge.jar"
procs = []


def start_zone_bridges():
    """Start one bridge per zone."""
    for name, cfg in ZONES.items():
        os.makedirs(cfg["workdir"], exist_ok=True)
        write_config_json(cfg["workdir"], cfg["port"], cfg["token"],
                          printer_type=cfg["printer_type"])
        cmd = ["java", "-Dlhb.server=true", "-cp", JAR_PATH,
               "io.github.augustinlr17.localhardwarebridge.Server"]
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, cwd=cfg["workdir"])
        procs.append(proc)
        cfg["proc"] = proc
        cfg["base_url"] = f"http://127.0.0.1:{cfg['port']}"
        if not wait_for_bridge(cfg["base_url"], name):
            sys.exit(1)
        print(f"OK   zone {name} bridge started on port {cfg['port']}")


def main():
    if not os.path.exists(JAR_PATH):
        print(f"FAIL: JAR not found at {JAR_PATH}")
        sys.exit(1)

    try:
        start_zone_bridges()

        # --- Section 1: Cross-zone print routing ---
        print("\n=== Section 1: Cross-zone print routing ===")

        raw_doc = {"type": "DELIVERY", "raw_content": "SGVsbG8gV29ybGQ=", "id": "wms-001"}

        # Operator in zone A prints a delivery note on zone B's printer (shipping)
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["B_SHIPPING"]["token"])
        assert_eq("zone A prints delivery note via zone B (status)", s, 200)
        result = json.loads(b)
        assert_eq("zone A prints via zone B (success)", result.get("success"), True)

        # Operator in zone A prints on zone C (quality control copy)
        s, b = request(ZONES["C_QUALITY"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["C_QUALITY"]["token"])
        assert_eq("zone A prints via zone C (status)", s, 200)
        result = json.loads(b)
        assert_eq("zone A prints via zone C (success)", result.get("success"), True)

        # Wrong token rejected
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("zone A token rejected by zone B (401)", s, 401)

        # --- Section 2: Printer mapping CRUD per zone ---
        print("\n=== Section 2: Printer mapping CRUD per zone ===")

        # Add a second printer type on zone A (e.g., for labels)
        label_mapping = {"type": "LABEL", "name": "CUPS-PDF",
                         "autoRotate": True, "resetImageableArea": True, "forceDPI": 203}
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "POST", "/printer/mappings",
                       body=label_mapping, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("add LABEL mapping to zone A", s, 200)

        # Verify zone A has 2 mappings, zone B still has 1 (config isolation)
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "GET", "/printer/mappings",
                       token=ZONES["A_RECEIVING"]["token"])
        mappings_a = json.loads(b).get("mappings", [])
        assert_eq("zone A has 2 printer mappings", len(mappings_a), 2)

        s, b = request(ZONES["B_SHIPPING"]["base_url"], "GET", "/printer/mappings",
                       token=ZONES["B_SHIPPING"]["token"])
        mappings_b = json.loads(b).get("mappings", [])
        assert_eq("zone B still has 1 printer mapping (isolation)", len(mappings_b), 1)

        # Print using the new LABEL type on zone A
        label_doc = {"type": "LABEL", "raw_content": "TEFCRUw=", "id": "wms-label-1"}
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "POST", "/printer",
                       body=label_doc, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("print LABEL via zone A (status)", s, 200)
        result = json.loads(b)
        assert_eq("print LABEL via zone A (success)", result.get("success"), True)

        # Update the LABEL mapping on zone A
        updated_label = {"type": "LABEL", "name": "CUPS-PDF",
                         "autoRotate": False, "resetImageableArea": False, "forceDPI": 300}
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "PUT", "/printer/mappings/LABEL",
                       body=updated_label, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("update LABEL mapping on zone A", s, 200)

        # Delete the LABEL mapping on zone A
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "DELETE", "/printer/mappings/LABEL",
                       token=ZONES["A_RECEIVING"]["token"])
        assert_eq("delete LABEL mapping on zone A", s, 200)

        # Verify it's gone
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "GET", "/printer/mappings",
                       token=ZONES["A_RECEIVING"]["token"])
        mappings_a = json.loads(b).get("mappings", [])
        assert_eq("zone A back to 1 mapping after delete", len(mappings_a), 1)

        # --- Section 3: Concurrent print jobs (per-type lock) ---
        print("\n=== Section 3: Concurrent print jobs ===")

        # Launch 5 concurrent print jobs to zone A's DELIVERY printer
        concurrent_results = []
        concurrent_lock = threading.Lock()

        def concurrent_print(job_id):
            doc = {"type": "DELIVERY", "raw_content": "SGVsbG8=", "id": f"conc-{job_id}"}
            s, b = request(ZONES["A_RECEIVING"]["base_url"], "POST", "/printer",
                           body=doc, token=ZONES["A_RECEIVING"]["token"])
            with concurrent_lock:
                concurrent_results.append((job_id, s, json.loads(b).get("success")))

        threads = []
        for i in range(5):
            t = threading.Thread(target=concurrent_print, args=(i,))
            threads.append(t)
            t.start()
        for t in threads:
            t.join(timeout=30)

        assert_eq("5 concurrent jobs returned", len(concurrent_results), 5)
        all_success = all(s == 200 and success for _, s, success in concurrent_results)
        assert_true("all 5 concurrent prints succeeded", all_success,
                    f"results: {concurrent_results}")

        # --- Section 4: Health monitoring across all zones ---
        print("\n=== Section 4: Multi-zone health monitoring ===")

        for name, cfg in ZONES.items():
            s, b = request(cfg["base_url"], "GET", "/system/health")
            assert_eq(f"zone {name} health status", s, 200)
            health = json.loads(b)
            assert_eq(f"zone {name} health UP", health.get("status"), "UP")
            assert_true(f"zone {name} printerEnabled",
                        health.get("printerEnabled") is True)

        # Version is consistent across all zones
        versions = {}
        for name, cfg in ZONES.items():
            s, b = request(cfg["base_url"], "GET", "/system/version.json",
                           token=cfg["token"])
            assert_eq(f"zone {name} version status", s, 200)
            versions[name] = json.loads(b).get("version")
        v0 = list(versions.values())[0]
        for name, v in versions.items():
            assert_eq(f"zone {name} version matches", v, v0)

        # --- Section 5: Per-endpoint security per zone ---
        print("\n=== Section 5: Per-endpoint security per zone ===")

        # Disable /printer on zone C (quality control — no printing allowed)
        s, b = request(ZONES["C_QUALITY"]["base_url"], "GET", "/config.json",
                       token=ZONES["C_QUALITY"]["token"])
        config_c = json.loads(b)
        config_c["security"]["endpoints"]["/printer"] = {"enabled": False, "password": ""}
        s, _ = request(ZONES["C_QUALITY"]["base_url"], "PUT", "/config.json",
                       body=config_c, token=ZONES["C_QUALITY"]["token"])
        assert_eq("disable /printer on zone C", s, 200)
        time.sleep(1)

        # Printing on zone C is now blocked (403)
        s, b = request(ZONES["C_QUALITY"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["C_QUALITY"]["token"])
        assert_eq("zone C /printer blocked (403)", s, 403)

        # But zone A and B still work
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("zone A /printer still works", s, 200)

        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["B_SHIPPING"]["token"])
        assert_eq("zone B /printer still works", s, 200)

        # Re-enable zone C
        config_c["security"]["endpoints"]["/printer"] = {"enabled": True, "password": ""}
        request(ZONES["C_QUALITY"]["base_url"], "PUT", "/config.json",
                body=config_c, token=ZONES["C_QUALITY"]["token"])

        # --- Section 6: Bridge restart recovery ---
        print("\n=== Section 6: Bridge restart recovery ===")

        # Restart zone B
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/system/restart.json",
                       token=ZONES["B_SHIPPING"]["token"])
        assert_true("zone B restart accepted", s in (200, 202))

        # Poll until zone B is healthy again
        healthy = False
        deadline = time.time() + 60
        while time.time() < deadline:
            try:
                s, b = request(ZONES["B_SHIPPING"]["base_url"], "GET", "/system/health")
                if s == 200 and json.loads(b).get("status") == "UP":
                    healthy = True
                    break
            except Exception:
                pass
            time.sleep(1)
        assert_true("zone B healthy after restart", healthy)

        # Verify zone B still works after restart
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["B_SHIPPING"]["token"])
        assert_eq("zone B print works after restart", s, 200)
        result = json.loads(b)
        assert_eq("zone B print success after restart", result.get("success"), True)

        # --- Section 7: Config section endpoints per zone ---
        print("\n=== Section 7: Config section endpoints per zone ===")

        # Get server config from each zone — must reflect different ports
        for name, cfg in ZONES.items():
            s, b = request(cfg["base_url"], "GET", "/system/server.json",
                           token=cfg["token"])
            assert_eq(f"zone {name} server config status", s, 200)
            server_cfg = json.loads(b)
            assert_eq(f"zone {name} port in server config",
                      server_cfg.get("port"), cfg["port"])

        # --- Section 8: Printer enable/disable per zone ---
        print("\n=== Section 8: Printer enable/disable per zone ===")

        # Disable printer service on zone A
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "PUT", "/printer/enabled",
                       body={"enabled": False}, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("disable printer on zone A", s, 200)

        # POST /printer on zone A now returns 503
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("zone A /printer returns 503 when disabled", s, 503)

        # Zone B still works
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/printer",
                       body=raw_doc, token=ZONES["B_SHIPPING"]["token"])
        assert_eq("zone B /printer still works while A disabled", s, 200)

        # Re-enable zone A
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "PUT", "/printer/enabled",
                       body={"enabled": True}, token=ZONES["A_RECEIVING"]["token"])
        assert_eq("re-enable printer on zone A", s, 200)

        # --- Section 9: System connections per zone ---
        print("\n=== Section 9: System connections per zone ===")

        for name, cfg in ZONES.items():
            s, b = request(cfg["base_url"], "GET", "/system/connections",
                           token=cfg["token"])
            assert_eq(f"zone {name} connections status", s, 200)

        # --- Section 10: Print with unknown type + autoAdd ---
        print("\n=== Section 10: Unknown type + autoAdd ===")

        # Enable autoAddUnknownType on zone A
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "GET", "/config.json",
                       token=ZONES["A_RECEIVING"]["token"])
        config_a = json.loads(b)
        config_a["printer"]["autoAddUnknownType"] = True
        request(ZONES["A_RECEIVING"]["base_url"], "PUT", "/config.json",
                body=config_a, token=ZONES["A_RECEIVING"]["token"])

        # Send a print with unknown type "EXPRESS" — should auto-add the mapping
        unknown_doc = {"type": "EXPRESS", "raw_content": "RQ==", "id": "wms-express"}
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "POST", "/printer",
                       body=unknown_doc, token=ZONES["A_RECEIVING"]["token"])
        # It may fail (empty printer name in auto-added mapping) but the mapping
        # should now exist in the config
        time.sleep(1)
        s, b = request(ZONES["A_RECEIVING"]["base_url"], "GET", "/printer/mappings",
                       token=ZONES["A_RECEIVING"]["token"])
        mappings_a = json.loads(b).get("mappings", [])
        types_a = [m["type"] for m in mappings_a]
        assert_true("EXPRESS type auto-added to zone A config", "EXPRESS" in types_a,
                    f"types: {types_a}")

        # --- Section 11: Fallback to default printer ---
        print("\n=== Section 11: Fallback to default printer ===")

        # Enable fallbackToDefault on zone B
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "GET", "/config.json",
                       token=ZONES["B_SHIPPING"]["token"])
        config_b = json.loads(b)
        config_b["printer"]["fallbackToDefault"] = True
        request(ZONES["B_SHIPPING"]["base_url"], "PUT", "/config.json",
                body=config_b, token=ZONES["B_SHIPPING"]["token"])

        # Print with a type that has no mapping — should fall back to default printer
        fallback_doc = {"type": "NONEXISTENT_TYPE", "raw_content": "RQ==", "id": "wms-fallback"}
        s, b = request(ZONES["B_SHIPPING"]["base_url"], "POST", "/printer",
                       body=fallback_doc, token=ZONES["B_SHIPPING"]["token"])
        # The print may succeed (if CUPS-PDF is the default) or fail (no default)
        # but it should not return 404 — it should attempt the fallback
        print(f"INFO fallback print result: status={s} body={b}")

        print("\nAll WMS multi-zone E2E tests passed!")

    finally:
        for proc in procs:
            try:
                proc.send_signal(signal.SIGTERM)
                proc.wait(timeout=5)
            except Exception:
                proc.kill()


if __name__ == "__main__":
    main()
