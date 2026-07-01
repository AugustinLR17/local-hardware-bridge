#!/usr/bin/env python3
"""
Extended E2E tests for Local Hardware Bridge REST API.

Covers endpoints not tested by test.py:
- GET /icon.png
- POST /system/notification
- GET/PUT /system/downloader.json
- GET/PUT /system/gui.json
- GET /system/serials.json
- Serial mapping CRUD (POST/PUT/DELETE /serial/mappings)
- PUT /serial/enabled
- GET /serial/status
- GET /serial/connections
- GET /system/connections
- GET/PUT /system/server.json
- Print with file_content (base64 image/PDF)
- Print with qty > 1
- Print with duplex/color/paperTray options
- GET /system/printers/{name}/trays.json
- Basic auth (password = token)
- Restart guard (double restart → 409)
- Print with unknown file type → error
- Printer mapping update/delete
"""

import json
import base64
import os
import sys
import time
import urllib.request
import urllib.error

BASE_URL = "http://127.0.0.1:57212"


def request(method, path, body=None, headers=None, raw_body=None, binary=False):
    url = BASE_URL + path
    if raw_body is not None:
        data = raw_body.encode("utf-8")
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
    else:
        data = None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None or raw_body is not None:
        req.add_header("Content-Type", "application/json")
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read()
            if binary:
                return resp.status, raw, dict(resp.headers)
            return resp.status, raw.decode("utf-8"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        raw = e.read()
        if binary:
            return e.code, raw, dict(e.headers)
        return e.code, raw.decode("utf-8", errors="replace"), dict(e.headers)
    except urllib.error.URLError as e:
        return -1, str(e), {}


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


def main():
    # Wait for bridge to be ready (test.py may have just restarted it)
    print("Waiting for bridge to be ready...")
    deadline = time.time() + 30
    ready = False
    while time.time() < deadline:
        try:
            s, b, h = request("GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                ready = True
                break
        except Exception:
            pass
        time.sleep(1)
    if not ready:
        print("FAIL: bridge did not become healthy")
        sys.exit(1)
    print("OK   bridge is ready")

    # --- 1. Icon endpoint ---
    print("\n--- Icon endpoint ---")
    s, b, h = request("GET", "/icon.png", binary=True)
    assert_eq("icon status", s, 200)
    assert_true("icon content-type is png", "image/png" in h.get("Content-Type", ""),
                f"got {h.get('Content-Type')}")
    assert_true("icon body is non-empty", len(b) > 0)
    # b is bytes when binary=True
    assert_true("icon starts with PNG magic", isinstance(b, bytes) and b[:4] == b'\x89PNG')

    # --- 2. Notification endpoint ---
    print("\n--- Notification endpoint ---")
    s, b, h = request("POST", "/system/notification",
                      body={"type": "INFO", "title": "E2E Test", "message": "Hello from E2E"})
    assert_eq("notification status", s, 200)
    assert_in("notification response", "sent", b)

    # --- 3. Downloader config ---
    print("\n--- Downloader config ---")
    s, b, h = request("GET", "/system/downloader.json")
    assert_eq("get downloader config", s, 200)
    downloader = json.loads(b)
    assert_true("downloader has path", "path" in downloader)
    assert_true("downloader has timeout", "timeout" in downloader)
    assert_true("downloader has ignoreTLSCertificateError", "ignoreTLSCertificateError" in downloader)
    assert_true("downloader has blockPrivateNetworks", "blockPrivateNetworks" in downloader)

    # Update downloader config
    downloader["timeout"] = 60.0
    s, b, h = request("PUT", "/system/downloader.json", body=downloader)
    assert_eq("put downloader config", s, 200)
    updated = json.loads(b)
    assert_eq("downloader timeout updated", updated["timeout"], 60.0)

    # Reset
    downloader["timeout"] = 30.0
    request("PUT", "/system/downloader.json", body=downloader)

    # --- 4. GUI config ---
    print("\n--- GUI config ---")
    s, b, h = request("GET", "/system/gui.json")
    assert_eq("get gui config", s, 200)
    gui = json.loads(b)
    assert_true("gui has notification", "notification" in gui)

    # Update GUI config
    gui["notification"]["enabled"] = False
    s, b, h = request("PUT", "/system/gui.json", body=gui)
    assert_eq("put gui config", s, 200)
    assert_eq("gui notification disabled", json.loads(b)["notification"]["enabled"], False)

    # --- 5. Serial ports listing ---
    print("\n--- Serial ports ---")
    s, b, h = request("GET", "/system/serials.json")
    assert_eq("get serials status", s, 200)
    serials = json.loads(b)
    assert_true("serials is a list", isinstance(serials, list))

    # --- 6. Serial mapping CRUD ---
    print("\n--- Serial mapping CRUD ---")
    # Add serial mapping
    serial_mapping = {
        "type": "SCALE",
        "name": "/dev/ttyUSB0",
        "baudRate": 9600,
        "numDataBits": 8,
        "numStopBits": 1,
        "parity": 0,
        "readMultipleBytes": True,
        "readCharset": "ISO-8859-1",
    }
    s, b, h = request("POST", "/serial/mappings", body=serial_mapping)
    assert_eq("add serial mapping", s, 200)
    serial_config = json.loads(b)
    assert_true("serial mapping added", len(serial_config.get("mappings", [])) > 0)

    # List serial mappings
    s, b, h = request("GET", "/serial/mappings")
    assert_eq("list serial mappings", s, 200)
    serial_mappings = json.loads(b).get("mappings", [])
    assert_true("serial mapping SCALE present", any(m["type"] == "SCALE" for m in serial_mappings))

    # Update serial mapping
    updated_serial = dict(serial_mapping)
    updated_serial["baudRate"] = 19200
    s, b, h = request("PUT", "/serial/mappings/SCALE", body=updated_serial)
    assert_eq("update serial mapping", s, 200)
    assert_eq("serial baud rate updated", json.loads(b)["mappings"][0]["baudRate"], 19200)

    # Delete serial mapping
    s, b, h = request("DELETE", "/serial/mappings/SCALE")
    assert_eq("delete serial mapping", s, 200)
    serial_mappings = json.loads(b).get("mappings", [])
    assert_true("serial mapping SCALE deleted", not any(m["type"] == "SCALE" for m in serial_mappings))

    # --- 7. Serial enable/disable ---
    print("\n--- Serial enable/disable ---")
    s, b, h = request("PUT", "/serial/enabled", body={"enabled": False})
    assert_eq("disable serial", s, 200)
    assert_eq("serial disabled", json.loads(b)["enabled"], False)

    s, b, h = request("PUT", "/serial/enabled", body={"enabled": True})
    assert_eq("enable serial", s, 200)
    assert_eq("serial enabled", json.loads(b)["enabled"], True)

    # --- 8. Serial status ---
    print("\n--- Serial status ---")
    s, b, h = request("GET", "/serial/status")
    assert_eq("serial status", s, 200)
    status_list = json.loads(b)
    assert_true("serial status is a list", isinstance(status_list, list))

    # --- 9. Serial connections ---
    print("\n--- Serial connections ---")
    s, b, h = request("GET", "/serial/connections")
    assert_eq("serial connections", s, 200)

    # --- 10. System connections ---
    print("\n--- System connections ---")
    s, b, h = request("GET", "/system/connections")
    assert_eq("system connections", s, 200)
    connections = json.loads(b)
    assert_true("connections is a dict", isinstance(connections, dict))

    # --- 11. Server config section ---
    print("\n--- Server config section ---")
    s, b, h = request("GET", "/system/server.json")
    assert_eq("get server config", s, 200)
    server_cfg = json.loads(b)
    assert_true("server config has port", "port" in server_cfg)
    assert_true("server config has bind", "bind" in server_cfg)
    assert_true("server config has authentication", "authentication" in server_cfg)
    assert_true("server config has tls", "tls" in server_cfg)
    assert_true("server config has cors", "cors" in server_cfg)

    # --- 12. Print with file_content (base64 image) ---
    print("\n--- Print with file_content (image) ---")
    # Create a minimal 1x1 PNG
    png_header = base64.b64encode(
        b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde'
        b'\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82'
    ).decode("ascii")

    image_doc = {
        "type": "TEST",
        "file_content": png_header,
        "url": "test-image.png",
        "id": "e2e-image",
    }
    s, b, h = request("POST", "/printer", body=image_doc)
    assert_eq("print image status", s, 200)
    result = json.loads(b)
    print(f"INFO print image result: {result}")

    # --- 13. Print with qty > 1 ---
    print("\n--- Print with qty > 1 ---")
    raw_doc_qty = {
        "type": "TEST",
        "raw_content": "SGVsbG8gV29ybGQ=",
        "id": "e2e-qty",
        "qty": 3,
    }
    s, b, h = request("POST", "/printer", body=raw_doc_qty)
    assert_eq("print with qty=3 status", s, 200)
    result = json.loads(b)
    assert_eq("print with qty success", result.get("success"), True)

    # --- 14. Print with duplex/color/paperTray options ---
    print("\n--- Print with duplex/color/paperTray ---")
    raw_doc_opts = {
        "type": "TEST",
        "raw_content": "SGVsbG8gV29ybGQ=",
        "id": "e2e-opts",
        "duplex": True,
        "color": True,
        "paper_tray": "MAIN",
    }
    s, b, h = request("POST", "/printer", body=raw_doc_opts)
    assert_eq("print with options status", s, 200)
    result = json.loads(b)
    assert_eq("print with options success", result.get("success"), True)

    # --- 15. Printer trays endpoint ---
    print("\n--- Printer trays ---")
    # Get trays for CUPS-PDF (or any available printer)
    s, b, h = request("GET", "/system/printers.json")
    printers = json.loads(b)
    if printers:
        printer_name = printers[0]["name"]
        s, b, h = request("GET", f"/system/printers/{printer_name}/trays.json")
        # May return 200 with trays or 200 with empty list
        assert_true("printer trays status 200", s == 200, f"got {s}: {b}")
        if s == 200:
            trays = json.loads(b)
            assert_true("trays is a list", isinstance(trays, list))
            print(f"INFO trays for {printer_name}: {trays}")
    else:
        print("WARN no printers found, skipping tray test")

    # --- 16. Printer tray for non-existent printer ---
    print("\n--- Printer trays for non-existent printer ---")
    s, b, h = request("GET", "/system/printers/NONEXISTENT_PRINTER_12345/trays.json")
    assert_eq("non-existent printer trays returns 404", s, 404)

    # --- 17. Basic auth (password = token) ---
    # NOTE: Global auth is captured at server start, so changing it requires a restart.
    print("\n--- Basic auth ---")
    # Get a FRESH config from the server
    s, b, h = request("GET", "/config.json")
    config = json.loads(b)

    # Save a deep copy of the original auth config for restoration
    original_auth = json.loads(json.dumps(config.get("server", {}).get("authentication", {})))
    original_security = json.loads(json.dumps(config.get("security", {}).get("endpoints", {})))

    config["server"]["authentication"]["enabled"] = True
    config["server"]["authentication"]["token"] = "basictoken123"
    s, b, h = request("PUT", "/config.json", body=config)
    assert_eq("enable auth for basic test", s, 200)

    # Restart so the auth change takes effect
    s, b, h = request("POST", "/system/restart.json")
    assert_true("restart for auth accepted", s in (200, 202), f"got {s}")
    time.sleep(2)

    # Wait for restart
    healthy = False
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            s, b, h = request("GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                healthy = True
                break
        except Exception:
            pass
        time.sleep(1)
    assert_true("server healthy after auth restart", healthy)

    # Request without auth → 401
    s, b, h = request("GET", "/system/version.json")
    assert_eq("no auth rejected (401)", s, 401)

    # Request with Bearer token
    s, b, h = request("GET", "/system/version.json", headers={"Authorization": "Bearer basictoken123"})
    assert_eq("bearer auth accepted", s, 200)

    # Request with Basic auth (password = token, username ignored)
    import base64 as b64
    basic_creds = b64.b64encode(b"anyuser:basictoken123").decode("ascii")
    s, b, h = request("GET", "/system/version.json", headers={"Authorization": f"Basic {basic_creds}"})
    assert_eq("basic auth accepted", s, 200)

    # Health endpoint still works without auth
    s, b, h = request("GET", "/system/health")
    assert_eq("health without auth (exempt)", s, 200)

    # Disable auth: get fresh config (with token since auth is active), modify, save, restart
    s, b, h = request("GET", "/config.json", headers={"Authorization": "Bearer basictoken123"})
    config = json.loads(b)
    config["server"]["authentication"]["enabled"] = False
    config["server"]["authentication"]["token"] = original_auth.get("token")
    config["security"]["endpoints"] = original_security
    request("PUT", "/config.json", body=config, headers={"Authorization": "Bearer basictoken123"})

    # Restart to apply the auth disable
    s, b, h = request("POST", "/system/restart.json", headers={"Authorization": "Bearer basictoken123"})
    assert_true("restart to restore auth accepted", s in (200, 202, 409), f"got {s}")

    # Wait for restart
    deadline = time.time() + 60
    healthy = False
    while time.time() < deadline:
        try:
            s, b, h = request("GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                healthy = True
                break
        except Exception:
            pass
        time.sleep(1)
    assert_true("server healthy after restore restart", healthy)
    time.sleep(3)

    # Verify auth is actually disabled now
    s, b, h = request("GET", "/system/version.json")
    assert_eq("auth disabled after restore", s, 200)

    # --- 18. Restart guard (double restart → 409) ---
    # NOTE: This test causes a server restart. It must be near the end
    # so subsequent tests don't hit a restarting server.
    print("\n--- Restart guard ---")
    s, b, h = request("POST", "/system/restart.json")
    assert_true("first restart accepted", s in (200, 202), f"got {s}")
    # Immediately send a second restart. Two valid outcomes:
    #   409 — server still up, restarting flag set ("already restarting")
    #   -1  — server already stopped by the restart thread (connection refused)
    # Both prove a restart is in progress; 409 is not guaranteed because
    # stop() can shut down Jetty before this request reaches the server.
    s, b, h = request("POST", "/system/restart.json")
    assert_true("second restart rejected (409 or connection refused)", s in (409, -1), f"got {s}")
    if s == 409:
        assert_in("already restarting message", "already restarting", b)

    # Wait for restart to complete
    healthy = False
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            s, b, h = request("GET", "/system/health")
            if s == 200 and json.loads(b).get("status") == "UP":
                healthy = True
                break
        except Exception:
            pass
        time.sleep(1)
    assert_true("server healthy after restart", healthy)

    # Give the server a moment to fully initialize services
    time.sleep(2)

    # --- 19. Unknown file type error ---
    print("\n--- Unknown file type ---")
    unknown_doc = {"type": "TEST", "url": "http://example.com/file.txt", "id": "e2e-unknown"}
    s, b, h = request("POST", "/printer", body=unknown_doc)
    assert_eq("unknown file type status", s, 200)
    result = json.loads(b)
    assert_eq("unknown file type fails", result.get("success"), False)
    assert_in("unknown file type message", "Unknown file type", result.get("message", ""))

    # --- 20. Printer mapping update and delete ---
    print("\n--- Printer mapping update/delete ---")
    # Add a mapping to update
    s, b, h = request("POST", "/printer/mappings",
                      body={"type": "UPDATE_TEST", "name": "CUPS-PDF",
                            "autoRotate": False, "resetImageableArea": True, "forceDPI": 0})
    assert_eq("add mapping for update test", s, 200)

    # Update it
    updated_mapping = {"type": "UPDATE_TEST", "name": "CUPS-PDF",
                       "autoRotate": True, "resetImageableArea": False, "forceDPI": 300}
    s, b, h = request("PUT", "/printer/mappings/UPDATE_TEST", body=updated_mapping)
    assert_eq("update mapping status", s, 200)
    mappings = json.loads(b).get("mappings", [])
    updated = [m for m in mappings if m["type"] == "UPDATE_TEST"]
    if updated:
        assert_eq("mapping autoRotate updated", updated[0]["autoRotate"], True)
        assert_eq("mapping forceDPI updated", updated[0]["forceDPI"], 300)

    # Delete it
    s, b, h = request("DELETE", "/printer/mappings/UPDATE_TEST")
    assert_eq("delete mapping status", s, 200)
    mappings = json.loads(b).get("mappings", [])
    assert_true("mapping deleted", not any(m["type"] == "UPDATE_TEST" for m in mappings))

    # Delete non-existent mapping → 404
    s, b, h = request("DELETE", "/printer/mappings/NONEXISTENT")
    assert_eq("delete non-existent mapping (404)", s, 404)

    # Update non-existent mapping → 404
    s, b, h = request("PUT", "/printer/mappings/NONEXISTENT",
                      body={"type": "NONEXISTENT", "name": "x",
                            "autoRotate": False, "resetImageableArea": True, "forceDPI": 0})
    assert_eq("update non-existent mapping (404)", s, 404)

    # --- 21. CORS headers ---
    print("\n--- CORS headers ---")
    s, b, h = request("GET", "/system/health")
    # Default config has allowAllOrigins=true, so CORS should allow any origin
    # Check that the response has appropriate CORS headers on a preflight
    req = urllib.request.Request(BASE_URL + "/system/health", method="OPTIONS")
    req.add_header("Origin", "http://example.com")
    req.add_header("Access-Control-Request-Method", "GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            cors_headers = {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        cors_headers = {k.lower(): v for k, v in e.headers.items()}
    # CORS may or may not be present depending on Javalin config, just log
    print(f"INFO CORS headers: {cors_headers}")

    # --- 22. Print when printer disabled → 503 ---
    print("\n--- Print disabled 503 ---")
    s, b, h = request("PUT", "/printer/enabled", body={"enabled": False})
    assert_eq("disable printer service", s, 200)

    s, b, h = request("POST", "/printer",
                      body={"type": "TEST", "raw_content": "SGVsbG8=", "id": "e2e-disabled"})
    assert_eq("print when disabled returns 503", s, 503)

    s, b, h = request("PUT", "/printer/enabled", body={"enabled": True})
    assert_eq("re-enable printer service", s, 200)

    print("\nAll extended E2E tests passed!")


if __name__ == "__main__":
    main()