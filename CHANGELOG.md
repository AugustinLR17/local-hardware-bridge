# Changelogs

## 1.0.3

Security hardening, bug fixes, new features and the first test suite.
**Security defaults are unchanged** — authentication stays disabled and CORS stays
open by default; everything below adds configurability, it does not lock you out.

### Security (opt-in)
- Configurable CORS allow-list (`server.cors`, default open as before).
- Path-traversal hardening on downloaded/print documents.
- SSRF mitigations: URL scheme restricted to http/https, type check on the URL
  path (no `#`/`?` bypass), optional `downloader.blockPrivateNetworks`.
- Constant-time token/password comparison; empty token never authorizes.
- TLS private key written with owner-only permissions; trust-all TLS (when
  `ignoreTLSCertificateError` is set) scoped to the single connection.

### Bug fixes
- A failed port bind no longer kills the whole process; serial I/O is thread-safe
  (no dropped writes) and no longer busy-spins at 100% CPU; downloader config
  changes apply without a JVM restart; downloaded files are cleaned up on success;
  config saves are atomic; restart is guarded against overlap.

### Features
- Enriched `/system/health` (printer/serial enabled, connections, uptime).
- Web UI: token handling + 401 flow, save/restart feedback, test print/serial,
  live serial monitor, periodic health refresh, dark mode, accessibility.
- TUI: `--token`/`LHB_TOKEN` auth, navigation keys, service toggles and restart.

### Tests
- First JUnit unit tests and additional e2e cases (health, path traversal, restart).

## 1.0.2

### Restart fixes
- The **Restart** action (Web UI `POST /system/restart.json` and the tray menu)
  now works reliably. The HTTP endpoint no longer stops Jetty from its own worker
  thread (which deadlocked); it responds first, then restarts on a dedicated
  thread.
- Desktop notifications now survive a restart: services that must outlive a
  restart (the GUI notification listener) are re-attached automatically.

### Windows packaging fixes
- The Windows release is now a **single self-contained `.exe`** installer (NSIS),
  bundling a Java runtime — no JDK/JRE required on the target machine.
- The app now launches **windowless** (no terminal/console window) via a native
  launcher.
- **Auto-start at boot** is registered reliably by the installer via
  `HKCU\...\Run` pointing at the windowless launcher, replacing the unreliable
  runtime `reg add` (which failed with exit code 1 and flashed a console).
- New `Launcher` entry point anchors the working directory to the install dir
  (`AppHome.anchor()`), so `config.json`, `log/` and `tls/` are found even when
  the app is started from a shortcut or auto-start (where the working directory
  is `system32`).
- Dropped the `.msi` artifact; the `.exe` is the single supported installer.

## From 0.x to 1.0.0

- 1.0 is a major rewrite, while maintain compatibility with existing WebApps
- Settings will lost after upgrade, please reconfigure via "Web UI" or "Web API"

### Feature changes
- Added per printer settings (Auto-rotate, DPI...)
- Added per serial port settings (Baud-rate, data bits, stop bit, parity bit, charset. binary mode, multi-bytes mode)
- Added "Web UI" for configuration, replacing "Configurator"
- Added "Web API", a HTTP API for WebApp to configure directly without using "Web UI" or "Configurator"
- Config file renamed from "setting.json" to "config.json", which is in different format

### Internal changes
- Removed "Configurator"
- Removed undocumented feature "Cloud Proxy"
- Removed usage of JavaFX
- Rewrite config code
- Implementation of WebSocket changed from "Java-WebSocket" to "Javalin"
- Internal dataflow optimization
- Simplified code by using "Lombok"
- Upgrade Java version from 8 to 21
- Many dependencies upgrades and security fixes
