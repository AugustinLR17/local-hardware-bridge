# AGENTS.md

## Project Overview

WebApp Hardware Bridge — a Java desktop application that exposes local printer and serial port access to web browsers via WebSocket and HTTP APIs. Commonly used for web-based POS systems (silent receipt printing, ESC/POS), WMS systems (weight scale reading, delivery note printing), and any web app needing serial port I/O.

## Build & Run Commands

### Prerequisites
- JDK 21 (Eclipse Temurin 21 recommended)
- Gradle (wrapper included: `./gradlew`)

### Build
```bash
./gradlew build
```
Output JAR goes to `build/libs/`.

### Run
- **GUI mode** (system tray icon): `java -cp build/libs/webapp-hardware-bridge-1.0.1.jar tigerworkshop.webapphardwarebridge.GUI`
- **Server-only mode** (no GUI, headless): `java -cp build/libs/webapp-hardware-bridge-1.0.1.jar tigerworkshop.webapphardwarebridge.Server`
- Via Gradle: `./gradlew run` (runs `GUI` main class as configured in `build.gradle`)

### Windows Installer
- Requires NSIS and a JRE 21 in `./jre` directory
- Run `install.nsi` to produce `whb.exe`
- The IntelliJ IDEA artifact build (`out/artifacts/webapp_hardware_bridge_jar/`) is used by the NSIS installer, not the Gradle output

### Tests
```bash
./gradlew test
```
JUnit 4 is on the test classpath, but **no test files exist yet** (`src/test/` is empty).

## Architecture & Control Flow

### Entry Points
- `GUI.main()` — launches `Server.start()`, then creates a system tray icon. Also registers itself as a `WebSocketServiceInterface` on the `/notification` channel to display desktop notifications.
- `Server.main()` — headless mode, just calls `Server.start()`.

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

### Config System
- `ConfigService` is a **singleton** (`ConfigService.getInstance()`) that loads `config.json` from the working directory on first access
- If `config.json` is missing, it creates one with defaults from the `Config` DTO's field initializers
- Config is exposed via HTTP `GET/PUT /config.json` — the Web UI edits it directly
- `Config` DTO uses Lombok `@Data` with nested static classes for each section (`Server`, `Printer`, `Serial`, `Downloader`, `GUI`)
- Version is hardcoded in `Constants.VERSION` (currently `"1.0.1"`) and must match `build.gradle` `version`

### Printing Pipeline
1. Browser sends JSON `PrintDocument` to `/printer` WebSocket
2. `PrinterWebSocketService.messageToService()` deserializes → calls `printDocument()`
3. Document type detected: raw (ESC/POS), image (JPG/PNG/GIF), or PDF
4. For image/PDF: `DocumentService.prepareDocument()` downloads from URL or decodes Base64 `fileContent`, saves to `downloads/` dir
5. Printer looked up via `searchPrinterForType()` — matches `type` field against `config.printer.mappings`
6. After printing, result sent back to browser via `server.messageToServer()` as `PrintResult` JSON
7. On error, downloaded file is deleted and notification sent to `/notification` channel

### Serial Pipeline
- One `SerialWebSocketService` instance per `config.serial.mappings` entry
- Each runs 3 threads: **read** (polls serial port, sends data to WebSocket clients), **write** (writes `writeBuffer` to port), **monitor** (auto-reconnects if port is unplugged)
- Monitor thread attempts `openPort()` every 1 second when port is closed
- Read charset is configurable per mapping; `"BINARY"` mode sends raw bytes as WebSocket binary frames

## Code Organization

```
src/main/java/tigerworkshop/webapphardwarebridge/
├── Constants.java              — App name, ID, version string
├── GUI.java                    — System tray icon + Server launcher
├── Server.java                 — Javalin HTTP/WS server, channel router, service registry
├── dtos/
│   ├── Config.java             — Full config structure with nested classes
│   ├── NotificationDTO.java    — type/title/message for desktop notifications
│   ├── PrintServiceDTO.java    — printer name/description for API responses
│   ├── SerialPortDTO.java      — port name/description/manufacturer for API responses
│   └── VersionDTO.java         — app name/id/version for API response
├── interfaces/
│   ├── WebSocketServerInterface.java  — Server→Service routing contract
│   └── WebSocketServiceInterface.java — Service lifecycle + messaging contract
├── responses/
│   ├── PrintDocument.java      — Incoming print job (type, url, fileContent, rawContent, extras, qty)
│   └── PrintResult.java        — Outgoing print result (success, message, id, printerName)
├── services/
│   ├── ConfigService.java      — Singleton config loader/saver
│   └── DocumentService.java    — File download + Base64 decode for print jobs
├── utils/
│   ├── AnnotatedPrintable.java — Printable wrapper that overlays text annotations on PDF/images
│   ├── CertificateGenerator.java — Self-signed TLS cert generation (BouncyCastle)
│   ├── ImagePrintable.java     — AWT Printable for image files
│   └── ThreadUtil.java         — silentSleep() helper
└── websocketservices/
    ├── PrinterWebSocketService.java  — Handles /printer channel, dispatches to PDF/image/raw printers
    └── SerialWebSocketService.java   — Handles /serial/{type} channels, manages serial port threads

src/main/resources/
├── web/                        — Static files served by Javalin (Web UI: index.html + Bootstrap + Petite-Vue)
├── log4j2.xml                  — Logging config (console + rolling file in log/)
└── META-INF/MANIFEST.MF        — Main-Class + Class-Path for fat JAR
```

