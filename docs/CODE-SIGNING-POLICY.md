# Code Signing Policy

**Last updated:** July 01, 2026

This document describes the code signing practices for Local Hardware Bridge.

Windows binaries are Authenticode-signed in CI via [SSL.com eSigner](https://www.ssl.com/esigner/)
(cloud code signing). The signing certificate is a Personal ID Code Signing
certificate issued by SSL.com (IV+OV validation).

## What Is Signed

The following artifacts are Authenticode-signed:

| Artifact | Description |
|----------|-------------|
| `Local Hardware Bridge.exe` | The windowless native launcher (jpackage app-image) bundled in the Windows installer |
| `Local-Hardware-Bridge-<version>.exe` | The NSIS installer itself |

Both binaries are signed before publication to GitHub Releases.

## Why Signing Matters

The Windows installer registers an auto-start entry in the registry
(`HKCU\...\Run`) so the bridge launches when the user logs in. Microsoft
Defender flags unsigned binaries in auto-start locations as potential
"persistence" threats. Authenticode signing with a trusted certificate prevents
these false positives and gives users confidence in the software's origin.

## Signing Process

Signing is performed automatically in CI (GitHub Actions) on every tagged
release, using the [`sslcom/esigner-codesign`](https://github.com/SSLcom/esigner-codesign)
GitHub Action:

1. The build produces a jpackage app-image (bundled JRE + native launcher).
2. The native launcher (`Local Hardware Bridge.exe`) is signed via eSigner
   (cloud signing — no PFX stored on the runner).
3. NSIS packages the signed app-image into a single installer.
4. The installer (`Local-Hardware-Bridge-<version>.exe`) is signed via eSigner.
5. Both signed artifacts are uploaded to the GitHub Release.

The signing steps are gated on the `ESIGNER_ENABLED` repository variable and
require four repository secrets (`ES_USERNAME`, `ES_PASSWORD`, `CREDENTIAL_ID`,
`ES_TOTP_SECRET`). When the variable is not set, builds produce unsigned
binaries.

The signing configuration is defined in:
- `.github/workflows/release.yml` (CI signing steps)

## Access Control

- **Build system:** GitHub Actions on GitHub-hosted runners only (no self-hosted
  runners).
- **Signing credentials:** Stored as GitHub repository secrets. Only repository
  administrators can add or modify them.
- **Trigger:** Signing runs only on signed git tags (`v*`), which require push
  access to the repository.
- **Multi-factor authentication:** GitHub account has 2FA enabled; the SSL.com
  eSigner account uses TOTP (time-based one-time password) for API signing.

## Artifact Integrity

- All release artifacts are built from the public `master` branch.
- The build is reproducible: anyone can clone the repository at a given tag and
  rebuild the artifacts.
- SHA256 checksums are published alongside each release (`SHA256SUMS`).

## Contact

For code signing questions, open an issue at
https://github.com/AugustinLR17/local-hardware-bridge/issues