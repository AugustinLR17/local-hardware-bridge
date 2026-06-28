# Build Instructions

## Requirements

- JDK 21, [Eclipse Temurin 21](https://adoptium.net/en-GB/temurin/releases/) Recommended

## Build from source

```bash
./gradlew build
```

Output JAR: `build/libs/local-hardware-bridge-2.0.0.jar`

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
   makensis /DPRODUCT_VERSION=2.0.0 install.nsi
   ```

   This yields `lhb.exe`. (CI renames it to `Local-Hardware-Bridge-<version>.exe`.)

> The release workflow (`.github/workflows/release.yml`) runs exactly these two
> steps on `windows-latest`, then Authenticode-signs the result.

## Code signing (required to avoid antivirus / SmartScreen warnings)

Microsoft Defender flags unsigned (or self-signed) binaries that register an
auto-start entry (`HKCU\...\Run`) as a **persistence** threat ("Anomaly
detected in ASEP registry"). A real, Microsoft-trusted Authenticode signature
is required for the installer *and* the windowless launcher it embeds.

The release workflow signs with **Azure Trusted Signing** (formerly Azure Code
Signing): a managed cloud HSM, ~$10/month, recognised by SmartScreen/Defender.
Signing is driven by [jsign](https://ebourg.github.io/jsign/) (cross-platform,
no Windows-only toolchain needed in CI).

### One-time Azure setup

1. Create a **Trusted Signing account** + a **Certificate Profile** (type
   *Public Trust*) in the Azure Portal. Complete the **identity validation**
   (1–20 business days). You can start with a *Public Trust Test* profile to
   validate the pipeline before validation completes.
2. Create an **App registration** (service principal) and a client secret, then
   grant it the **Trusted Signing Certificate Profile Signer** role on the
   certificate profile.
3. Add these **repository secrets** (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |--------|-------|
   | `AZURE_TENANT_ID` | Azure AD tenant ID |
   | `AZURE_CLIENT_ID` | Service principal (app) client ID |
   | `AZURE_CLIENT_SECRET` | Service principal secret |
   | `AZURE_CODESIGNING_ENDPOINT` | Region endpoint, e.g. `weu.codesigning.azure.net` (no `https://`, no trailing `/`) |
   | `AZURE_CODESIGNING_ACCOUNT` | Trusted Signing account name |
   | `AZURE_CODESIGNING_PROFILE` | Certificate profile name |

When the secrets are present, the release workflow signs **both** the jpackage
launcher (`Local Hardware Bridge.exe`) and the final NSIS installer
(`Local-Hardware-Bridge-<version>.exe`). When they are absent, signing is
skipped and a workflow warning is emitted (the build still produces an unsigned
EXE).

> The Trusted Signing certificates are short-lived (3 days). The workflow always
> timestamps against `http://timestamp.acs.microsoft.com/`, so the signature
> stays valid long after the cert itself expires.

### Signing locally

From a machine with the Azure secrets, signing a single EXE manually:

```bash
# 1. Get an access token
TOKEN=$(curl -fsS -X POST "https://login.microsoftonline.com/$AZURE_TENANT_ID/oauth2/v2.0/token" \
  -d "client_id=$AZURE_CLIENT_ID" \
  -d "scope=https://codesigning.azure.net/.default" \
  -d "client_secret=$AZURE_CLIENT_SECRET" \
  -d "grant_type=client_credentials" | jq -r .access_token)

# 2. Sign (download jsign once: https://repo1.maven.org/maven2/net/jsign/jsign/7.4/jsign-7.4.jar)
java -jar jsign.jar \
  --storetype TRUSTEDSIGNING \
  --keystore "https://$AZURE_CODESIGNING_ENDPOINT" \
  --storepass "$TOKEN" \
  --alias "$AZURE_CODESIGNING_ACCOUNT/$AZURE_CODESIGNING_PROFILE" \
  --tsaurl http://timestamp.acs.microsoft.com/ \
  --tsmode RFC3161 \
  "build/dist/appimage/Local Hardware Bridge/Local Hardware Bridge.exe"
```

Verify with `Get-AuthenticodeSignature` (PowerShell) or `jsign --verify`.

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

The system service runs as `root` by default. To run it as a specific
unprivileged user instead, add a drop-in (no need to edit the shipped unit):
```bash
sudo systemctl edit local-hardware-bridge
# In the editor, add:
#   [Service]
#   User=youruser
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
