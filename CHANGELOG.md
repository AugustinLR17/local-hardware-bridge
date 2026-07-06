# Changelogs

## 2.3.3

### Fixes
- **Print-job type matching is now case-insensitive — no more phantom mappings** —
  a print job with type `Main` did not match a configured `MAIN` mapping
  (case-sensitive compare), so with `autoAddUnknownType` enabled the bridge
  silently added a duplicate `Main` mapping with an empty printer name to
  `config.json`. The phantom then surfaced in the Web UI on the next save/reload,
  looking like saving had created a weird extra printer. Type lookup, the
  auto-add guard, and the `PUT`/`DELETE /printer/mappings/{type}` and
  `/serial/mappings/{type}` endpoints now all match the type case-insensitively
  (`DELETE` also sweeps up existing case-variant duplicates). Regression tests
  added for the case-insensitive lookup and the no-duplicate auto-add.
- **Editing/removing a printer or serial mapping corrupted the other rows** — the
  Web UI rendered mapping lists with keyless `v-for` loops, so petite-vue reused
  DOM nodes by position. Selecting a printer in one row bled into a sibling row
  (looked like a phantom mapping appeared and the selection jumped to the last
  one), and Remove deleted the wrong mapping. Each mapping now carries a stable
  client-side id used as `:key`, so rows track by identity. The id is stripped
  before the config is saved, so nothing new is persisted. Verified with a
  headless-Chrome reconciliation test: after removing the middle mapping, the
  surviving rows keep their original DOM nodes instead of shifting.

### Documentation
- **Intune supersedence detection rule made version-specific** — the deployment
  guide now instructs detecting on `HKCU\SOFTWARE\Local Hardware Bridge\Version`
  **equals the bundled version** instead of "`Install_Dir` value exists". The old
  rule matched every version identically, so under supersedence Intune never saw
  the old app as removed and looped on "Superseded applications are detected" /
  "A superseded app failed to uninstall". The installer already writes the
  `Version` value (`install.nsi`), so no installer change is required.
- **Documented the `0x8007013A` install/uninstall failure** — added a
  troubleshooting section explaining that `0x8007013A`
  (`ERROR_DISK_RESOURCES_EXHAUSTED`) originates in the Intune Management
  Extension unpacking content (most often Defender interfering), not in the
  install/uninstall scripts, with concrete fixes.
- **Defender exclusions now cover the IME content cache** — the guide adds
  `C:\Program Files (x86)\Microsoft Intune Management Extension\Content`
  alongside `%LOCALAPPDATA%\Local Hardware Bridge`, and documents pushing both
  via a Defender for Endpoint NGP default policy or an Endpoint Protection
  profile.

## 2.3.1

### Features
- **Single-instance enforcement with user prompt** — when starting a new
  instance and the configured port is already occupied by another Local
  Hardware Bridge, the GUI mode shows a dialog asking the user whether to
  stop the old instance and take over. If accepted, the old instance is
  stopped via `/system/restart.json` and the port is freed. In headless
  server mode, the new instance logs a warning and exits cleanly. Non-LHB
  processes on the port are detected and reported separately.

### Fixes
- **File type detection failed with `file_content` and no URL** — when a PDF or
  image was sent via `file_content` (Base64) without a `url` field, the bridge
  returned "Unknown file type: null" because type detection only inspected the
  URL path extension. Now falls back to sniffing the decoded content's magic
  bytes (`%PDF` for PDF, `89 50 4E 47` for PNG, `FF D8 FF` for JPEG, `GIF8` for
  GIF) when the URL has no usable extension. The output file also gets the
  correct extension inferred from the content.
- **Auto-update did not update the systemd service JAR** — `UpdateService.applyUpdate()`
  only replaced the JAR at the current runtime location. When running from an
  AppImage (read-only mount) or a different directory than the systemd service,
  the JAR at `/opt/local-hardware-bridge/local-hardware-bridge.jar` was never
  updated. Now, if the systemd service is installed, the update targets the
  service JAR and optionally syncs the current runtime JAR too. Rollback also
  targets the systemd JAR.

### Tests
- Added unit tests for content-based file type detection (PDF/PNG/JPEG/GIF magic
  bytes, URL extension priority, invalid base64, empty content).
- Added unit tests for `DocumentService.sniffExtension()` and extension inference
  on inline content filenames.
- Added E2E tests for printing a PDF via `file_content` without a URL, and with
  a URL that has no extension (regression tests for the detection bug).
- Added unit tests for `SingleInstanceGuard` (port detection, app identification,
  port-free wait timeout).

## 2.2.3

### Fixes
- **AccessDeniedException on startup** — `offerLinuxServiceInstall()` tried
  to read the root-owned systemd unit file with `Files.readString()`,
  crashing when the file exists but isn't user-readable. Now catches
  `AccessDeniedException` and treats the service as "installed, version
  unknown".

### Refactoring
- **Extracted `SystemdServiceGenerator`** — the systemd unit generation
  logic was duplicated between `GUI.java` and `Server.java`. It's now a
  testable static utility in `utils/` with comprehensive JUnit tests
  covering: Server vs GUI entry point, /opt install path, version
  extraction, restart policy, and file readability.

