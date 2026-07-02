# Intune Deployment Guide — Local Hardware Bridge

This guide covers deploying Local Hardware Bridge (LHB) across a fleet of
Windows PCs via Microsoft Intune (Endpoint Manager). It targets the **2025-2026
Intune console** layout.

## Overview

LHB ships as a self-contained NSIS installer (`lhb.exe`) with a bundled JRE —
no Java installation is required on target machines. The installer is
**per-user** by default (installs into `%LOCALAPPDATA%\Local Hardware Bridge`,
no admin/UAC prompt), which is the recommended mode for Intune deployment in
**User context**.

The deployment consists of three parts:

1. **Win32 app** — the installer + a PowerShell wrapper that installs silently
   and deploys an enterprise config.
2. **Endpoint Protection profile** — a Microsoft Defender exclusion for the
   install directory (prevents false-positive quarantines).
3. **Config template** — a `config.json` with enterprise defaults (auth token,
   serial disabled, printer enabled, auto-update disabled).

---

## Prerequisites

- **Intune license** (Microsoft 365 E3/E5 or Intune Plan 1)
- **IntuneWinAppUtil.exe** — download directly from
  [GitHub: Microsoft/Win32-Content-Prep-Tool](https://github.com/microsoft/Microsoft-Win32-Content-Prep-Tool/blob/master/IntuneWinAppUtil.exe)
  (Windows-only utility, run on a Windows machine)
- **`lhb.exe`** — download from the
  [latest GitHub release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest)
- **Intune scripts** — from `packaging/intune/` in this repository:
  `install.ps1`, `uninstall.ps1`, `config-template.json`

> The scripts in `packaging/intune/` are **not** attached to GitHub Releases —
> they are stable across versions. Only `lhb.exe` changes between releases.

---

## Step 1 — Prepare the config template

Edit `config-template.json` to match your enterprise policy. The shipped
defaults are:

```json
{
  "server": {
    "authentication": { "enabled": true, "token": "lhb002" },
    "bind": "127.0.0.1",
    "port": 57212
  },
  "serial": { "enabled": false, "mappings": [] },
  "printer": { "enabled": true, "mappings": [] },
  "update": { "enabled": false }
}
```

Key decisions:

| Field                          | Default     | Notes                                      |
|--------------------------------|-------------|--------------------------------------------|
| `server.authentication.enabled`| `true`      | Requires a token for all API/WS calls      |
| `server.authentication.token`  | `"lhb002"`  | **Change this** before deploying           |
| `server.bind`                  | `127.0.0.1` | Localhost only. Use `0.0.0.0` for network  |
| `serial.enabled`               | `false`     | Disabled unless you use serial devices     |
| `printer.enabled`              | `true`      | Always on; idle if no printer is connected |
| `update.enabled`               | `false`     | Updates managed via Intune, not in-app     |

The config is deployed **once** — on first install only. If `config.json`
already exists (e.g. the user installed LHB manually before), the script
does **not** overwrite it. The user can still adjust settings via the Web UI
after deployment.

---

## Step 2 — Build the .intunewin package

On a Windows machine:

1. Create a working folder, e.g. `C:\Intune\LHB\`.
2. Place these files in the folder:
   - `lhb.exe` (from the GitHub release)
   - `install.ps1` (from `packaging/intune/`)
   - `uninstall.ps1` (from `packaging/intune/`)
   - `config-template.json` (from `packaging/intune/`, edited in Step 1)

3. Run IntuneWinAppUtil.exe:

```
IntuneWinAppUtil.exe -s C:\Intune\LHB -d C:\Intune\Output -o install.ps1
```

This produces `C:\Intune\Output\install.intunewin`.

---

## Step 3 — Upload to Intune

1. Sign in to the [Intune admin center](https://intune.microsoft.com).
2. Go to **Apps → Windows → Add**.
3. Select **Windows app (Win32)** as the app type.
4. Select the `install.intunewin` package file.

### App information

| Field              | Value                       |
|--------------------|-----------------------------|
| Name               | Local Hardware Bridge       |
| Description        | Local printer/serial bridge |
| Publisher          | AugustinLR17                |
| App install context| **User**                    |

### Program

| Field              | Value                                               |
|--------------------|-----------------------------------------------------|
| Install command    | `powershell.exe -ExecutionPolicy Bypass -File install.ps1` |
| Uninstall command  | `powershell.exe -ExecutionPolicy Bypass -File uninstall.ps1` |
| Install behavior   | **User**                                            |

### Requirements

| Field                | Value          |
|----------------------|----------------|
| Operating system arch| 64-bit         |
| Minimum OS version   | Windows 10 2004|
| Install behavior     | **User**       |

### Detection rules

Use a **Registry** detection rule:

| Field           | Value                                                        |
|-----------------|--------------------------------------------------------------|
| Rule type       | Registry                                                     |
| Key path        | `HKEY_CURRENT_USER\SOFTWARE\Local Hardware Bridge`          |
| Value name      | `Install_Dir`                                                |
| Detection method| String value exists                                          |

> If you use the per-machine installer (`/DPER_MACHINE=1`), the key is under
> `HKEY_LOCAL_MACHINE` instead. Adjust accordingly.

### Assignments

- Assign to the desired user/device groups.
- Set the app as **Required** for automatic silent deployment, or
  **Available** for self-service from the Company Portal.

---

## Step 4 — Microsoft Defender exclusion profile

To prevent Defender from flagging the bundled JRE or the launcher (common with
unsigned/signed Java apps), create an Endpoint Protection profile:

1. Go to **Devices → Configuration profiles → Create profile**.
2. Platform: **Windows 10 and later**.
3. Profile type: **Templates → Endpoint protection**.
4. Navigate to **Microsoft Defender Antivirus → Antivirus Exclusions**.
5. Add a path exclusion:

| Field    | Value                                      |
|----------|--------------------------------------------|
| Type     | Path                                       |
| Value    | `%LOCALAPPDATA%\Local Hardware Bridge`     |
| Excluded | True                                       |

> The `%LOCALAPPDATA%` variable is resolved per-user by the Defender client
> — each user's install directory is excluded automatically.

6. Assign the profile to the same groups as the Win32 app.

The `install.ps1` script also attempts `Add-MpPreference -ExclusionPath` as a
best-effort fallback, but this only works when the script runs elevated. The
Intune profile is the reliable method.

---

## Step 5 — Verify the deployment

On a target machine after Intune sync:

1. Check that the app is installed:
   - Registry: `HKCU\SOFTWARE\Local Hardware Bridge\Install_Dir` should exist
   - Folder: `%LOCALAPPDATA%\Local Hardware Bridge\Local Hardware Bridge.exe`
2. Check the config:
   - `%LOCALAPPDATA%\Local Hardware Bridge\config.json` should contain the
     enterprise settings (token `lhb002`, serial disabled, etc.)
3. Check the service:
   - Open `http://127.0.0.1:57212/system/health` in a browser
   - The response should include `"status": "OK"` and `"version": "2.1.1"`
   - With auth enabled, unauthenticated requests to other endpoints return 401
4. Check Defender:
   - Run `Get-MpPreference` in an elevated PowerShell
   - `ExclusionPath` should include the user's LHB install folder

---

## Per-machine mode (optional)

The NSIS installer supports a per-machine mode via a build flag:

```
makensis /DPRODUCT_VERSION=<ver> /DPER_MACHINE=1 install.nsi
```

This installs to `C:\Program Files\Local Hardware Bridge` and requires admin
rights. Use this if you deploy in **Device context** (SYSTEM account).

> **Warning:** In per-machine mode, `config.json` is written to
> `C:\Program Files\Local Hardware Bridge\`, which is read-only for standard
> users. The app will fail to save its config. A future version will redirect
> the config to `%PROGRAMDATA%\Local Hardware Bridge` for per-machine installs.
> Until then, **use per-user mode for Intune**.

---

## Troubleshooting

### App does not install

- Check Intune app assignment (Required vs Available).
- Check that the device is in the target group.
- Force a sync: **Devices → select device → Sync**.
- Check `Event Viewer → Applications and Services Logs → Microsoft → Intune → ManagementAgent`.

### Config not deployed

- The script only deploys `config-template.json` if `config.json` does **not**
  already exist. If the user had LHB installed before, their config is kept.
- To force a config refresh, delete `config.json` from the install directory
  and re-run the install, or push a separate PowerShell script via Intune that
  overwrites it.

### Defender quarantines the app

- Verify the Endpoint Protection exclusion profile is assigned.
- Run `Get-MpPreference` to confirm the exclusion is active.
- Temporarily add an exclusion manually:
  `Add-MpPreference -ExclusionPath "$env:LOCALAPPDATA\Local Hardware Bridge"`
- Check Defender event logs for the quarantine action.

### App installed but not reachable on port 57212

- Check that the app is running: `Get-Process "Local Hardware Bridge"`.
- Check the auto-start registry: `HKCU\...\Run\Local Hardware Bridge`.
- Check Windows Firewall — by default LHB binds to `127.0.0.1` (localhost
  only), so no firewall rule is needed. If you changed `bind` to `0.0.0.0`,
  you must allow inbound TCP 57212.

### Authentication errors (401)

- The default template enables auth with token `lhb002`. All API/WS requests
  must include `Authorization: Bearer lhb002` (or `?token=lhb002` for
  WebSocket). The `/system/health` endpoint is exempt and always works.
- If you changed the token in the template, make sure the Web UI and any
  integrating web apps use the new token.

---

## Uninstall via Intune

1. In the Intune app, change the assignment from Required to **Uninstall** for
   the target group.
2. Intune runs the uninstall command:
   `powershell.exe -ExecutionPolicy Bypass -File uninstall.ps1`
3. The script stops the app, runs the NSIS uninstaller silently, removes the
   Defender exclusion, and cleans up residual files.

---

## File summary

| Artifact               | Source                         | Changes between versions? |
|------------------------|--------------------------------|---------------------------|
| `lhb.exe`              | GitHub Releases                | Yes (every release)       |
| `install.ps1`          | `packaging/intune/`            | No (stable)               |
| `uninstall.ps1`        | `packaging/intune/`            | No (stable)               |
| `config-template.json` | `packaging/intune/`            | No (stable, edit locally) |
| `install.intunewin`    | Built by admin (Windows)       | Rebuild after lhb.exe update |