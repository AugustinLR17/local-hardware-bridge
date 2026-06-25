# Local Hardware Bridge — TUI Admin

This is a terminal admin dashboard for the Local Hardware Bridge server. It is **not** an end-user client.

## Purpose

- Run the bridge on a server / shared PC / VM (headless).
- Use this TUI from an admin terminal to monitor health, printer/serial mappings, and connections.
- End users do not need this tool. Their browser talks to the bridge directly over HTTP/WebSocket.

## Build

```bash
cd tui
go build -o lhb-tui .
```

## Usage

```bash
# Local bridge
./lhb-tui --server http://127.0.0.1:12212

# Remote bridge (server mode)
./lhb-tui --server http://192.168.1.100:12212

# With authentication
./lhb-tui --server http://192.168.1.100:12212 --token my-secret-token
```

## What you see

- Dashboard: bridge status, version, active services
- Printers: OS printers + current mappings
- Serial: OS serial ports + current mappings
- Config: quick view of bind/port/auth settings

The TUI auto-refreshes every 5 seconds. Use arrow keys / tab to switch views, `q` to quit.
