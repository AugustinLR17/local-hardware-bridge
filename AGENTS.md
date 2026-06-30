# AGENTS.md

## Project Overview

Local Hardware Bridge — a Java 21 desktop application that exposes local printer and serial port access to web browsers via WebSocket and HTTP APIs. Commonly used for web-based POS systems (silent receipt printing, ESC/POS), WMS systems (weight scale reading, delivery note printing), and any web app needing serial port I/O.

A separate Go TUI admin client lives in `tui/` and is built cross-platform in CI.

## Build & Run Commands

### Prerequisites
- JDK 21 (Eclipse Temurin 21 recommended; `jpackage` is needed for native packaging)
- Gradle (wrapper included: `./gradlew`)
- Go 1.22+ (only for the TUI in `tui/`)

### Build
```bash
./gradlew build        # compiles, runs unit tests, produces shadow JAR
./gradlew shadowJar    # just the fat JAR (build depends on this)
```
Output: `build/libs/local-hardware-bridge-2.0.0.jar` (shadow JAR; `module-info.class` is excluded from it to avoid JPMS conflicts with bundled deps).

### Run
- **Default** (via Launcher → GUI): `./gradlew run` or `java -jar build/libs/local-hardware-bridge-2.0.0.jar`
- **GUI mode** (system tray icon): `java -cp build/libs/local-hardware-bridge-2.0.0.jar io.github.augustinlr17.localhardwarebridge.GUI`
- **Server-only mode** (headless): `java -cp build/libs/local-hardware-bridge-2.0.0.jar io.github.augustinlr17.localhardwarebridge.Server` or `java -Dlhb.server=true -jar build/libs/local-hardware-bridge-2.0.0.jar`
- Launcher dispatch: `-Dlhb.server=true` → headless server; otherwise → GUI. `-Dlhb.headless=true` forces headless within GUI mode.

### Tests

**Unit tests** (JUnit 4, run by `./gradlew build` / `./gradlew test`):
```bash
./gradlew test
```
Over 30 test classes exist in `src/test/`, covering:
- `dtos/` — Config defaults, Jackson round-trips, unknown-property tolerance, nested DTOs (CORS, Security, EndpointRule, mappings, Downloader, TLS), ReleaseInfo parsing, UpdateStatusDTO, VersionDTO, NotificationDTO, SystemDTOs
- `services/` — ConfigService load/save round-trips (atomicity, temp file cleanup, complex config), DocumentService path-traversal/SSRF/prepare/delete, MappingCrudLogic, UpdateService (status, asset lookup, cleanup, scheduler, pending detection)
- `websocketservices/` — PrinterWebSocketService file-type detection (PDF/image spoofing via query/fragment), dispatch, per-type locks, searchPrinterForType; SerialWebSocketService channel naming, BINARY charset
- `utils/` — VersionComparator (semver parsing, pre-release precedence, edge cases), AnnotatedPrintable, AnnotatedPrintableAnnotation, CertificateGenerator, ImagePrintable, ThreadUtil
- `responses/` — PrintDocument/PrintResult construction, serialization, snake_case deserialization, round-trips
- Root package — Constants, AppHome, CrossBridgeAuth (token isolation, constant-time comparison), ServerChannelRouting (pub/sub, wildcard, binary), ServerRestartGuard (AtomicBoolean CAS), ServerMessageBroadcast

