# Intune Deployment — Local Hardware Bridge

This directory contains the files needed to deploy Local Hardware Bridge via
Microsoft Intune (Endpoint Manager) in a corporate environment.

## Files

| File                       | Purpose                                                       |
|----------------------------|---------------------------------------------------------------|
| `install.ps1`              | Intune install wrapper: silent install + config + Defender    |
| `uninstall.ps1`            | Intune uninstall wrapper: cleanup + Defender removal          |
| `config-template.json`     | Enterprise base config (auth, serial off, printer on, auto-update on) |
| `update-config.ps1`        | Config-only update: replaces config.json + restarts app       |
| `update-config-api.ps1`    | Live config update via HTTP API (no restart needed)           |

These files are **stable across versions**. Starting with v2.4.0, each GitHub
release also includes a ready-to-upload `.intunewin` package built in CI
(no desktop icon, auto-update enabled, enterprise config). You only need
the scripts below for manual builds or config-only updates.

## Quick start

### Option A: Use the CI-built `.intunewin` (recommended)

1. Download `Local-Hardware-Bridge-<version>.intunewin` from the
   [latest release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest).
2. Upload to Intune as a Win32 app (see the
   [full guide](../../docs/Intune-Deployment.md)).

The CI package bundles the installer (no desktop icon), install/uninstall
scripts, and the enterprise config template with:
   - `authentication.enabled = false` (no token required)
   - `serial.enabled = false`
   - `printer.enabled = true`
   - `update.enabled = true`, `autoDownload = true`, `autoInstall = true`
   - `bind = 127.0.0.1` (localhost only)

### Option B: Build manually

If you need a custom config or different installer flags:

1. Download the NSIS installer from the
   [latest release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest).
2. Place the installer, `install.ps1`, `uninstall.ps1`, and `config-template.json`
   in a single folder.
3. Edit `config-template.json` to match your enterprise policy.
4. Run `IntuneWinAppUtil.exe` to create the `.intunewin` package (see the
   [full guide](../../docs/Intune-Deployment.md)).
5. Upload to Intune as a Win32 app.

## Updating the config (without redeploying the app)

When you need to push config changes (new printer mappings, token change,
endpoint passwords, `fallbackToDefault`, etc.) to machines that already have
LHB installed, use one of the update scripts:

### Option A — File replacement + restart (`update-config.ps1`)

Best for: token changes, first-time config deployment, or when the app might
not be running.

1. Edit `config-template.json` with the new settings.
2. Go to **Intune → Devices → Scripts → Add**.
3. Upload `update-config.ps1` and `config-template.json`.
4. Assign to the same groups as the Win32 app.
5. The script backs up the old config, deploys the new one, and restarts LHB.

### Option B — Live API update (`update-config-api.ps1`)

Best for: adding printer mappings, enabling endpoints, or any config change
that doesn't modify the auth token. Zero downtime — no restart needed.

1. Edit `config-template.json` with the new settings.
2. Go to **Intune → Devices → Scripts → Add**.
3. Upload `update-config-api.ps1` and `config-template.json`.
4. Assign to the same groups.
5. The script pushes the config via `PUT /config.json` — the app applies it
   immediately. The auth token is auto-detected from the existing config.

> **Warning:** If you're changing the auth token itself, use Option A (file
> replacement + restart). Option B would fail because the new config changes
> the token while the API request uses the old one.

For the complete step-by-step guide (Intune upload, detection rules,
Defender exclusion profile, troubleshooting, update procedures), see:
**[docs/Intune-Deployment.md](../../docs/Intune-Deployment.md)**