## 2.2.2

### Fixes
- **Linux systemd service crashed on startup** — the service launched `GUI`
  (which needs a display) instead of `Server` (headless). Under systemd
  where no X/Wayland session exists, `GUI` crashed trying to show a
  `JOptionPane`. The service now launches `Server`.
- **JAR path in service pointed to the download folder** — the systemd unit
  hardcoded the JAR location at runtime, so moving or deleting the download
  broke the service. The JAR is now copied to `/opt/local-hardware-bridge/`
  during service installation, and the unit points there.
- **Dialog windows were too small on Linux** — `JOptionPane` with a `null`
  parent renders minuscule on some window managers. All confirm dialogs now
  use a parent `JFrame` so they have a proper size.

## 2.2.1

### Fixes
- **Linux AppImage broken** — the AppRun script pointed at
  `usr/lib/local-hardware-bridge/Local Hardware Bridge` but jpackage places
  the launcher in `bin/` (`usr/lib/local-hardware-bridge/bin/Local Hardware
  Bridge`). The AppImage failed to start with "Aucun fichier ou dossier de
  ce nom".

## 2.2.0

Feature release: **Intune enterprise deployment** support and a
per-machine installer mode.

### Features
- **Microsoft Intune deployment** — the bridge can now be deployed silently
  across a fleet of Windows PCs via Intune (Endpoint Manager). The
  `packaging/intune/` directory provides:
  - `install.ps1` — silent install (`/S`), one-time enterprise config
    deployment, best-effort Defender exclusion
  - `uninstall.ps1` — clean uninstall with Defender exclusion removal
  - `config-template.json` — enterprise defaults (auth token `lhb002`,
    serial disabled, printer enabled, auto-update disabled, localhost bind)
  - `README.md` — quick admin guide
- **Per-machine installer mode** — `makensis /DPER_MACHINE=1 install.nsi`
  installs to `C:\Program Files` with HKLM registry keys and admin rights.
  Default stays per-user (backward compatible).
- **Wiki guide** — `docs/Intune-Deployment.md` covers the full deployment
  process: `.intunewin` preparation, Intune upload, detection rules,
  Defender exclusion profile, per-machine mode, and troubleshooting.

### Fixes
- `ConfigServiceConcurrencyTest` was flaky (shared `ArrayList` across 16
  threads) — switched to `Collections.synchronizedList()`.

### Tests
- `ConfigIntuneTemplateTest` (10 tests) — proves the Intune config template
  loads via Jackson into `Config.class` with correct enterprise values
  (strict mode, no unknown properties).

### Security
- `.gitignore` now blocks `aur_key` / `aur_key.pub` to prevent accidental
  commit of AUR SSH keys.

## 2.1.1

Incremental release: new print controls, expanded test coverage, and
CI/packaging improvements (code signing, AUR publishing, Linux AppImage).

### Features
- **Duplex, color, and paper tray controls** in print requests — the
  `PrintDocument` payload now accepts options to select duplex mode, color vs
  monochrome, and paper source (tray) per print job.
- **AUR publishing** — tagged releases now publish to the Arch User Repository
  (`local-hardware-bridge-bin`) automatically.
- **Linux AppImage with bundled JRE** — the AppImage is now fully
  self-contained (JRE bundled via jpackage), no system Java required.

### CI & packaging
- **SSL.com eSigner** code signing for the Windows installer (replaces the
  earlier Azure Trusted Signing attempt; SignPath was also evaluated and
  rejected for insufficient popularity).
- Removed Azure Trusted Signing from the release workflow.

### Tests
- Unit test coverage expanded to **737 tests** across DTOs, services,
  WebSocket services, utils, and responses.
- Extended E2E suite: `RemoteDisconnected` and connection-refused are now
  caught in all test request helpers for flake resistance.

## 2.1.0

Feature release: **auto-update system** and a less-conflicting default port,
plus a large batch of fixes, new tests, and tooling.

### Features
- **Auto-update** — the bridge can check GitHub Releases and optionally
  download, apply, and roll back updates. Opt-in hybrid model (detection +
  notification + manual install by default) suited to B2B/POS environments.
  Adds the `/system/update/*` REST endpoints, an `update` config section, and a
  Web UI panel.

### Changes
- **Default port `12212` → `57212`** — moved into the IANA dynamic/private range
  (49152–65535) to avoid conflicts with other local apps. Existing installs keep
  their configured port (`config.json` is persisted); only fresh installs use the
  new default. The multi-bridge example range becomes `57212`–`57217`.

### Fixes
- Resolve ~120 SonarQube issues (bugs, vulnerabilities, code smells).
- Disabled endpoints are checked before global token auth; `/system/health` is
  exempt from global token auth; `PUT /printer/enabled` returns `503` when the
  printer is disabled.
- `ReleaseInfo.tagName` maps `tag_name`; missing `Config` import in `Launcher`.
- WMS E2E: retry print after a bridge restart; raw-socket port-availability
  checks; wait for ports to free between suites.

