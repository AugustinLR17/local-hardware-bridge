# Local Hardware Bridge

> **Fork of** [WebApp Hardware Bridge](https://github.com/imTigger/webapp-hardware-bridge) by imTigger — originally licensed under MIT.

**Local Hardware Bridge** exposes local printers and serial ports to web applications via WebSocket and REST APIs. Built for POS systems, WMS, IoT dashboards, and any web app needing hardware access.

## Features

- **Silent Printing** — PDF, images, ESC/POS, ZPL from any browser or remote server
- **Serial Port I/O** — Bidirectional communication with scales, scanners, IoT devices
- **REST API** — Full CRUD for config, mappings, printer/serial management
- **WebSocket API** — Real-time streaming for serial data and print status
- **Web UI** — Browser-based configuration dashboard
- **TUI Client** — Terminal interface for headless/server environments
- **Cross-Platform** — Windows, Linux, macOS with native installers
- **Auth & TLS** — Bearer token authentication, HTTPS/WSS support

## Quick Start

### Download

Grab the latest release from [Releases](https://github.com/AugustinLR17/local-hardware-bridge/releases):

| Platform | File |
|----------|------|
| Cross-platform | `local-hardware-bridge-*.jar` (requires JDK 21+) |
| Windows | `.exe` or `.msi` installer |
| Linux | `.AppImage` (requires JDK 21+) |
| macOS | `.dmg` installer |

### Run

```bash
# GUI mode (system tray icon)
java -jar local-hardware-bridge-*.jar

# Server mode (headless)
java -cp local-hardware-bridge-*.jar io.github.augustinlr17.localhardwarebridge.Server

# TUI mode (terminal interface)
./lhb-tui --server http://127.0.0.1:12212
```

The Web UI is available at `http://127.0.0.1:12212` (default).

## Architecture

```mermaid
graph LR
    subgraph Clients
        Browser[Web App / POS]
        Remote[Remote Server]
        TUI[TUI Client]
    end

    subgraph LHB[Local Hardware Bridge]
        PrinterSvc[Printer Service]
        SerialSvc[Serial Services]
        ConfigSvc[Config Service]
        WebUI[Web UI]
    end

    subgraph Hardware
        Printers[OS Printers]
        SerialPorts[OS Serial Ports]
    end

    Browser -- WebSocket --> PrinterSvc
    Browser -- WebSocket --> SerialSvc
    Remote -- REST API --> PrinterSvc
    Remote -- REST API --> SerialSvc
    TUI -- REST API --> ConfigSvc
    TUI -- REST API --> PrinterSvc
    TUI -- REST API --> SerialSvc

    PrinterSvc --> Printers
    SerialSvc --> SerialPorts
```

**How it works:** The Java application runs a Javalin HTTP/WebSocket server on localhost. Printer and serial services subscribe to channels. Messages are routed between WebSocket clients, HTTP requests, and hardware services via a pub/sub channel model.

## REST API

Full API documentation: [HTTP_API.md](HTTP_API.md)

### Quick Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| **Config** | | |
| GET | `/config.json` | Get full configuration |
| PUT | `/config.json` | Update full configuration |
| GET | `/system/server.json` | Get server config |
| PUT | `/system/server.json` | Update server config |
| GET | `/system/downloader.json` | Get downloader config |
| PUT | `/system/downloader.json` | Update downloader config |
| GET | `/system/gui.json` | Get GUI config |
| PUT | `/system/gui.json` | Update GUI config |
| **Printer** | | |
| GET | `/system/printers.json` | List OS printers |
| GET | `/printer/mappings` | List printer mappings |
| POST | `/printer/mappings` | Add printer mapping |
| PUT | `/printer/mappings/{type}` | Update printer mapping |
| DELETE | `/printer/mappings/{type}` | Delete printer mapping |
| PUT | `/printer/enabled` | Enable/disable printer service |
| POST | `/printer` | Submit print job |
| **Serial** | | |
| GET | `/system/serials.json` | List OS serial ports |
| GET | `/serial/mappings` | List serial mappings |
| POST | `/serial/mappings` | Add serial mapping |
| PUT | `/serial/mappings/{type}` | Update serial mapping |
| DELETE | `/serial/mappings/{type}` | Delete serial mapping |
| PUT | `/serial/enabled` | Enable/disable serial service |
| GET | `/serial/status` | Serial port status (open/mapped) |
| GET | `/serial/connections` | WebSocket clients per channel |
| POST | `/serial/{type}` | Write to serial port |
| **System** | | |
| GET | `/system/version.json` | App name, ID, version |
| GET | `/system/health` | Health check + services + connections |
| GET | `/system/connections` | All WebSocket connections |
| POST | `/system/notification` | Send desktop notification |
| POST | `/system/restart.json` | Restart server |
| POST | `/system/install-service` | Install systemd service (Linux) |
| POST | `/system/uninstall-service` | Uninstall systemd service (Linux) |

### Example: Print from command line

```bash
# List available printers
curl http://127.0.0.1:12212/system/printers.json

# Print a PDF from URL
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"INVOICE","url":"https://example.com/invoice.pdf"}'

# Print raw ESC/POS data
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"RECEIPT","raw_content":"<base64-encoded-data>"}'

# Add a printer mapping
curl -X POST http://127.0.0.1:12212/printer/mappings \
  -H "Content-Type: application/json" \
  -d '{"type":"RECEIPT","name":"POS-80"}'

# Check health
curl http://127.0.0.1:12212/system/health
```

### Example: Serial port management

```bash
# List serial ports
curl http://127.0.0.1:12212/system/serials.json

# Add a serial mapping
curl -X POST http://127.0.0.1:12212/serial/mappings \
  -H "Content-Type: application/json" \
  -d '{"type":"SCALE","name":"/dev/ttyUSB0","baudRate":9600,"numDataBits":8,"numStopBits":1,"parity":0}'

# Write to serial port
curl -X POST http://127.0.0.1:12212/serial/SCALE \
  -H "Content-Type: text/plain" \
  -d 'W'

# Check serial port status
curl http://127.0.0.1:12212/serial/status
```

## WebSocket Protocol

### Printer Channel (`/printer`)

Send:
```json
{
  "type": "receipt",
  "url": "https://example.com/receipt.pdf",
  "id": "optional-id",
  "qty": 1,
  "file_content": "base64-encoded-file",
  "raw_content": "base64-encoded-raw-escpos",
  "extras": [{"text": "annotation", "x": 10.0, "y": 20.0, "size": 12, "bold": true}]
}
```

Receive `PrintResult`:
```json
{"success": true, "message": "Success", "id": "...", "printerName": "..."}
```

### Serial Channel (`/serial/{type}`)

- Text messages → written to serial port
- Binary messages → written as raw bytes
- Data from serial port → sent back as text (or binary if `readCharset` is `"BINARY"`)

## Cross-Platform

| Platform | GUI Mode | Server Mode | Install | Service |
|----------|----------|-------------|---------|---------|
| Windows | System tray, `.exe`/`.msi` | `java -cp ... Server` | Installer | Startup shortcut |
| Linux | Headless fallback | `java -cp ... Server` | `.AppImage` | systemd |
| macOS | Headless fallback | `java -cp ... Server` | `.dmg` | launchd |

## Documentation

| Document | Description |
|----------|-------------|
| [HTTP API Reference](HTTP_API.md) | Complete REST & WebSocket API docs |
| [Configuration](CONFIGURATION.md) | All config options explained |
| [Advanced](ADVANCED.md) | Auth, TLS/WSS, advanced settings |
| [Build Instructions](BUILD.md) | Build from source, create installers |
| [Troubleshooting](TROUBLESHOOT.md) | Common issues and fixes |
| [Architecture](ARCHITECTURE.md) | Internal architecture and design |
| [Changelog](CHANGELOG.md) | Version history |

## JS SDK

Integration examples for web apps are in the [`demo/`](demo) directory:

- [`websocket-printer.js`](demo/websocket-printer.js) — Printer WebSocket client
- [`websocket-serial.js`](demo/websocket-serial.js) — Serial WebSocket client
- [`websocket-weigh.js`](demo/websocket-weigh.js) — Weight scale client (AWH-SA30)

## License

MIT License — see [LICENSE](LICENSE)

Original work Copyright (c) 2017 imTigger
Modified work Copyright (c) 2026 AugustinLR17
