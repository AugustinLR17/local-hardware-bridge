# Privacy Policy

**Last updated:** June 28, 2026

Local Hardware Bridge ("the application") respects your privacy. This policy
describes what data the application collects, how it is used, and your choices.

## Data Collection

**The application does not collect, transmit, or share personal data.**

Specifically:

- **No telemetry or analytics.** The application does not phone home, report
  usage statistics, or send crash reports to any server.
- **No user accounts.** There is no login, registration, or account system.
- **No advertising or tracking SDKs.**
- **No data leaves your machine** except the print documents and serial commands
  you explicitly send through the API (these go to your local printer or serial
  device, not to us).

## Local Data

The application stores the following files **locally** on your machine, in the
install directory:

| File/Directory | Contents | Purpose |
|----------------|----------|---------|
| `config.json` | Configuration (port, printer mappings, serial mappings, optional auth token) | App settings |
| `log/` | Application log files | Debugging; auto-rotated |
| `tls/` | Self-signed TLS certificates (if enabled) | Encrypted connections |
| `downloads/` | Temporary print job files | Deleted after successful printing |

- These files never leave your machine.
- You can delete them at any time. The `config.json` is recreated with defaults
  on the next launch.
- The optional auth token in `config.json` is stored in plaintext. Set file
  permissions appropriately if this is a concern.

## Network Access

The application listens on `127.0.0.1:57212` (localhost) by default. It does
**not** initiate outbound network connections except:

- When you submit a print job with a `url` field — the application downloads
  that URL to print it. This request goes to the URL **you specified**, not to
  us.
- When downloading files from URLs **you provide** in print jobs.

## Third-Party Services

The application does not integrate any third-party analytics, advertising, or
tracking services.

Windows binaries are code-signed in CI via [SSL.com eSigner](https://www.ssl.com/esigner/)
(cloud code signing). This is a build-time process only and does not involve
any runtime data collection.

## Open Source

The application is open source (MIT license). You can audit the full source code
at any time: https://github.com/AugustinLR17/local-hardware-bridge

## Contact

For privacy questions, open an issue at
https://github.com/AugustinLR17/local-hardware-bridge/issues