### Build & quality
- Enable the **JaCoCo** coverage plugin (XML report wired for SonarQube).
- Bump the Shadow plugin (Gradle 9 compatibility); update build tooling for
  JDK 25; revert an earlier Gradle 9 / Lombok bump that broke CI.
- Add unit/edge-case tests (incl. `PrintServiceDTO`/`SerialPortDTO` JSON
  contracts, auto-update, cross-bridge auth/routing, WMS multi-zone).

### Docs & tooling
- README status badges; embedded auto-generated Web UI and TUI demos.
- Automated, fully-Dockerised media capture pipeline (`scripts/capture/`,
  Playwright + VHS + ffmpeg) and a `media.yml` workflow.
- Privacy and code-signing policies; consolidated docs to the wiki.

## 2.0.0

Major release: **Authenticode code signing** for the Windows installer and
launcher, and the project is now **open source** (public repository).

### Code signing (Windows)
- The Windows installer and the bundled launcher are signed with a
  Microsoft-trusted certificate via [SSL.com eSigner](https://www.ssl.com/esigner/)
  (cloud code signing) in CI. This resolves the Microsoft Defender "Anomaly
  detected in ASEP registry" persistence alert that is triggered by the
  auto-start registry entry on unsigned binaries.
- The release workflow signs **both** the jpackage launcher (the windowless
  `Local Hardware Bridge.exe` that the `HKCU\...\Run` key points at) and the
  final NSIS installer.

### Other
- Repository switched from private to public (MIT license, attribution to the
  original `webapp-hardware-bridge` project preserved).

## 1.0.3

Security hardening, bug fixes, new features and the first test suite.
**Security defaults are unchanged** — authentication stays disabled and CORS stays
open by default; everything below adds configurability, it does not lock you out.

### Security (opt-in)
- Configurable CORS allow-list (`server.cors`, default open as before).
- Path-traversal hardening on downloaded/print documents.
- SSRF mitigations: URL scheme restricted to http/https, type check on the URL
  path (no `#`/`?` bypass), optional `downloader.blockPrivateNetworks`.
- Constant-time token/password comparison; empty token never authorizes.
- TLS private key written with owner-only permissions; trust-all TLS (when
  `ignoreTLSCertificateError` is set) scoped to the single connection.

### Bug fixes
- A failed port bind no longer kills the whole process; serial I/O is thread-safe
  (no dropped writes) and no longer busy-spins at 100% CPU; downloader config
  changes apply without a JVM restart; downloaded files are cleaned up on success;
  config saves are atomic; restart is guarded against overlap.

### Features
- Enriched `/system/health` (printer/serial enabled, connections, uptime).
- Web UI: token handling + 401 flow, save/restart feedback, test print/serial,
  live serial monitor, periodic health refresh, dark mode, accessibility.
- TUI: `--token`/`LHB_TOKEN` auth, navigation keys, service toggles and restart.

### Tests
- First JUnit unit tests and additional e2e cases (health, path traversal, restart).

## 1.0.2

### Restart fixes
- The **Restart** action (Web UI `POST /system/restart.json` and the tray menu)
  now works reliably. The HTTP endpoint no longer stops Jetty from its own worker
  thread (which deadlocked); it responds first, then restarts on a dedicated
  thread.
- Desktop notifications now survive a restart: services that must outlive a
  restart (the GUI notification listener) are re-attached automatically.

### Windows packaging fixes
- The Windows release is now a **single self-contained `.exe`** installer (NSIS),
  bundling a Java runtime — no JDK/JRE required on the target machine.
- The app now launches **windowless** (no terminal/console window) via a native
  launcher.
- **Auto-start at boot** is registered reliably by the installer via
  `HKCU\...\Run` pointing at the windowless launcher, replacing the unreliable
  runtime `reg add` (which failed with exit code 1 and flashed a console).
- New `Launcher` entry point anchors the working directory to the install dir
  (`AppHome.anchor()`), so `config.json`, `log/` and `tls/` are found even when
  the app is started from a shortcut or auto-start (where the working directory
  is `system32`).
- Dropped the `.msi` artifact; the `.exe` is the single supported installer.

## From 0.x to 1.0.0

- 1.0 is a major rewrite, while maintain compatibility with existing WebApps
- Settings will lost after upgrade, please reconfigure via "Web UI" or "Web API"

### Feature changes
- Added per printer settings (Auto-rotate, DPI...)
- Added per serial port settings (Baud-rate, data bits, stop bit, parity bit, charset. binary mode, multi-bytes mode)
- Added "Web UI" for configuration, replacing "Configurator"
- Added "Web API", a HTTP API for WebApp to configure directly without using "Web UI" or "Configurator"
- Config file renamed from "setting.json" to "config.json", which is in different format

### Internal changes
- Removed "Configurator"
- Removed undocumented feature "Cloud Proxy"
- Removed usage of JavaFX
- Rewrite config code
- Implementation of WebSocket changed from "Java-WebSocket" to "Javalin"
- Internal dataflow optimization
- Simplified code by using "Lombok"
- Upgrade Java version from 8 to 21
- Many dependencies upgrades and security fixes
