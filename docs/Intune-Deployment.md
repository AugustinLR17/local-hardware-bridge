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
3. **Config template** — a `config.json` with enterprise defaults (no
   auth, serial disabled, printer enabled, auto-update enabled).

For updating the config on already-deployed machines (without redeploying the
app), see [Updating the Configuration](#updating-the-configuration) below.

---

## Prerequisites

- **Intune license** (Microsoft 365 E3/E5 or Intune Plan 1)
- **`.intunewin` package** — download directly from the
  [latest GitHub release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest)
  (file: `Local-Hardware-Bridge-<version>.intunewin`).
  This is a ready-to-upload Win32 app package built in CI with:
  - No desktop icon (NSIS built with `NO_DESKTOP_ICON`)
  - Enterprise config template (auto-update enabled, no auth required)
  - Install/uninstall PowerShell wrappers
- **Alternatively**, build manually using `IntuneWinAppUtil.exe` (see
  [Manual .intunewin build](#manual-intunewin-build) below)
- **Intune scripts** — from `packaging/intune/` in this repository:
  `install.ps1`, `uninstall.ps1`, `config-template.json`
  (plus `update-config.ps1` and `update-config-api.ps1` for config-only updates)

> The `.intunewin` package is generated automatically in CI for each release.
> The scripts in `packaging/intune/` are stable across versions — only the
> bundled installer changes between releases.

---

## Step 1 — Prepare the config template

Edit `config-template.json` to match your enterprise policy. The shipped
defaults are:

```json
{
  "server": {
    "authentication": { "enabled": false },
    "bind": "127.0.0.1",
    "port": 57212
  },
  "serial": { "enabled": false, "mappings": [] },
  "printer": { "enabled": true, "mappings": [] },
  "update": { "enabled": true, "autoDownload": true, "autoInstall": true }
}
```

Key decisions:

| Field                          | Default     | Notes                                      |
|--------------------------------|-------------|--------------------------------------------|
| `server.authentication.enabled`| `false`     | No token required; enable if you need auth |
| `server.bind`                  | `127.0.0.1` | Localhost only. Use `0.0.0.0` for network  |
| `serial.enabled`               | `false`     | Disabled unless you use serial devices     |
| `printer.enabled`              | `true`      | Always on; idle if no printer is connected |
| `update.enabled`               | `true`      | Auto-update checks enabled                  |
| `update.autoDownload`          | `true`      | Downloads new versions automatically        |
| `update.autoInstall`           | `true`      | Applies updates on next restart             |
| `update.checkIntervalHours`    | `24`        | Daily update checks                          |

The config is deployed **once** — on first install only. If `config.json`
already exists (e.g. the user installed LHB manually before), the script
does **not** overwrite it. The user can still adjust settings via the Web UI
after deployment.

---

## Step 2 — Get the .intunewin package

### Option A: Download the CI-built package (recommended)

Each GitHub release includes a ready-to-upload `.intunewin` package:

1. Go to the
   [latest GitHub release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest).
2. Download `Local-Hardware-Bridge-<version>.intunewin`.

The package is built automatically in CI with:
- NSIS installer built with `NO_DESKTOP_ICON` (no desktop shortcut on silent install)
- Enterprise config template (auth enabled, auto-update enabled)
- Install/uninstall PowerShell wrappers

> If you customized `config-template.json` in Step 1, use Option B instead —
> the CI package uses the repository defaults.

### Option B: Build manually

If you need a custom config or custom installer flags, build the `.intunewin`
yourself on a Windows machine:

1. Download `IntuneWinAppUtil.exe` from
   [GitHub: Microsoft/Win32-Content-Prep-Tool](https://github.com/microsoft/Microsoft-Win32-Content-Prep-Tool/releases/latest).
2. Create a working folder, e.g. `C:\Intune\LHB\`.
3. Download the NSIS installer from the
   [latest GitHub release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest).
4. Place these files in the folder:
   - The NSIS installer (`lhb.exe` or `Local-Hardware-Bridge-<version>.exe`)
   - `install.ps1` (from `packaging/intune/`)
   - `uninstall.ps1` (from `packaging/intune/`)
   - `config-template.json` (from `packaging/intune/`, edited in Step 1)

> **Enterprise build (no desktop icon):** For Intune deployments, desktop icons
> are usually undesirable. Build the installer with the `NO_DESKTOP_ICON` flag
> to uncheck the desktop shortcut by default:
> ```
> makensis /DPRODUCT_VERSION=<version> /DNO_DESKTOP_ICON=1 install.nsi
> ```

5. Run IntuneWinAppUtil.exe:

```
IntuneWinAppUtil.exe -c C:\Intune\LHB -s install.ps1 -o C:\Intune\Output -q
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

Use a **version-specific Registry** detection rule. The installer writes the
product version to `HKCU\SOFTWARE\Local Hardware Bridge\Version` (see
`install.nsi`), so detect on that value being **equal to the version this app
package ships**:

| Field           | Value                                                        |
|-----------------|--------------------------------------------------------------|
| Rule type       | Registry                                                     |
| Key path        | `HKEY_CURRENT_USER\SOFTWARE\Local Hardware Bridge`          |
| Value name      | `Version`                                                    |
| Detection method| String comparison                                            |
| Operator        | Equals                                                       |
| Value           | `2.3.2` (**the exact version bundled in this .intunewin**)   |

> **Why version-specific and not just `Install_Dir` exists?** Both the old and
> the new app write the same `Install_Dir` key, so a "value exists" rule makes
> **every** version look identical to Intune. Under supersedence, that means the
> old app is still "detected" after the new one installs, so Intune keeps
> retrying its uninstall in a loop and the report shows *"Superseded
> applications are detected"* / *"A superseded app failed to uninstall"*
> indefinitely. Detecting on `Version` **equals the target version** makes each
> package distinguish itself: the old app becomes correctly "not detected" once
> the new version's `Version` value is written, and the supersedence chain
> resolves cleanly.
>
> **Bump this value on every release** to match the bundled installer version.
>
> If you use the per-machine installer (`/DPER_MACHINE=1`), the key is under
> `HKEY_LOCAL_MACHINE` instead. Adjust accordingly.

### Assignments

- Assign to the desired user/device groups.
- Set the app as **Required** for automatic silent deployment, or
  **Available** for self-service from the Company Portal.

---

## Step 4 — Microsoft Defender exclusions

To prevent Defender from flagging the bundled JRE or the launcher (common with
unsigned/signed Java apps) **and** from interfering with the Intune Management
Extension while it unpacks the `.intunewin` content, exclude **two** paths:

| Type | Value                                                        | Purpose                                         |
|------|--------------------------------------------------------------|-------------------------------------------------|
| Path | `%LOCALAPPDATA%\Local Hardware Bridge`                       | The installed app (JRE + launcher)              |
| Path | `C:\Program Files (x86)\Microsoft Intune Management Extension\Content` | The IME staging/extraction cache — prevents `0x8007013A` during install/uninstall |

> The `%LOCALAPPDATA%` variable is resolved per-user by the Defender client
> — each user's install directory is excluded automatically. The IME `Content`
> path is where Intune downloads and unzips every Win32 package before running
> it; if Defender locks or quarantines a file mid-extraction, the install (or
> the supersedence uninstall) fails with **`0x8007013A`**
> (`ERROR_DISK_RESOURCES_EXHAUSTED`). See
> [Troubleshooting → 0x8007013A](#app-install-or-uninstall-fails-with-0x8007013a).

You can push these exclusions in either of two ways — **use one, not both**:

### Option A — Defender for Endpoint "NGP default policy" (recommended if MDE is deployed)

If your tenant runs Microsoft Defender for Endpoint, the **Next Generation
Protection (NGP) default policy** already governs every endpoint. Add the two
paths under **Endpoint security → Antivirus → NGP default policy → Defender →
Excluded Paths**:

```
%LOCALAPPDATA%\Local Hardware Bridge, C:\Program Files (x86)\Microsoft Intune Management Extension\Content
```

> Note: the NGP default policy typically has **Network Protection = block mode**
> enabled. That does not affect LHB (it binds to `127.0.0.1` only) or the IME
> content download, so no additional Network Protection exception is needed.

### Option B — Endpoint Protection configuration profile

1. Go to **Devices → Configuration profiles → Create profile**.
2. Platform: **Windows 10 and later**.
3. Profile type: **Templates → Endpoint protection**.
4. Navigate to **Microsoft Defender Antivirus → Antivirus Exclusions**.
5. Add both path exclusions from the table above.
6. Assign the profile to the same groups as the Win32 app.

The `install.ps1` script also attempts `Add-MpPreference -ExclusionPath` for the
app directory as a best-effort fallback, but this only works when the script
runs elevated and does **not** cover the IME `Content` path. The Intune
policy/profile is the reliable method.

---

## Step 5 — Verify the deployment

On a target machine after Intune sync:

1. Check that the app is installed:
   - Registry: `HKCU\SOFTWARE\Local Hardware Bridge\Install_Dir` should exist
   - Folder: `%LOCALAPPDATA%\Local Hardware Bridge\Local Hardware Bridge.exe`
2. Check the config:
   - `%LOCALAPPDATA%\Local Hardware Bridge\config.json` should contain the
     enterprise settings (auth disabled, serial disabled, etc.)
3. Check the service:
   - Open `http://127.0.0.1:57212/system/health` in a browser
   - The response should include `"status": "UP"` and the current version
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

### App install or uninstall fails with 0x8007013A

`0x8007013A` is Win32 error **314 = `ERROR_DISK_RESOURCES_EXHAUSTED`** ("The
physical resources of this disk have been exhausted"). In Intune it surfaces as
**"Failed 0x8007013A"** on an install, or **"Uninstall Failed 0x8007013A"** /
**"A superseded app failed to uninstall"** during a supersedence migration.

**It is not a fault in `install.ps1` / `uninstall.ps1`** — those scripts only
return `0`, `1603`, or the NSIS exit code, never `0x8007013A`. The error comes
from the **Intune Management Extension (IME)** while it downloads or unzips the
`.intunewin` content, *before* your command runs. Ranked by likelihood:

1. **Microsoft Defender interfering with the IME extraction.** LHB bundles a JRE
   that Defender frequently false-positives on. If Defender scans/quarantines a
   file while the IME is unpacking it, the extraction aborts with `0x8007013A`.
   → **Fix:** ensure the Defender exclusions from
   [Step 4](#step-4--microsoft-defender-exclusions) are assigned **and applied**
   to the affected devices — both `%LOCALAPPDATA%\Local Hardware Bridge` **and**
   `C:\Program Files (x86)\Microsoft Intune Management Extension\Content`.
   Confirm with `Get-MpPreference | Select-Object -Expand ExclusionPath`.
2. **Low free disk space.** Check the device; the IME needs room to stage and
   unzip the package.
3. **Corrupt/full IME content cache.** Restart the **Microsoft Intune Management
   Extension** service, or clear
   `C:\Program Files (x86)\Microsoft Intune Management Extension\Content\Incoming`,
   then force a **Sync**. `0x8007013A` is often transient and clears on retry.

> **"A superseded app failed to uninstall" but the app shows Installed:** the
> new version installed and runs fine — only the removal of the old package's
> content failed. Once the Defender exclusion is in place, the next IME retry
> clears the old app record. No user-facing impact in the meantime.

### Config not deployed

- The script only deploys `config-template.json` if `config.json` does **not**
  already exist. If the user had LHB installed before, their config is kept.
- To force a config refresh on all machines, deploy `update-config.ps1` or
  `update-config-api.ps1` via Intune Scripts (see
  [Updating the Configuration](#updating-the-configuration)).
- To force a config refresh on a single machine, delete `config.json` from the
  install directory and re-run the install.

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

- The default template ships with auth **disabled**. If you enabled
  `server.authentication.enabled`, all API/WS requests must include
  `Authorization: Bearer <token>` (or `?token=<token>` for WebSocket).
  The `/system/health` endpoint is always exempt.

---

## Uninstall via Intune

1. In the Intune app, change the assignment from Required to **Uninstall** for
   the target group.
2. Intune runs the uninstall command:
   `powershell.exe -ExecutionPolicy Bypass -File uninstall.ps1`
3. The script stops the app, runs the NSIS uninstaller silently, removes the
   Defender exclusion, and cleans up residual files.

---

## Updating the Configuration

There are two scenarios for updating LHB on deployed machines:

### Scenario 1 — New app version (e.g. v2.3.0 → v2.3.1)

**Option A: Use the CI-built `.intunewin` (recommended)**

1. Download the new `Local-Hardware-Bridge-<version>.intunewin` from the
   GitHub release.
2. Create a **new** Win32 app in Intune and upload the new `.intunewin`.
   Set its [detection rule](#detection-rules) to `Version` **equals the new
   version** (e.g. `2.3.3`) — this is what lets supersedence resolve cleanly
   instead of looping on "Superseded applications are detected".
3. In the new app → **Supersedence** → select the old app.
4. Intune will automatically:
   - Uninstall the old version (runs `uninstall.ps1` — removes the entire
     install directory including `config.json`).
   - Install the new version (runs `install.ps1` — deploys the new
     `config-template.json` as `config.json`).
5. Assign to groups (use pilot groups first for progressive rollout).

**Option B: Manual build**

1. Download the new `Local-Hardware-Bridge-<version>.exe` from the GitHub
   release.
2. Rebuild the `.intunewin` package with the new installer (same `install.ps1`,
   `uninstall.ps1`, `config-template.json` — these are stable across versions).
   See [Step 2 — Option B](#option-b-build-manually) for instructions.
3. Follow steps 2-5 from Option A above.

> **Note:** Because `uninstall.ps1` removes the entire install directory, the
> old `config.json` is lost. The new `install.ps1` deploys the fresh
> `config-template.json`. This is by design — the new version's config template
> is the source of truth.

### Scenario 2 — Config-only update (no new app version)

When you need to push config changes (add printer mappings, change token,
enable `fallbackToDefault`, set endpoint passwords, etc.) **without** deploying
a new app version, use one of the update scripts from `packaging/intune/`.

#### Option A — File replacement + restart (`update-config.ps1`)

Best for: token changes, first-time config fixes, or when the app might not be
running on all machines.

**Deployment via Intune Scripts:**

1. Edit `config-template.json` with the new settings.
2. Go to **Intune → Devices → Scripts → Add**.
3. Upload `update-config.ps1` and `config-template.json` (both files must be
   in the same folder).
4. Assign to the same groups as the Win32 app.
5. The script will:
   - Locate the install directory (via registry or fallback).
   - Back up the existing `config.json` to `config.json.bak`.
   - Overwrite `config.json` with the new template.
   - Restart LHB via the VBS launcher (preserves the WorkingDir fix).

**Intune Scripts configuration:**

| Setting              | Value                    |
|----------------------|--------------------------|
| Run script in        | **User context**         |
| Run with privileges  | No (user-level)          |
| Script to run        | `update-config.ps1`      |

#### Option B — Live API update (`update-config-api.ps1`)

Best for: adding printer mappings, enabling/disabling endpoints, or any config
change that does **not** modify the auth token. Zero downtime — no restart
needed, active print jobs and serial connections are not disrupted.

**Deployment via Intune Scripts:**

1. Edit `config-template.json` with the new settings.
2. Go to **Intune → Devices → Scripts → Add**.
3. Upload `update-config-api.ps1` and `config-template.json`.
4. Assign to the same groups.
5. The script will:
   - Check that LHB is running (health check on `127.0.0.1:57212`).
   - Auto-detect the auth token from the existing `config.json`.
   - Push the new config via `PUT /config.json`.
   - The app applies the config immediately (no restart).

> **Warning:** If you are changing the auth token itself, use Option A (file
> replacement + restart). Option B would fail with 401 because the API request
> uses the old token while the new config contains the new one.

#### When to use which option?

| Situation                                   | Option A (file + restart) | Option B (API live) |
|---------------------------------------------|---------------------------|---------------------|
| Adding a printer mapping (e.g. "pdf")       | ✅ Works                  | ✅ Best (no downtime) |
| Changing the auth token                     | ✅ Best                   | ❌ Will fail (401)   |
| Enabling `fallbackToDefault`                | ✅ Works                  | ✅ Best (no downtime) |
| Setting per-endpoint passwords              | ✅ Works                  | ✅ Best (no downtime) |
| App might not be running on some machines   | ✅ Best                   | ❌ Will fail        |
| Need zero downtime (production environment) | ❌ Causes brief restart   | ✅ Best              |
| First config deployment (no config.json)    | ✅ Best                   | ❌ Will fail        |

#### Example: adding the "pdf" printer mapping

1. Edit `config-template.json`:
   ```json
   "printer": {
     "enabled": true,
     "autoAddUnknownType": false,
     "fallbackToDefault": true,
     "mappings": [
       {"type": "MAIN", "name": "Lexmark XM3250 (2)", "autoRotate": false, "resetImageableArea": true, "forceDPI": 0},
       {"type": "pdf",  "name": "Lexmark XM3250 (2)", "autoRotate": false, "resetImageableArea": true, "forceDPI": 0}
     ]
   }
   ```
2. Deploy via Option B (API live update) — no restart needed on any machine.
3. Verify on one machine:
   ```
   curl http://127.0.0.1:57212/printer/mappings
   ```

---

## File summary

| Artifact               | Source                         | Changes between versions? |
|------------------------|--------------------------------|---------------------------|
| `lhb.exe`              | GitHub Releases                | Yes (every release)       |
| `install.ps1`          | `packaging/intune/`            | No (stable)               |
| `uninstall.ps1`        | `packaging/intune/`            | No (stable)               |
| `config-template.json` | `packaging/intune/`            | No (stable, edit locally) |
| `update-config.ps1`    | `packaging/intune/`            | No (stable)               |
| `update-config-api.ps1`| `packaging/intune/`            | No (stable)               |
| `install.intunewin`    | GitHub Releases (CI-built) or manual build | Rebuild after lhb.exe update |