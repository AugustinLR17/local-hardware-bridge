# Architecture

## Overview

Local Hardware Bridge is a Java 21 application built on [Javalin](https://javalin.io/) that exposes local hardware (printers, serial ports) to web applications via WebSocket and REST APIs.

## Entry Points

| Main Class | Mode | Description |
|------------|------|-------------|
| `io.github.augustinlr17.localhardwarebridge.GUI` | GUI | System tray icon + server, desktop notifications |
| `io.github.augustinlr17.localhardwarebridge.Server` | Headless | Server only, no desktop dependencies |

## Core Components

### Server (`Server.java`)

Central hub that:
- Creates and configures the Javalin HTTP/WebSocket server
- Registers all REST API endpoints
- Routes messages between WebSocket clients and services via pub/sub channels
- Manages service lifecycle (start, register, unregister, stop)

### Pub/Sub Channel Model

Two interfaces drive the messaging:

- **`WebSocketServiceInterface`** — Implemented by services (Printer, Serial, GUI). Each declares a `getChannel()` and receives messages via `messageToService()`.
- **`WebSocketServerInterface`** — Implemented by `Server`. Routes messages between WebSocket clients and services, and between services.

**Routing maps:**
- `socketChannelSubscriptions` — channel → queue of `WsContext` (browser WebSocket connections)
- `serviceChannelSubscriptions` — channel → queue of `WebSocketServiceInterface` (backend services)
- Services on channel `"*"` receive messages from **all** channels

### Channel Map

| Channel | Service | Direction |
|---------|---------|-----------|
| `/printer` | `PrinterWebSocketService` | Browser → Printer |
| `/serial/{type}` | `SerialWebSocketService` | Browser ↔ Serial Port |
| `/notification` | `GUI` | Services → Desktop notifications |

### Service Lifecycle

1. `Server.start()` reads config, creates services, calls `service.start()`
2. `Server.registerService(service)` → `service.onRegister(this)` + add to channel map
3. `Server.stop()` → iterate services → `unregisterService()` + `service.stop()`

## Printing Pipeline

```
Browser/HTTP → PrintDocument JSON
    → PrinterWebSocketService.printDocument()
    → Type detection: raw | image | PDF
    → [image/PDF] DocumentService.prepareDocument() (download/Base64 decode)
    → searchPrinterForType() matches type against config.printer.mappings
    → Print to OS printer
    → PrintResult JSON back to browser
```

## Serial Pipeline

Each `SerialWebSocketService` instance runs 3 threads:

| Thread | Role |
|--------|------|
| Read | Polls serial port, sends data to WebSocket clients |
| Write | Writes `writeBuffer` to port (last-write-wins) |
| Monitor | Auto-reconnects every 1s if port unplugged |

Read charset is configurable; `"BINARY"` mode sends raw bytes as WebSocket binary frames.

## Configuration

`ConfigService` is a singleton loading `config.json` from the working directory. If missing, creates defaults from the `Config` DTO's field initializers. Config is exposed via HTTP `GET/PUT /config.json` and individual section endpoints.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Fat JAR via Shadow plugin | Single-file deployment, no classpath issues |
| `module-info.class` excluded from shadow JAR | Avoids JPMS conflicts with bundled dependencies |
| Single `writeBuffer` for serial | Last-write-wins is correct for most serial commands |
| Auth uses `header.endsWith(token)` | Simple check — see ADVANCED.md for security considerations |
| WebSocket message size unlimited | Required for large print jobs |

## Directory Structure

```
src/main/java/io/github/augustinlr17/localhardwarebridge/
├── Constants.java              — App name, ID, version
├── GUI.java                    — System tray icon + Server launcher
├── Server.java                 — Javalin server, channel router, REST API
├── dtos/                       — Data transfer objects (Config, Version, etc.)
├── interfaces/                 — WebSocketServerInterface, WebSocketServiceInterface
├── responses/                  — PrintDocument, PrintResult
├── services/                   — ConfigService, DocumentService
├── utils/                      — AnnotatedPrintable, CertificateGenerator, etc.
└── websocketservices/          — PrinterWebSocketService, SerialWebSocketService

src/main/resources/
├── web/                        — Web UI static files (HTML + Petite-Vue + Bootstrap)
├── icon.png                    — App icon
├── log4j2.xml                  — Logging config
└── META-INF/MANIFEST.MF        — Main-Class declaration

tui/                            — Go TUI client (bubbletea)
scripts/                        — Platform service files (systemd, launchd)
demo/                           — JS SDK + HTML examples
```

## Dependencies

| Library | Purpose |
|---------|---------|
| Javalin 6.2.0 | HTTP + WebSocket server |
| Javalin SSL Plugin 6.2.0 | TLS/WSS support |
| jSerialComm 2.11.0 | Cross-platform serial port access |
| PDFBox 2.0.31 | PDF rendering and printing |
| BouncyCastle 1.78.1 | TLS certificate generation |
| Jackson 2.17.2 | JSON serialization |
| Lombok 1.18.34 | Boilerplate reduction |
| Log4j2 2.23.1 | Logging |
