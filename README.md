# Local Hardware Bridge

> **Fork of** [WebApp Hardware Bridge](https://github.com/imTigger/webapp-hardware-bridge) by imTigger — originally licensed under MIT.

## Introduction

Local Hardware Bridge made it possible for WebApps to perform silent print and access to serial ports.

Common use cases:
- Web-based POS - PDF and ESC/POS receipt silent print
- Web-based WMS - Serial weight scale real-time reading, delivery note/packing List silent print
- Any WebApps need to read/write to serial ports

## Features

- [x] Direct print from WebApps
- [x] Serial port read/write from WebApps
- [x] Support all modern browsers that implemented WebSocket (Chrome, Firefox, Edge... etc)
- [x] [HTTP API](HTTP_API.md) to configure directly from your WebApp
- [x] [JS SDK/Example included](demo)

### Direct Print
- 0-click silent printing in web browsers
- Download via URL / Base64 encoded file / Base64 encoded binary raw command
- Support multiple printers, mapped by key
- Support PDF/PNG/JPG Printing
- Support RAW/ESC-POS/ZPL Printing (via `raw_content` field with Base64-encoded binary data)
- Support adding annotation text to PDF/Image before printing
- Per printer settings

### Serial Access
- Bidirectional communication
- Support multiple ports, mapped by key
- Support multiple connection share same serial port
- Serial weigh scale (AWH-SA30 supported out-of-box in JS SDK)
- Per port settings (Baud rate, data bits, stop bit, parity bit)

## How to use?

### Client Side

1. Install and setup mapping via Web UI / API

2. Start "Local Hardware Bridge" and start using your WebApp

### WebApp Side

1. Check [JS SDK/Example](demo)

## How it works?

Local Hardware Bridge is a Java based application, which have more access to underlying hardwares.

It exposes a WebSocket and HTTP server on localhost to accept print jobs and serial connections from browsers and remote servers.

### Print Jobs

- PDF/Images job are downloaded/decoded and then sent to mapped printer.
- Raw job are sent to mapped printer directly via `raw_content` (Base64-encoded binary).

#### Raw/ZPL/ESC-POS Printing

To send raw commands (ESC-POS, ZPL, etc.) directly to a printer:

1. Install a **Generic / Text Only** driver for your printer (or use the printer's native driver if it supports raw mode)
2. Map a type to that printer in the Web UI (e.g., type `RECEIPT` → `Generic / Text Only`)
3. Send the raw data as Base64 in the `raw_content` field:

```javascript
printService.submit({
    'type': 'RECEIPT',
    'raw_content': btoa(rawCommandString)  // or base64-encode binary data
});
```

For ZPL label printers, encode your ZPL string as Base64 and send it the same way.

### Serial Connections

- Serial port are opened by Java and "proxied" as WebSocket stream
- Serial port can be shared by multiple connections
- Bidirectional communications possible

### Mappings

Web UI / API are provided to set up mappings between keys and printers/serials.

Therefore, WebApps do not need to care about the actual printer names.

## More documents

- [Configurations](CONFIGURATION.md)
- [HTTP APIs](HTTP_API.md)
- [Advanced Configurations - Authentication](ADVANCED.md#authentication)
- [Advanced Configurations - HTTPS/WSS Support](ADVANCED.md#httpswss-support)
- [Build from source](BUILD.md)
- [Troubleshooting](TROUBLESHOOT.md)

## Cross-Platform Support

Local Hardware Bridge runs on **Windows**, **Linux**, and **macOS**.

| Platform | GUI Mode | Server Mode | Service |
|----------|----------|-------------|---------|
| Windows | System tray icon, NSIS installer | `java -cp ... Server` | Startup shortcut |
| Linux | Headless fallback if no system tray | `java -cp ... Server` | systemd service |
| macOS | Headless fallback if no system tray | `java -cp ... Server` | launchd plist |

See [Build Instructions](BUILD.md) for platform-specific installation details.

## HTTP Print API (Server-to-Server)

A remote server can submit print jobs via HTTP without needing a WebSocket connection:

```bash
# List available printers
curl http://127.0.0.1:12212/system/printers.json

# Print a PDF from URL
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"INVOICE","url":"https://example.com/invoice.pdf"}'

# Print raw ESC-POS data
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"RECEIPT","raw_content":"<base64-encoded-data>"}'
```

See [HTTP APIs](HTTP_API.md) for full documentation.

## Running on Linux

The server can run on Linux in headless mode:

```bash
java -cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server
```

The Web UI is then accessible at `http://127.0.0.1:12212`. The GUI mode (system tray icon) requires a desktop environment and is not supported on most Linux distributions.

## Version

The application version is displayed at startup in the log and is available via the HTTP API:

```bash
curl http://127.0.0.1:12212/system/version.json
```

## Upgrade

- Settings will lost after upgrade from 0.x to 1.0, please reconfigure via "Web UI" or "Web API"

## Changelogs

- [Changelogs](CHANGELOG.md)
