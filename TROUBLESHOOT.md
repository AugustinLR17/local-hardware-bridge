# Troubleshoot

## The app or system tray icon does not start (Windows)

Install the Microsoft Visual C++ Redistributable:
[vc_redist.x64.exe](https://www.microsoft.com/en-US/download/details.aspx?id=48145)

## Web UI is unreachable at http://127.0.0.1:12212

- Confirm the bridge is running (system tray icon, or `systemctl status local-hardware-bridge` on Linux).
- Check the configured `server.bind`/`server.port` in `config.json`.
- Another process may already hold the port; change `server.port` and restart.

## config.json / log/ / tls/ not found, or created in the wrong place

Relative paths resolve against the working directory. The packaged launchers use
the `Launcher` entry point, which calls `AppHome.anchor()` to repoint the working
directory to the install dir before anything else loads. If you start the app
manually, run it from (or set the working directory to) the install dir.

## Browser refuses to connect to ws:// from an https:// page

A secure page cannot open an insecure WebSocket. Enable TLS/WSS on the bridge and
connect with `wss://`. See [Advanced → HTTPS/WSS Support](ADVANCED.md#httpswss-support).

## Linux systemd service fails to start

- View logs: `journalctl -u local-hardware-bridge -f`
- Verify the JAR exists at `/opt/local-hardware-bridge/local-hardware-bridge.jar`.
- Ensure `java` (JDK 21+) is installed and on `PATH` at `/usr/bin/java`.
