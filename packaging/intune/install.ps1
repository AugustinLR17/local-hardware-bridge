<#
.SYNOPSIS
    Intune install wrapper for Local Hardware Bridge.
.DESCRIPTION
    1. Runs the NSIS installer silently (/S).
    2. Copies the enterprise config-template.json as config.json
       ONLY if config.json does not already exist (one-time setup).
    3. Adds a Microsoft Defender exclusion for the install directory
       (best-effort; the primary exclusion should be an Intune Endpoint
       Protection profile, but this covers manual/admin installs).
.PARAMETER InstallerPath
    Path to lhb.exe. Defaults to the script's own directory.
.PARAMETER ConfigTemplate
    Path to config-template.json. Defaults to the script's own directory.
.NOTES
    Designed for Intune "Windows app (Win32)" deployment in User context.
    The NSIS installer is per-user (no admin/UAC required).
    Exit codes: 0 = success, 1603 = generic failure.
#>

[CmdletBinding()]
param(
    [string]$InstallerPath = "$PSScriptRoot\lhb.exe",
    [string]$ConfigTemplate = "$PSScriptRoot\config-template.json"
)

$ErrorActionPreference = "Stop"
$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"

# ---------------------------------------------------------------------------
# 0. Logging helper
# ---------------------------------------------------------------------------
function Write-Log([string]$Msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$ts] $Msg"
}

# ---------------------------------------------------------------------------
# 1. Run the NSIS installer silently
# ---------------------------------------------------------------------------
Write-Log "Starting silent install of $ProductName"

if (-not (Test-Path $InstallerPath)) {
    Write-Log "ERROR: Installer not found at $InstallerPath"
    exit 1603
}

# NSIS silent flag is /S (capital). Per-user installer needs no elevation.
$process = Start-Process -FilePath $InstallerPath -ArgumentList "/S" -Wait -PassThru -NoNewWindow

if ($process.ExitCode -ne 0) {
    Write-Log "ERROR: Installer exited with code $($process.ExitCode)"
    exit $process.ExitCode
}

Write-Log "Installer completed successfully."

# ---------------------------------------------------------------------------
# 2. Determine the install directory from the registry
# ---------------------------------------------------------------------------
$installDir = $null
if (Test-Path $InstallRegKey) {
    $regVal = Get-ItemProperty -Path $InstallRegKey -Name "Install_Dir" -ErrorAction SilentlyContinue
    if ($regVal) {
        $installDir = $regVal.Install_Dir
    }
}

if (-not $installDir -or -not (Test-Path $installDir)) {
    # Fallback to the default per-user path
    $installDir = "$env:LOCALAPPDATA\$ProductName"
    Write-Log "Registry lookup failed, falling back to $installDir"
}

if (-not (Test-Path $installDir)) {
    Write-Log "ERROR: Install directory not found: $installDir"
    exit 1603
}

Write-Log "Install directory: $installDir"

# ---------------------------------------------------------------------------
# 3. Deploy config.json (only if it does not already exist)
# ---------------------------------------------------------------------------
$configDest = Join-Path $installDir "config.json"

if (Test-Path $configDest) {
    Write-Log "config.json already exists at $configDest — skipping (one-time setup already done)."
} else {
    if (-not (Test-Path $ConfigTemplate)) {
        Write-Log "WARNING: config-template.json not found at $ConfigTemplate — app will use built-in defaults."
    } else {
        Write-Log "Deploying config-template.json -> config.json"
        Copy-Item -Path $ConfigTemplate -Destination $configDest -Force
        Write-Log "Config deployed successfully."
    }
}

# ---------------------------------------------------------------------------
# 4. Microsoft Defender exclusion (best-effort)
# ---------------------------------------------------------------------------
# In Intune User context, the script typically runs without admin rights, so
# Add-MpPreference will likely fail. The primary exclusion should be pushed
# via an Intune Endpoint Protection profile (Defender Antivirus Exclusions).
# This block covers the case where the script is run elevated (e.g. manually
# by an admin or in Device context).
try {
    $defender = Get-MpComputerStatus -ErrorAction SilentlyContinue
    if ($defender -and $defender.AMServiceEnabled) {
        Add-MpPreference -ExclusionPath $installDir -ErrorAction SilentlyContinue
        Write-Log "Defender exclusion added for $installDir"
    }
} catch {
    Write-Log "Defender exclusion skipped (no admin rights or Defender not available). Use an Intune Endpoint Protection profile instead."
}

# ---------------------------------------------------------------------------
# 5. Done
# ---------------------------------------------------------------------------
Write-Log "Install script completed."
exit 0