#!/usr/bin/env python3
"""End-to-end tests for Local Hardware Bridge REST API."""

import json
import base64
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
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def assert_eq(name, actual, expected):
    if actual != expected:
        print(f"FAIL {name}: expected {expected}, got {actual}")
        sys.exit(1)
    print(f"OK   {name}")


def main():
    # 1. Health check
    status, body = request("GET", "/system/health")
    assert_eq("health status", status, 200)
    health = json.loads(body)
    assert_eq("health status field", health.get("status"), "UP")

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

    # 6. Print via URL (file content)
    with open("/app/print-test.pdf", "rb") as f:
        pdf_b64 = base64.b64encode(f.read()).decode("utf-8")
    doc = {"type": "TEST", "url": "test.pdf", "file_content": pdf_b64, "id": "e2e-1"}
    status, body = request("POST", "/printer", doc)
    assert_eq("print status", status, 200)
    result = json.loads(body)
    assert_eq("print success", result.get("success"), True)

    # 7. Disable endpoint and verify it is blocked
    config_status, config_body = request("GET", "/config.json")
    assert_eq("get config status", config_status, 200)
    config = json.loads(config_body)
    config["security"]["endpoints"]["/printer"] = {"enabled": False, "password": ""}
    status, _ = request("PUT", "/config.json", config)
    assert_eq("disable printer status", status, 200)

    # Wait a moment for config to take effect
    time.sleep(1)

    status, body = request("POST", "/printer", doc)
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

    status, body = request("POST", "/printer", doc)
    assert_eq("printer without password status", status, 401)

    status, body = request("POST", "/printer", doc, {"Authorization": "Bearer secret123"})
    assert_eq("printer with password status", status, 200)
    result = json.loads(body)
    assert_eq("printer with password success", result.get("success"), True)

    print("\nAll E2E tests passed!")


if __name__ == "__main__":
    main()