Tests are hermetic: no network, no server bind, no real printing. Many tests use reflection to test private methods (`constantTimeEquals`, `extractBearerToken`, `searchPrinterForType`, `getOutputFile`, `download`, `verifyPublicHost`, `findJarAsset`, etc.). Some tests that call `save()` create `config.json` in the CWD (harmless — it's git-ignored).

**E2E tests** (Docker-based, Python harness against a running bridge + CUPS-PDF):
```bash
./gradlew shadowJar
docker build -f e2e/Dockerfile -t lhb-e2e .
docker run --rm lhb-e2e
```
The E2E suite (`e2e/test.py`) exercises: `/system/health`, version, printer listing, printer mapping CRUD, raw print, path-traversal probe, endpoint disable/enable, per-endpoint password auth, and async restart recovery. Runs in CI via `.github/workflows/e2e.yml`.

### TUI (Go)
```bash
cd tui && go build -o lhb-tui .
./lhb-tui --server http://127.0.0.1:57212 [--token my-secret-token]
```
CI enforces `gofmt` formatting and `go vet`. The `LHB_TOKEN` env var is an alternative to `--token`.

### Native Packaging
- **Windows installer**: `./gradlew createWindowsApp` (jpackage app-image) then `makensis /DPRODUCT_VERSION=<ver> install.nsi` → `lhb.exe`. Bundles a JRE via jpackage; the launcher is windowless (GUI subsystem, no console). Code signing of the launcher and installer is to be wired via SignPath (Azure Trusted Signing was removed); current CI builds are unsigned.
- **Linux DEB**: `./gradlew createLinuxApp` (jpackage `--type deb`)
- **Linux RPM**: `./gradlew createRpmApp` (jpackage `--type rpm`)
- **Linux AppImage**: built in CI via linuxdeploy (release workflow)
- **macOS .app**: built in CI via jpackage (release workflow, icon converted to .icns via sips/iconutil)

## CI Workflows (`.github/workflows/`)

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | push/PR to master | Build + unit test (ubuntu), compile-only check (windows/macos), TUI gofmt + vet + build |
| `e2e.yml` | push/PR to master, `v*` tags | Docker E2E tests |
| `release.yml` | `v*` tags | Fat JAR + cross-compiled TUI binaries + Windows installer (signed) + Linux AppImage + macOS app. **Verifies tag version matches `build.gradle` version** — fails the build on mismatch. |

## Architecture & Control Flow

### Entry Points
- `Launcher.main()` — the packaged `Main-Class`. Has **no** logging/config static deps so it can call `AppHome.anchor()` (sets `user.dir` to the install dir, so `config.json`/`log/`/`tls/` resolve correctly under shortcut/auto-start launches with CWD=`system32`) **before** any other app class loads. Then dispatches to `GUI` (default) or `Server` (`-Dlhb.server=true`).
- `GUI.main()` — launches `Server.start()`, then creates a system tray icon (Windows/macOS) or runs headless (Linux). Registers itself as a persistent `WebSocketServiceInterface` on the `/notification` channel to display desktop notifications. On Windows, re-spawns under `javaw.exe` to hide the console. On Linux, offers systemd service install. On macOS, offers launchd service install.
- `Server.main()` — headless mode, just calls `Server.start()`. Also calls `AppHome.anchor()` defensively (idempotent).
- **Legacy shims**: `tigerworkshop.webapphardwarebridge.GUI` and `tigerworkshop.webapphardwarebridge.Server` delegate to the new package, so old scripts/systemd units/launchd plists keep working. `module-info.java` exports this package.

### Core Messaging Pattern
The app uses a **pub/sub channel model** with two interfaces:

- `WebSocketServiceInterface` — implemented by services (Printer, Serial, GUI). Each service declares a `getChannel()` string and receives messages via `messageToService()`.
- `WebSocketServerInterface` — implemented by `Server`. Routes messages between WebSocket clients and services, and between services themselves.

**Channel routing in `Server`:**
- `socketChannelSubscriptions` — maps channel names → queues of `WsContext` (browser WebSocket connections)
- `serviceChannelSubscriptions` — maps channel names → queues of `WebSocketServiceInterface` (backend services)
- Messages from WebSocket clients → `messageToService(channel, message)` → dispatched to all services subscribed to that channel
- Messages from services → `messageToServer(channel, message)` → broadcast to all WebSocket clients on that channel
- Services subscribed to channel `"*"` receive messages from **all** channels

### WebSocket Channels
| Channel | Purpose |
|---------|---------|
| `/printer` | Print jobs from browsers → `PrinterWebSocketService` |
| `/serial/{type}` | Serial I/O per mapping type → `SerialWebSocketService` instances |
| `/notification` | Desktop notifications → `GUI` (tray icon messages) |

### Service Lifecycle
1. `Server.start()` reads config, creates service instances, calls `service.start()`
2. `Server.registerService(service)` — calls `service.onRegister(this)` and adds to channel map
3. `Server.stop()` — iterates all services, calls `unregisterService()` then `service.stop()`
4. **Persistent services** (`registerPersistentService()`) — survive restarts. `stop()` removes them from the channel map but `start()` re-attaches them. Used by the GUI notification listener. Printer/serial services are NOT persistent (recreated by `start()`).

### Config System
- `ConfigService` is a **singleton** (`ConfigService.getInstance()`) that loads `config.json` from the working directory on first access
- If `config.json` is missing, it creates one with defaults from the `Config` DTO's field initializers
- Jackson is configured with `FAIL_ON_UNKNOWN_PROPERTIES = false` — unknown/future fields are tolerated on load
- `save()` is **atomic**: writes to a temp file in the same dir, then `ATOMIC_MOVE` into place (falls back to non-atomic move if the FS doesn't support it). A crash mid-write cannot corrupt the existing config.
- Config is exposed via HTTP `GET/PUT /config.json` — the Web UI edits it directly. Individual sections also have dedicated endpoints (server, downloader, gui, printer mappings, serial mappings).
- `Config` DTO uses Lombok `@Data` with nested static classes. Key sections: `Server` (address, bind, port, authentication, tls, cors), `Security` (per-endpoint rules), `Downloader`, `Printer`, `Serial`, `GUI`, `Update` (auto-update settings).
- Version is hardcoded in `Constants.VERSION` (currently `"2.0.0"`) and must match `build.gradle` `version`. The release workflow **enforces** this: it fails the build if the git tag version doesn't match `build.gradle`.

### Printing Pipeline
1. Browser sends JSON `PrintDocument` to `/printer` WebSocket (or HTTP `POST /printer`)
2. `PrinterWebSocketService.messageToService()` deserializes → calls `printDocument()`
3. A **per-type lock** (`ConcurrentHashMap<String, Object> printLocks`) serializes concurrent prints to the same printer type, but prints to different types run in parallel
4. Document type detected from the URL **path only** (not query/fragment) via `urlFilename()`: raw (ESC/POS), image (JPG/PNG/GIF), or PDF
5. For image/PDF: `DocumentService.prepareDocument()` downloads from URL or decodes Base64 `fileContent`, saves to `downloads/` dir
6. Printer looked up via `searchPrinterForType()` — matches `type` field against `config.printer.mappings`. If not found: optionally auto-adds the type (`autoAddUnknownType`) and/or falls back to the default printer (`fallbackToDefault`)
7. After printing, result sent back to browser via `server.messageToServer()` as `PrintResult` JSON
8. **Downloaded files are always cleaned up** in a `finally` block (success or failure) so `downloads/` doesn't grow unbounded. Raw prints don't create files.

### Serial Pipeline
- One `SerialWebSocketService` instance per `config.serial.mappings` entry
- Each runs 3 threads: **read** (polls serial port, sends data to WebSocket clients), **write** (drains a `BlockingQueue<byte[]>` and writes all queued data to the port each cycle), **monitor** (auto-reconnects if port is unplugged)
- Monitor thread attempts `openPort()` every 1 second when port is closed
- Read charset is configurable per mapping; `"BINARY"` mode sends raw bytes as WebSocket binary frames
- Write uses a `LinkedBlockingQueue` — all queued writes are drained each cycle (no last-write-wins loss)

### Restart Mechanism
`POST /system/restart.json` triggers an **async restart** on a separate daemon thread (`"server-restart"`), guarded by an `AtomicBoolean` to prevent overlapping restarts. The HTTP response is sent **before** `stop()`/`start()` run — they must NOT execute on the Jetty worker thread, or `javalinServer.stop()` deadlocks shutting down the very thread pool serving the request. The E2E suite polls `/system/health` until the server is back up.

### Auto-Update System
- `UpdateService` is a **singleton** (`UpdateService.getInstance()`) that checks GitHub Releases for new versions
- **Hybrid approach**: detects + notifies by default; `autoDownload`/`autoInstall` are opt-in (off by default, recommended for B2B/POS)
- **Check flow**: polls `https://api.github.com/repos/{owner}/{repo}/releases/latest` (or `/releases` if pre-releases are included), parses `ReleaseInfo` DTO, compares versions with `VersionComparator` (semver with pre-release precedence)
- **Download**: downloads the new fat JAR to `updates/` via an atomic `.part` → move pattern; verifies file size against the GitHub-reported size
- **Apply**: `POST /system/update/apply` stops the server, replaces the current JAR (backs up old one to `.bak`), restarts. The apply runs on a background thread (same `restarting` AtomicBoolean guard as `/system/restart.json`) to avoid Jetty deadlock
- **Rollback**: `POST /system/update/rollback` restores the `.bak` JAR
- **Launcher auto-install**: if `autoInstall` is enabled, `Launcher.main()` applies a pending update before starting the app. `-Dlhb.no-update=true` is an emergency bypass
- **Scheduler**: `Server.start()` calls `UpdateService.startScheduledChecks()` which uses a single-thread `ScheduledExecutorService` (daemon thread `"update-checker"`). `Server.stop()` calls `stopScheduledChecks()`. Interval is `config.update.checkIntervalHours` (default 24, 0 = startup-only)
- **Pending update detection**: on startup, `UpdateService` scans `updates/` for a previously-downloaded JAR from a prior run and sets it as pending if its version is newer than `Constants.VERSION`
- **GUI integration**: the system tray menu has a "Check for Updates" item; a silent background check runs 8s after startup; an interactive dialog offers download+install if an update is found
- **Web UI**: the Advanced tab has an Auto-Update card with check/download buttons and config toggles
- **GitHub API**: every request sends a `User-Agent: Local-Hardware-Bridge/{version}` header (required by GitHub fair-use policy)

## Code Organization

```
src/main/java/io/github/augustinlr17/localhardwarebridge/
├── AppHome.java                — Anchors user.dir to JAR location (called first from Launcher)
├── Constants.java              — App name, ID, version, legacy identifiers
├── GUI.java                    — System tray icon + Server launcher + platform service install
├── Launcher.java               — Packaged Main-Class; anchors CWD then dispatches to GUI/Server
├── Server.java                 — Javalin HTTP/WS server, channel router, service registry, all REST endpoints
├── dtos/
│   ├── Config.java             — Full config structure with nested static classes (incl. Update)
│   ├── NotificationDTO.java    — type/title/message for desktop notifications
│   ├── PrintServiceDTO.java    — printer name/description for API responses
│   ├── ReleaseInfo.java        — GitHub Releases API response DTO (tag, assets, pre-release)
│   ├── SerialPortDTO.java      — port name/description/manufacturer for API responses
│   ├── UpdateStatusDTO.java    — Update check status for API responses
│   └── VersionDTO.java         — app name/id/version for API response
├── interfaces/
│   ├── WebSocketServerInterface.java  — Server→Service routing contract
│   └── WebSocketServiceInterface.java — Service lifecycle + messaging contract
├── responses/
│   ├── PrintDocument.java      — Incoming print job (type, url, id, uuid, qty, fileContent, rawContent, extras)
│   └── PrintResult.java        — Outgoing print result (success, message, id, printerName)
├── services/
│   ├── ConfigService.java      — Singleton config loader/saver (atomic save)
│   ├── DocumentService.java    — File download + Base64 decode (SSRF/path-traversal hardened)
│   └── UpdateService.java      — Singleton: checks GitHub Releases, downloads/applies JAR updates, rollback
├── utils/
│   ├── AnnotatedPrintable.java — Printable wrapper that overlays text annotations on PDF/images
│   ├── CertificateGenerator.java — Self-signed TLS cert generation (BouncyCastle)
│   ├── ImagePrintable.java     — AWT Printable for image files
│   ├── ThreadUtil.java         — silentSleep() helper
│   └── VersionComparator.java  — Semver comparison (MAJOR.MINOR.PATCH + pre-release precedence)
└── websocketservices/
    ├── PrinterWebSocketService.java  — Handles /printer channel, per-type locks, type detection, auto-add/fallback
    └── SerialWebSocketService.java   — Handles /serial/{type} channels, 3-thread model, BlockingQueue writes

src/main/java/tigerworkshop/webapphardwarebridge/
├── GUI.java                    — Legacy shim → delegates to new package
└── Server.java                 — Legacy shim → delegates to new package

src/main/resources/
├── web/                        — Static files served by Javalin (Web UI: index.html + Bootstrap + Petite-Vue)
├── windows/                    — Windows exe manifest (for jpackage)
├── icon.png                    — App icon
├── log4j2.xml                  — Logging config (console + rolling file in log/)
└── META-INF/MANIFEST.MF        — Main-Class + Class-Path for fat JAR

src/test/java/                  — JUnit 4 unit tests (4 classes, hermetic)
tui/                            — Go TUI admin client (bubbletea + lipgloss)
e2e/                            — Docker-based E2E tests (Python + CUPS-PDF)
demo/                           — HTML examples + JS SDK for integrating with the bridge
scripts/                        — Platform service files (systemd unit, launchd plist)
docs/                           — Code signing policy
```

## HTTP API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/config.json` | Get full config |
| PUT | `/config.json` | Update full config (saves to disk atomically) |
| POST | `/printer` | Submit print job (returns `PrintResult` synchronously) |
| GET | `/printer/mappings` | List printer mappings |
| POST | `/printer/mappings` | Add printer mapping |
| PUT | `/printer/mappings/{type}` | Update printer mapping by type |
| DELETE | `/printer/mappings/{type}` | Delete printer mapping by type |
| PUT | `/printer/enabled` | Enable/disable printer service |
| GET | `/system/printers.json` | List OS printers |
| POST | `/serial/{type}` | Write data to serial port by type |
| GET | `/serial/mappings` | List serial mappings |
| POST | `/serial/mappings` | Add serial mapping |
| PUT | `/serial/mappings/{type}` | Update serial mapping by type |
| DELETE | `/serial/mappings/{type}` | Delete serial mapping by type |
| PUT | `/serial/enabled` | Enable/disable serial service |
| GET | `/serial/status` | Serial port status (open/mapped/type per port) |
| GET | `/serial/connections` | WebSocket connections per serial channel |
| GET | `/system/serials.json` | List OS serial ports |
| GET | `/system/version.json` | App name, ID, version |
| GET | `/system/health` | Health check (status, version, printerEnabled, serialEnabled, uptime, services, connections) |
| POST | `/system/restart.json` | Async restart (returns immediately, restarts on background thread) |
| POST | `/system/notification` | Send a desktop notification |
| GET | `/system/connections` | List all WebSocket connections per channel |
| GET | `/system/server.json` | Get server config section |
| PUT | `/system/server.json` | Update server config section |
| GET | `/system/downloader.json` | Get downloader config section |
| PUT | `/system/downloader.json` | Update downloader config section |
| GET | `/system/gui.json` | Get GUI config section |
| PUT | `/system/gui.json` | Update GUI config section |
| POST | `/system/install-service` | Install systemd service (Linux only, migrates legacy service name) |
| POST | `/system/uninstall-service` | Uninstall systemd service (Linux only) |
| GET | `/system/update/status` | Get update check status (current version, latest, pending) |
| GET | `/system/update/check` | Check for updates synchronously (polls GitHub Releases API) |
| POST | `/system/update/download` | Download the update JAR to `updates/` |
| POST | `/system/update/apply` | Apply pending update (replace JAR + async restart) |
| POST | `/system/update/rollback` | Rollback to the `.bak` JAR + async restart |
| GET | `/system/update.json` | Get update config section |
| PUT | `/system/update.json` | Update update config section (restarts scheduler) |
| GET | `/icon.png` | Serve app icon |

### Auth & Endpoint Security
- **Global token auth** (`config.server.authentication`): when enabled, all HTTP requests require a Bearer token (`Authorization: Bearer <token>`) or Basic auth (password = token, username ignored). Comparison is **constant-time** via `MessageDigest.isEqual` — not suffix-based.
- **WebSocket auth**: when global auth is enabled, WS connections must pass `?token=<token>` as a query param (constant-time comparison). Invalid tokens get closed with code 1003.
- **Per-endpoint security rules** (`config.security.endpoints`): a map of path → `{enabled, password}`. Disabled endpoints return 403 — this check runs **before** the global token check, so a valid token does NOT bypass a disabled endpoint. Endpoints with a password require Bearer/Basic auth with that specific password (in addition to or instead of the global token). Dynamic paths like `/serial/SCALE` match the `/serial/{type}` rule.
- **Always-exempt endpoints**: `/system/health` bypasses all auth (global token, endpoint rules) so Docker/K8s/load-balancer health probes always work. `/config.json` ignores endpoint security rules (disable/password) so the Web UI loads, but still requires the global token when auth is enabled.
- CORS: configurable per-origin allow-list (`config.server.cors`), defaults to allow-all.

## WebSocket Protocol

### Printer Channel (`/printer`)
Send JSON:
```json
{
  "type": "receipt",
  "url": "https://example.com/receipt.pdf",
  "id": "optional-id",
  "qty": 1,
  "file_content": "base64-encoded-file (alternative to url)",
  "raw_content": "base64-encoded-raw-escpos (triggers raw print mode)",
  "extras": [{"text": "annotation", "x": 10.0, "y": 20.0, "size": 12, "bold": true}]
}
```
Field names use Jackson `@JsonProperty` snake_case (`file_content`, `raw_content`); other fields use Java camelCase as-is.

Receive `PrintResult`:
```json
{"success": true, "message": "Success", "id": "...", "printerName": "..."}
```

### Serial Channel (`/serial/{type}`)
- Text messages sent to the channel are written to the serial port
- Binary messages are also supported for writing
- Data received from the serial port is sent back as text (or binary if `readCharset` is `"BINARY"`)

## Key Conventions & Gotchas

- **Java module system**: `module-info.java` is present and required. All `requires` directives must be updated when adding new dependencies. The `opens` directives for Jackson reflection on DTOs/response/utils classes are critical — if you add a new package that Jackson serializes, add an `opens` directive. `module-info.class` is **excluded from the shadow JAR** to avoid JPMS conflicts.
- **Lombok everywhere**: `@Data`, `@Getter`, `@ToString`, `@Log4j2`, `@NoArgsConstructor`, `@AllArgsConstructor` are standard. Don't write getters/setters manually. Lombok is `compileOnly` + `annotationProcessor` (also `testCompileOnly` + `testAnnotationProcessor`).
- **Logging**: Use `@Log4j2` (Lombok) then `log.info(...)` / `log.error(...)`. Do not create loggers by hand. Log4j2 config is in `src/main/resources/log4j2.xml`. The build uses `log4j-slf4j2-impl` (SLF4J 2.x binding).
- **Config file is runtime-created**: `config.json` is in `.gitignore` and created on first run. Don't look for it in the repo. Unit tests that call `save()` will create it in the CWD (harmless).
- **Version must be kept in sync**: `Constants.VERSION`, `build.gradle` `version`, and the `PRODUCT_VERSION` default in `install.nsi` must match. CI release workflow enforces the tag ↔ build.gradle match and fails on mismatch.
- **Working directory anchoring**: relative paths (`config.json`, `log/`, `tls/`, `downloads/`, `updates/`) resolve against `user.dir`, which `AppHome.anchor()` (called first from `Launcher`) repoints to the install dir. If you add a new entry point, call `AppHome.anchor()` before touching files or loggers. It's idempotent and a no-op outside a JAR.
- **AnnotatedPrintable platform quirk**: `getDefaultTransform()` works on Windows but throws `NullPointerException` on macOS — the catch block falls back to a blank `AffineTransform`. Don't remove this try/catch.
- **Serial write is a queue**: `writeQueue` is a `LinkedBlockingQueue<byte[]>`, drained fully each write cycle. All queued writes are delivered (no last-write-wins loss).
- **HTTP Print API**: `POST /printer` calls `PrinterWebSocketService.printDocument()` synchronously and returns `PrintResult` JSON. This allows remote servers to submit print jobs without WebSocket. Returns 503 if the printer service is disabled.
- **Cross-platform GUI**: `GUI.java` falls back to headless mode (log URL + `Thread.currentThread().join()` or a notification loop on Linux) when `SystemTray.isSupported()` returns false. On Windows, it re-spawns under `javaw.exe` to hide the console window. On Linux it offers systemd install; on macOS it offers launchd install.
- **Service files**: `scripts/linux/` has a systemd unit, `scripts/macos/` has a launchd plist. Both use the `Server` main class (headless). Linux can also install the service at runtime via `POST /system/install-service` (migrates from the legacy `webapp-hardware-bridge` service name).
- **WebSocket message size**: Set to unlimited (`-1`) in `Server.wsBefore()`. This matters for large print jobs. Automatic pings are enabled every 5 seconds.
- **Default port**: 57212. Default bind is `127.0.0.1` (localhost only).
- **Web UI**: Single-page app using Petite-Vue (not Vue.js) + Bootstrap 5, served as static files from `src/main/resources/web/`. Axios for HTTP calls.
- **Demo files**: `demo/` contains HTML examples and JS SDK files (`websocket-printer.js`, `websocket-serial.js`, `websocket-weigh.js`) for integrating with the bridge from web apps.
- **DocumentService security**: URL downloads are restricted to `http`/`https` schemes only. `blockPrivateNetworks` config (opt-in, default off) rejects loopback/link-local/site-local addresses (SSRF mitigation). The TLS trust-all (`ignoreTLSCertificateError`) is scoped to a single connection — it never mutates the JVM-wide default. Path traversal is blocked: `getOutputFile()` strips directories from suggested filenames and verifies the canonical path stays inside the downloads dir.
- **File type detection**: `isPDF`/`isImage` in `PrinterWebSocketService` parse the URL and inspect only the **path** component, so a query string or fragment (e.g. `file.exe#x.pdf`) cannot spoof the file type.
- **Legacy compatibility**: `tigerworkshop.webapphardwarebridge` package contains shim `GUI` and `Server` classes. `Constants` has `LEGACY_APP_NAME`, `LEGACY_APP_ID`, `LEGACY_SERVICE_NAME` for migration detection. The Windows installer cleans up old TigerWorkshop shortcuts/registry entries. The Linux service installer migrates from the legacy service name.
- **Endpoint registration order matters**: static serial routes (`/serial/mappings`, `/serial/status`, `/serial/connections`) are registered **before** the wildcard `POST /serial/{type}` so Javalin matches them first.

## Key Dependencies

| Library | Purpose |
|---------|---------|
| Javalin 6.2.0 | HTTP + WebSocket server framework |
| Javalin SSL Plugin 6.2.0 | TLS/WSS support |
| jSerialComm 2.11.0 | Cross-platform serial port access |
| PDFBox 2.0.31 | PDF rendering and printing |
| BouncyCastle 1.78.1 | TLS certificate generation |
| Jackson 2.17.2 | JSON serialization |
| Lombok 1.18.34 | Boilerplate reduction |
| Commons IO 2.16.1 | File utilities |
| Commons Codec 1.17.1 | Base64 encoding |
| Log4j2 2.23.1 | Logging (via SLF4J 2.x binding) |
| Apache HttpCore5 5.2.5 | HTTP client (on classpath; downloads use `java.net.URLConnection`) |
| JUnit 4.13.2 | Unit testing |
| bubbletea + lipgloss (Go) | TUI admin client |
