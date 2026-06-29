# Code Signing Policy

**Last updated:** June 28, 2026

This document describes the code signing practices for Local Hardware Bridge, as
required by the [SignPath Foundation](https://signpath.org/) open source code
signing program.

> Free code signing provided by SignPath Foundation, certificate by SignPath
> Foundation.

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
release:

1. The build produces a jpackage app-image (bundled JRE + native launcher).
2. The native launcher (`Local Hardware Bridge.exe`) is signed.
3. NSIS packages the signed app-image into a single installer.
4. The installer (`Local-Hardware-Bridge-<version>.exe`) is signed.
5. Both signatures are verified (`Get-AuthenticodeSignature`) before publishing.

The signing configuration is defined in:
- `.github/workflows/release.yml` (CI signing steps)
- [Development Guide](https://github.com/AugustinLR17/local-hardware-bridge/wiki/Development-Guide) (local signing instructions)

## Access Control

- **Build system:** GitHub Actions on GitHub-hosted runners only (no self-hosted
  runners).
- **Signing credentials:** Stored as GitHub repository secrets. Only repository
  administrators can add or modify them.
- **Trigger:** Signing runs only on signed git tags (`v*`), which require push
  access to the repository.
- **Multi-factor authentication:** GitHub account has 2FA enabled.

## Artifact Integrity

- All release artifacts are built from the public `master` branch.
- The build is reproducible: anyone can clone the repository at a given tag and
  rebuild the artifacts.
- SHA256 checksums are published alongside each release (`SHA256SUMS`).

## Revocation

If a signing key is compromised, or if the project is found to violate the
SignPath Foundation terms, certificates may be revoked by SignPath Foundation.
The project maintainer will cooperate fully with any investigation.

## Contact

For code signing questions, open an issue at
https://github.com/AugustinLR17/local-hardware-bridge/issues
