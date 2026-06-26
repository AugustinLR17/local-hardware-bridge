# Build Instructions

## Requirements

- JDK 21, [Eclipse Temurin 21](https://adoptium.net/en-GB/temurin/releases/) Recommended

## Build from source

```bash
./gradlew build
```

Output JAR: `build/libs/local-hardware-bridge-1.0.1.jar`

## Run

### GUI mode (system tray icon)
```bash
java -cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI
```

### Server mode (headless, no GUI)
```bash
java -cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server
```

### Via Gradle
```bash
./gradlew run
```

## Windows Installer (single `.exe`, bundled JRE)

The installer is a single `lhb.exe` that bundles a Java runtime, installs a
**windowless** launcher (no terminal), creates shortcuts, and registers
auto-start. No JDK/JRE is required on the target machine.

Requirements to build it:

- JDK 21 with `jpackage` (e.g. [Eclipse Temurin 21](https://adoptium.net/en-GB/temurin/releases/))
- [NSIS](https://nsis.sourceforge.io/) (provides `makensis`)

Steps:

1. Build the app-image (fat JAR + bundled JRE + native launcher):

   ```bash
   ./gradlew createWindowsApp
   ```

   This produces `build/dist/appimage/Local Hardware Bridge/` containing a
   windowless `Local Hardware Bridge.exe` launcher (main class `...Launcher`).

2. Wrap it into the installer:

   ```bash
   makensis /DPRODUCT_VERSION=1.0.1 install.nsi
   ```

   This yields `lhb.exe`. (CI renames it to `Local-Hardware-Bridge-<version>.exe`.)

> The release workflow (`.github/workflows/release.yml`) runs exactly these two
> steps on `windows-latest`, then Authenticode-signs the result.

## Linux Installation

### Manual

```bash
# Build
./gradlew build

# Install
sudo mkdir -p /opt/local-hardware-bridge
sudo cp build/libs/local-hardware-bridge-*.jar /opt/local-hardware-bridge/local-hardware-bridge.jar
```

### Run manually
```bash
java -cp /opt/local-hardware-bridge/local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server
```

### Install as systemd service (auto-start on boot)

```bash
sudo cp scripts/linux/local-hardware-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now local-hardware-bridge
```

The service runs as the current user. To run as a specific user, use an instantiated service:
```bash
sudo systemctl enable --now local-hardware-bridge@yourusername
```

### Check status
```bash
sudo systemctl status local-hardware-bridge
```

### View logs
```bash
journalctl -u local-hardware-bridge -f
```

## macOS Installation

### Manual

```bash
# Build
./gradlew build

# Install
sudo mkdir -p /usr/local/opt/local-hardware-bridge
sudo cp build/libs/local-hardware-bridge-*.jar /usr/local/opt/local-hardware-bridge/local-hardware-bridge.jar
```

### Run manually
```bash
java -cp /usr/local/opt/local-hardware-bridge/local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server
```

### Install as launchd service (auto-start on login)

```bash
cp scripts/macos/io.github.augustinlr17.localhardwarebridge.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/io.github.augustinlr17.localhardwarebridge.plist
```

### Uninstall service
```bash
launchctl unload ~/Library/LaunchAgents/io.github.augustinlr17.localhardwarebridge.plist
```

### View logs
```bash
tail -f /usr/local/var/log/local-hardware-bridge.log
```
