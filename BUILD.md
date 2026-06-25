# Build Instructions

## Requirements

- JDK 21, [Eclipse Temurin 21](https://adoptium.net/en-GB/temurin/releases/) Recommended

## Build from source

```bash
./gradlew build
```

Output JAR: `build/libs/webapp-hardware-bridge-1.0.1.jar`

## Run

### GUI mode (system tray icon)
```bash
java -cp webapp-hardware-bridge.jar tigerworkshop.webapphardwarebridge.GUI
```

### Server mode (headless, no GUI)
```bash
java -cp webapp-hardware-bridge.jar tigerworkshop.webapphardwarebridge.Server
```

### Via Gradle
```bash
./gradlew run
```

## Windows Installer bundled with JRE

- JRE 21, [Eclipse Temurin 21](https://adoptium.net/en-GB/temurin/releases/) Recommended
- [Nullsoft Scriptable Install System](https://nsis.sourceforge.io/)

1. Follow "Build from source" instructions to yield `build/libs/webapp-hardware-bridge-1.0.1.jar`

2. Copy JRE 21 into `./jre` directory

3. Run `install.nsi` with NSIS to yield `whb.exe`

## Linux Installation

### Manual

```bash
# Build
./gradlew build

# Install
sudo mkdir -p /opt/webapp-hardware-bridge
sudo cp build/libs/webapp-hardware-bridge-*.jar /opt/webapp-hardware-bridge/webapp-hardware-bridge.jar
```

### Run manually
```bash
java -cp /opt/webapp-hardware-bridge/webapp-hardware-bridge.jar tigerworkshop.webapphardwarebridge.Server
```

### Install as systemd service (auto-start on boot)

```bash
sudo cp scripts/linux/webapp-hardware-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now webapp-hardware-bridge
```

The service runs as the current user. To run as a specific user, use an instantiated service:
```bash
sudo systemctl enable --now webapp-hardware-bridge@yourusername
```

### Check status
```bash
sudo systemctl status webapp-hardware-bridge
```

### View logs
```bash
journalctl -u webapp-hardware-bridge -f
```

## macOS Installation

### Manual

```bash
# Build
./gradlew build

# Install
sudo mkdir -p /usr/local/opt/webapp-hardware-bridge
sudo cp build/libs/webapp-hardware-bridge-*.jar /usr/local/opt/webapp-hardware-bridge/webapp-hardware-bridge.jar
```

### Run manually
```bash
java -cp /usr/local/opt/webapp-hardware-bridge/webapp-hardware-bridge.jar tigerworkshop.webapphardwarebridge.Server
```

### Install as launchd service (auto-start on login)

```bash
cp scripts/macos/tigerworkshop.webapphardwarebridge.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/tigerworkshop.webapphardwarebridge.plist
```

### Uninstall service
```bash
launchctl unload ~/Library/LaunchAgents/tigerworkshop.webapphardwarebridge.plist
```

### View logs
```bash
tail -f /usr/local/var/log/webapp-hardware-bridge.log
```
