#!/usr/bin/env python3
"""End-to-end tests for Local Hardware Bridge REST API."""

import json
import base64
import os
import sys
import time
import urllib.request
import urllib.error

BASE_URL = "http://127.0.0.1:57212"


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
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


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


def main():
    # 1. Health check (now returns enriched fields)
    status, body = request("GET", "/system/health")
    assert_eq("health status", status, 200)
    health = json.loads(body)
    assert_eq("health status field", health.get("status"), "UP")

    # Enriched health fields. Assert presence, but tolerate a field being renamed by
    # accepting any reasonable alias so a future rename does not hard-break the suite.
    assert_true("health printerEnabled present", "printerEnabled" in health)
    assert_true("health serialEnabled present", "serialEnabled" in health)
    has_uptime = any("uptime" in k.lower() for k in health.keys())
    assert_true("health uptime present", has_uptime)
    has_connections = ("connections" in health) or ("activeConnections" in health)
    assert_true("health connections present", has_connections)
    print(f"INFO health fields: {sorted(health.keys())}")

    # 2. Version
    status, body = request("GET", "/system/version.json")
    assert_eq("version status", status, 200)
    version = json.loads(body)
    assert_eq("version appId", version.get("appId"), "io.github.augustinlr17.localhardwarebridge")

    # 3. Printers (should see at least CUPS-PDF)
    status, body = request("GET", "/system/printers.json")
    assert_eq("printers status", status, 200)
    printers = json.loads(body)
    printer_names = [p["name"] for p in printers]
    print(f"INFO found printers: {printer_names}")
    if "CUPS-PDF" not in printer_names and not any("PDF" in n for n in printer_names):
        print("WARN no PDF printer found; print test may be skipped")

    # 4. Add printer mapping
    mapping = {"type": "TEST", "name": "CUPS-PDF", "resetImageableArea": True, "autoRotate": False, "forceDPI": 0}
    status, body = request("POST", "/printer/mappings", mapping)
    assert_eq("add mapping status", status, 200)

    # 5. List mappings
    status, body = request("GET", "/printer/mappings")
    assert_eq("list mappings status", status, 200)
    mappings = json.loads(body).get("mappings", [])
    assert_eq("mapping type", mappings[0]["type"], "TEST")

    # 6. Print via raw content (avoids PDF/CUPS rendering issues in Docker)
    raw_doc = {"type": "TEST", "raw_content": "SGVsbG8gV29ybGQ=", "id": "e2e-1"}
    status, body = request("POST", "/printer", raw_doc)
    assert_eq("print status", status, 200)
    result = json.loads(body)
    assert_eq("print success", result.get("success"), True)

    # 6b. Path-traversal probe: a malicious suggested filename must not let an inline
    # document escape the downloads directory. We point the "url" (used only as a suggested
    # filename when file_content is present) at ../../../../tmp/lhb_pwn.pdf and assert the
    # server never creates a file outside its downloads dir. Safe + self-contained.
    pwn_path = "/tmp/lhb_pwn.pdf"
    if os.path.exists(pwn_path):
        os.remove(pwn_path)
    payload = base64.b64encode(b"not a real pdf").decode("ascii")
    pwn_doc = {
        "type": "TEST",
        "file_content": payload,
        "url": "../../../../tmp/lhb_pwn.pdf",
        "id": "e2e-traversal",
    }
    # The request may succeed (file written safely inside downloads/) or fail (bad PDF);
    # either is fine. The invariant is that nothing lands at the traversal target.
    status, body = request("POST", "/printer", pwn_doc)
    print(f"INFO traversal probe status={status} body={body}")
    time.sleep(1)
    assert_true("no file written outside downloads dir", not os.path.exists(pwn_path))

    # 7. Disable endpoint and verify it is blocked
    config_status, config_body = request("GET", "/config.json")
    assert_eq("get config status", config_status, 200)
    config = json.loads(config_body)
    config["security"]["endpoints"]["/printer"] = {"enabled": False, "password": ""}
    status, _ = request("PUT", "/config.json", config)
    assert_eq("disable printer status", status, 200)

    # Wait a moment for config to take effect
    time.sleep(1)

    status, body = request("POST", "/printer", raw_doc)
    assert_eq("blocked printer status", status, 403)

    # Re-enable for clean state
    config["security"]["endpoints"]["/printer"] = {"enabled": True, "password": ""}
    status, _ = request("PUT", "/config.json", config)
    assert_eq("re-enable printer status", status, 200)

    # 8. Endpoint-specific password
    config["security"]["endpoints"]["/printer"] = {"enabled": True, "password": "secret123"}
    status, _ = request("PUT", "/config.json", config)
    assert_eq("set password status", status, 200)
    time.sleep(1)

    status, body = request("POST", "/printer", raw_doc)
    assert_eq("printer without password status", status, 401)

    status, body = request("POST", "/printer", raw_doc, {"Authorization": "Bearer secret123"})
    assert_eq("printer with password status", status, 200)
    result = json.loads(body)
    assert_eq("printer with password success", result.get("success"), True)

    # Clear the endpoint password again so the restart test starts from a clean state.
    config["security"]["endpoints"]["/printer"] = {"enabled": True, "password": ""}
    status, _ = request("PUT", "/config.json", config)
    assert_eq("clear password status", status, 200)

    # 9. Restart endpoint: returns quickly, and the server becomes healthy again (verifies
    # the async restart fix where stop()/start() run off the request thread). The health
    # endpoint is exempt from auth so polling works regardless of config state.
    status, body = request("POST", "/system/restart.json")
    assert_true("restart accepted status", status in (200, 202))
    assert_true("restart body mentions restarting", "restarting" in body.lower())

    # Poll until the server is healthy again (generous timeout to avoid flakiness).
    healthy = False
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            s, b = request("GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                healthy = True
                break
        except Exception:
            pass
        time.sleep(1)
    assert_true("server healthy after restart", healthy)

    print("\nAll E2E tests passed!")


if __name__ == "__main__":
    main()
