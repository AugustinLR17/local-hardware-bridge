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

## Windows Installer bundled with JRE

- JRE 21, [Eclipse Temurin 21](https://adoptium.net/en-GB/temurin/releases/) Recommended
- [Nullsoft Scriptable Install System](https://nsis.sourceforge.io/)

1. Follow "Build from source" instructions to yield `build/libs/local-hardware-bridge-1.0.1.jar`

2. Copy JRE 21 into `./jre` directory

3. Run `install.nsi` with NSIS to yield `lhb.exe`

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
