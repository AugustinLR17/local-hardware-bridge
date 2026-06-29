#!/usr/bin/env python3
"""
Auto-update E2E test.

Tests the update REST API endpoints against a running bridge instance:
1. GET /system/update/status — initial status (before any check)
2. GET /system/update/check — trigger a check (may fail if no network, but
   the endpoint must respond with a valid JSON structure)
3. GET /system/update.json — get update config section
4. PUT /system/update.json — update config (e.g. disable checks, change interval)
5. POST /system/update/download — should return 409 if no update available
6. POST /system/update/apply — should return 409 if no pending update
7. POST /system/update/rollback — should not crash even without a backup
8. Verify update config is persisted in /config.json
9. Verify update settings can be toggled and read back
10. Verify the status DTO contains all expected fields
"""

import json
import os
import signal
import subprocess
import sys
import time
import urllib.request
import urllib.error


BASE_URL = "http://127.0.0.1:12212"


def request(method, path, body=None, headers=None):
    url = BASE_URL + path
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
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


def assert_in(name, substring, string):
    if substring not in string:
        print(f"FAIL {name}: '{substring}' not found in '{string}'")
        sys.exit(1)
    print(f"OK   {name}")


def wait_for_bridge(timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            s, b = request("GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                return True
        except Exception:
            pass
        time.sleep(0.5)
    return False


def wait_for_port_free(port, timeout=60):
    import socket
    deadline = time.time() + timeout
    while time.time() < deadline:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1)
        try:
            sock.connect(("127.0.0.1", port))
            sock.close()
        except (ConnectionRefusedError, OSError):
            return True
        time.sleep(1)
    return False


def main():
    jar_path = "/app/local-hardware-bridge.jar"
    if not os.path.exists(jar_path):
        print(f"FAIL: JAR not found at {jar_path}")
        sys.exit(1)

    # Wait for port to be free (previous test suites may have used it)
    if not wait_for_port_free(12212):
        print("FAIL: port 12212 is still in use")
        sys.exit(1)

    # Start the bridge with a dedicated workdir
    workdir = "/tmp/bridge-update"
    os.makedirs(workdir, exist_ok=True)

    # Copy the JAR so AppHome.anchor() works
    import shutil
    local_jar = os.path.join(workdir, "bridge.jar")
    shutil.copy2(jar_path, local_jar)

    # Write a config with update enabled but interval=0 (startup-only, no background)
    config = {
        "server": {
            "address": "127.0.0.1",
            "bind": "127.0.0.1",
            "port": 12212,
            "authentication": {"enabled": False, "token": None},
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
        "update": {
            "enabled": True,
            "autoDownload": False,
            "autoInstall": False,
            "includePrereleases": False,
            "checkIntervalHours": 0,
            "repository": "AugustinLR17/local-hardware-bridge",
            "channel": "stable",
        },
    }
    with open(os.path.join(workdir, "config.json"), "w") as f:
        json.dump(config, f)

    proc = subprocess.Popen(
        ["java", "-Dlhb.server=true", "-cp", local_jar,
         "io.github.augustinlr17.localhardwarebridge.Server"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        cwd=workdir,
    )

    try:
        # --- Wait for bridge to start ---
        if not wait_for_bridge():
            print("FAIL: bridge did not become healthy")
            sys.exit(1)
        print("OK   bridge started")

        # --- 1. GET /system/update/status (initial) ---
        print("\n--- Update status (initial) ---")
        s, b = request("GET", "/system/update/status")
        assert_eq("update status endpoint returns 200", s, 200)
        status = json.loads(b)
        assert_true("status has currentVersion", "currentVersion" in status)
        assert_true("status has checked field", "checked" in status)
        assert_true("status has updateAvailable field", "updateAvailable" in status)
        assert_true("status has downloading field", "downloading" in status)
        assert_true("status has pendingRestart field", "pendingRestart" in status)
        assert_true("status has error field", "error" in status)
        print(f"INFO initial status: {json.dumps(status, indent=2)}")

        # --- 2. GET /system/update/check ---
        print("\n--- Update check ---")
        s, b = request("GET", "/system/update/check")
        # The check might succeed (if there's network and a newer release) or
        # fail (no network in Docker), but the endpoint must return valid JSON.
        assert_true("update check returns 200 or 500", s in (200, 500),
                    f"got {s}")
        if s == 200:
            check_result = json.loads(b)
            assert_true("check result has currentVersion", "currentVersion" in check_result)
            assert_true("check result has updateAvailable", "updateAvailable" in check_result)
            print(f"INFO check result: {json.dumps(check_result, indent=2)}")
        else:
            # 500 is acceptable if there's no network in Docker
            print(f"INFO check returned 500 (expected if no network): {b}")

        # --- 3. GET /system/update.json (config section) ---
        print("\n--- Update config section ---")
        s, b = request("GET", "/system/update.json")
        assert_eq("get update config status", s, 200)
        update_config = json.loads(b)
        assert_true("update config has enabled", "enabled" in update_config)
        assert_true("update config has autoDownload", "autoDownload" in update_config)
        assert_true("update config has autoInstall", "autoInstall" in update_config)
        assert_true("update config has includePrereleases", "includePrereleases" in update_config)
        assert_true("update config has checkIntervalHours", "checkIntervalHours" in update_config)
        assert_true("update config has repository", "repository" in update_config)
        assert_true("update config has channel", "channel" in update_config)
        assert_eq("update config enabled", update_config["enabled"], True)
        assert_eq("update config autoDownload", update_config["autoDownload"], False)
        assert_eq("update config autoInstall", update_config["autoInstall"], False)
        assert_eq("update config checkIntervalHours", update_config["checkIntervalHours"], 0)
        assert_eq("update config repository", update_config["repository"],
                  "AugustinLR17/local-hardware-bridge")
        assert_eq("update config channel", update_config["channel"], "stable")

        # --- 4. PUT /system/update.json (modify config) ---
        print("\n--- Modify update config ---")
        new_config = dict(update_config)
        new_config["autoDownload"] = True
        new_config["checkIntervalHours"] = 48
        s, b = request("PUT", "/system/update.json", new_config)
        assert_eq("put update config status", s, 200)
        updated = json.loads(b)
        assert_eq("updated autoDownload", updated["autoDownload"], True)
        assert_eq("updated checkIntervalHours", updated["checkIntervalHours"], 48)

        # Verify it persisted
        s, b = request("GET", "/system/update.json")
        persisted = json.loads(b)
        assert_eq("persisted autoDownload", persisted["autoDownload"], True)
        assert_eq("persisted checkIntervalHours", persisted["checkIntervalHours"], 48)

        # Reset for subsequent tests
        new_config["autoDownload"] = False
        new_config["checkIntervalHours"] = 0
        request("PUT", "/system/update.json", new_config)

        # --- 5. Verify update config appears in /config.json ---
        print("\n--- Update config in full config.json ---")
        s, b = request("GET", "/config.json")
        assert_eq("get full config status", s, 200)
        full_config = json.loads(b)
        assert_true("full config has update section", "update" in full_config)
        assert_eq("full config update.enabled", full_config["update"]["enabled"], True)

        # --- 6. POST /system/update/download (should 409 if no update available) ---
        print("\n--- Download without available update ---")
        # First, check if an update is actually available
        s, b = request("GET", "/system/update/status")
        status = json.loads(b)
        if not status.get("updateAvailable", False):
            # No update available — download should return 409
            s, b = request("POST", "/system/update/download")
            assert_eq("download without update returns 409", s, 409)
            assert_in("download error message", "No update available", b)
            print("OK   download correctly rejected without available update")
        else:
            print("INFO update is available — skipping 409 test (would download)")

        # --- 7. POST /system/update/apply (should 409 if no pending update) ---
        print("\n--- Apply without pending update ---")
        s, b = request("POST", "/system/update/apply")
        assert_eq("apply without pending returns 409", s, 409)
        assert_in("apply error message", "No pending update", b)
        print("OK   apply correctly rejected without pending update")

        # --- 8. POST /system/update/rollback (should not crash) ---
        print("\n--- Rollback without backup ---")
        s, b = request("POST", "/system/update/rollback")
        # Rollback starts an async process — it should return 200 (rolling back)
        # even if there's no backup (the rollback will fail internally but not crash)
        assert_true("rollback returns 200 or 409", s in (200, 409),
                    f"got {s}")
        if s == 200:
            # Wait for the async restart to complete
            time.sleep(3)
            if not wait_for_bridge(timeout=30):
                print("WARN: bridge did not recover after rollback — this is OK if no backup existed")
            else:
                print("OK   bridge recovered after rollback attempt")
        else:
            print("OK   rollback rejected (already restarting or no restart needed)")

        # --- 9. Toggle update.enabled and verify ---
        print("\n--- Toggle update.enabled ---")
        s, b = request("GET", "/system/update.json")
        cfg = json.loads(b)
        original_enabled = cfg["enabled"]

        cfg["enabled"] = False
        s, b = request("PUT", "/system/update.json", cfg)
        assert_eq("disable update status", s, 200)

        s, b = request("GET", "/system/update.json")
        assert_eq("update disabled in config", json.loads(b)["enabled"], False)

        # Restore
        cfg["enabled"] = original_enabled
        request("PUT", "/system/update.json", cfg)

        # --- 10. Verify all status fields have correct types ---
        print("\n--- Status field types ---")
        s, b = request("GET", "/system/update/status")
        assert_eq("status endpoint status", s, 200)
        status = json.loads(b)
        assert_true("checked is bool", isinstance(status.get("checked"), bool))
        assert_true("updateAvailable is bool", isinstance(status.get("updateAvailable"), bool))
        assert_true("currentVersion is str",
                    isinstance(status.get("currentVersion"), str))
        assert_true("downloading is bool", isinstance(status.get("downloading"), bool))
        assert_true("pendingRestart is bool", isinstance(status.get("pendingRestart"), bool))
        assert_true("prerelease is bool", isinstance(status.get("prerelease"), bool))

        # --- 11. Verify update endpoints are listed in /config.json ---
        print("\n--- Update config persists through /config.json ---")
        s, b = request("PUT", "/system/update.json", {
            "enabled": True,
            "autoDownload": False,
            "autoInstall": False,
            "includePrereleases": True,
            "checkIntervalHours": 12,
            "repository": "AugustinLR17/local-hardware-bridge",
            "channel": "prerelease",
        })
        assert_eq("set prerelease config status", s, 200)

        s, b = request("GET", "/config.json")
        full = json.loads(b)
        assert_eq("prerelease config persisted", full["update"]["includePrereleases"], True)
        assert_eq("prerelease channel persisted", full["update"]["channel"], "prerelease")
        assert_eq("prerelease interval persisted", full["update"]["checkIntervalHours"], 12)

        # Reset
        request("PUT", "/system/update.json", {
            "enabled": True,
            "autoDownload": False,
            "autoInstall": False,
            "includePrereleases": False,
            "checkIntervalHours": 0,
            "repository": "AugustinLR17/local-hardware-bridge",
            "channel": "stable",
        })

        print("\nAll auto-update E2E tests passed!")

    finally:
        # Clean up
        try:
            proc.send_signal(signal.SIGTERM)
            proc.wait(timeout=5)
        except Exception:
            proc.kill()


if __name__ == "__main__":
    main()