## Key Conventions & Gotchas

- **Java module system**: `module-info.java` is present and required. All `requires` directives must be updated when adding new dependencies. The `opens` directives for Jackson reflection on DTOs/response classes are critical — if you add a new DTO package, add an `opens` directive.
- **Lombok everywhere**: `@Data`, `@Getter`, `@Log4j2`, `@NoArgsConstructor`, `@AllArgsConstructor` are standard. Don't write getters/setters manually.
- **Logging**: Use `@Log4j2` (Lombok) then `log.info(...)` / `log.error(...)`. Do not create loggers by hand. Log4j2 config is in `src/main/resources/log4j2.xml`.
- **No test suite**: `src/test/` is empty. JUnit 4 dependency exists but no tests are written.
- **Config file is runtime-created**: `config.json` is in `.gitignore` and created on first run. Don't look for it in the repo.
- **Version must be kept in sync**: `Constants.VERSION` and `build.gradle` `version` must match.
- **IntelliJ artifact vs Gradle**: The NSIS installer uses IntelliJ's artifact output (`out/artifacts/`), not Gradle's `build/libs/`. The `MANIFEST.MF` Class-Path is maintained manually for the IntelliJ artifact build.
- **AnnotatedPrintable platform quirk**: `getDefaultTransform()` works on Windows but throws `NullPointerException` on macOS — the catch block falls back to a blank `AffineTransform`. Don't remove this try/catch.
- **Serial write is single-buffer**: `writeBuffer` is a single byte array, not a queue. Rapid successive writes will overwrite. This is by design (last-write-wins for serial commands).
- **HTTP Print API**: `POST /printer` calls `PrinterWebSocketService.printDocument()` synchronously and returns `PrintResult` JSON. This allows remote servers to submit print jobs without WebSocket.
- **Cross-platform GUI**: `GUI.java` falls back to headless mode (log URL + `Thread.currentThread().join()`) when `SystemTray.isSupported()` returns false (Linux headless, macOS without desktop).
- **Service files**: `scripts/linux/` has systemd unit, `scripts/macos/` has launchd plist. Both use the `Server` main class (headless).
- **WebSocket message size**: Set to unlimited (`-1`) in `Server.wsBefore()`. This matters for large print jobs.
- **Auth check is suffix-based**: HTTP Bearer auth uses `header.endsWith(token)` rather than exact match or prefix stripping — this is a simplistic check, not a security best practice.
- **Default port**: 12212. Hardcoded in `Config.Server` defaults.
- **Web UI**: Single-page app using Petite-Vue (not Vue.js) + Bootstrap 5, served as static files from `src/main/resources/web/`. Axios for HTTP calls.
- **Demo files**: `demo/` contains HTML examples and JS SDK files (`websocket-printer.js`, `websocket-serial.js`, `websocket-weigh.js`) for integrating with the bridge from web apps.

## HTTP API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/config.json` | Get current config |
| PUT | `/config.json` | Update config (saves to disk) |
| POST | `/printer` | Submit print job via HTTP (returns `PrintResult` synchronously) |
| POST | `/serial/{type}` | Write data to serial port via HTTP |
| GET | `/system/printers.json` | List OS printers |
| GET | `/system/serials.json` | List OS serial ports |
| GET | `/system/version.json` | App name, ID, version |
| POST | `/system/restart.json` | Restart WebSocket/HTTP server |

All endpoints have CORS allowing any origin. Auth (when enabled) supports both Bearer token and Basic auth (password = token, username ignored).

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

Receive `PrintResult`:
```json
{"success": true, "message": "Success", "id": "...", "printerName": "..."}
```

### Serial Channel (`/serial/{type}`)
- Text messages sent to the channel are written to the serial port
- Binary messages are also supported for writing
- Data received from the serial port is sent back as text (or binary if `readCharset` is `"BINARY"`)

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
| Log4j2 2.23.1 | Logging |
| Apache HttpCore5 5.2.5 | HTTP client for downloads |
