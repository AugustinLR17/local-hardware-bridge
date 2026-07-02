# Intune Deployment — Local Hardware Bridge

This directory contains the files needed to deploy Local Hardware Bridge via
Microsoft Intune (Endpoint Manager) in a corporate environment.

## Files

| File                  | Purpose                                                    |
|-----------------------|------------------------------------------------------------|
| `install.ps1`         | Intune install wrapper: silent install + config + Defender |
| `uninstall.ps1`       | Intune uninstall wrapper: cleanup + Defender removal        |
| `config-template.json`| Enterprise base config (auth, serial off, printer on, ...)  |

These files are **stable across versions** — they are NOT attached to GitHub
Releases. Download the NSIS installer from the latest release and combine it
with the scripts from this directory.

## Quick start

1. Download the NSIS installer from the
   [latest release](https://github.com/AugustinLR17/local-hardware-bridge/releases/latest)
   (either `lhb.exe` or `Local-Hardware-Bridge-<version>.exe` — both work).
2. Place the installer, `install.ps1`, `uninstall.ps1`, and `config-template.json`
   in a single folder.
3. Edit `config-template.json` to match your enterprise policy (token, port,
   etc.). The default template ships with:
   - `authentication.enabled = true`, `token = "lhb002"`
   - `serial.enabled = false`
   - `printer.enabled = true`
   - `update.enabled = false` (updates managed via Intune)
   - `bind = 127.0.0.1` (localhost only)
4. Run `IntuneWinAppUtil.exe` to create the `.intunewin` package (see the
   [full guide](../../docs/Intune-Deployment.md)).
5. Upload to Intune as a Win32 app.

For the complete step-by-step guide (Intune upload, detection rules,
Defender exclusion profile, troubleshooting), see:
**[docs/Intune-Deployment.md](../../docs/Intune-Deployment.md)**