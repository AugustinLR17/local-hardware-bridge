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
    Path to the NSIS installer. Auto-detected: looks for lhb.exe or
    Local-Hardware-Bridge-<version>.exe in the script directory.
.PARAMETER ConfigTemplate
    Path to config-template.json. Defaults to the script's own directory.
.NOTES
    Designed for Intune "Windows app (Win32)" deployment in User context.
    The NSIS installer is per-user (no admin/UAC required).
    Exit codes: 0 = success, 1603 = generic failure.
#>

[CmdletBinding()]
param(
    [string]$InstallerPath = "",
    [string]$ConfigTemplate = ""
)

$ErrorActionPreference = "Stop"
$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"

# ---------------------------------------------------------------------------
# 0. Resolve script directory (PSScriptRoot can be empty in some contexts)
# ---------------------------------------------------------------------------
if ($PSScriptRoot) {
    $ScriptDir = $PSScriptRoot
} elseif ($MyInvocation.MyCommand.Path) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
} else {
    $ScriptDir = (Get-Location).Path
}

# Auto-detect the installer exe if not explicitly provided.
# Accepts lhb.exe or Local-Hardware-Bridge-<version>.exe.
if (-not $InstallerPath) {
    $exactMatch = Join-Path $ScriptDir "lhb.exe"
    if (Test-Path $exactMatch) {
        $InstallerPath = $exactMatch
    } else {
        $patternMatch = Get-ChildItem -Path $ScriptDir -Filter "Local-Hardware-Bridge-*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($patternMatch) {
            $InstallerPath = $patternMatch.FullName
        }
    }
}

if (-not $ConfigTemplate) {
    $ConfigTemplate = Join-Path $ScriptDir "config-template.json"
}

# ---------------------------------------------------------------------------
# 0b. Logging helper
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
# 4. Fix auto-start WorkingDir (safety net for HKCU\...\Run key)
# ---------------------------------------------------------------------------
# The NSIS installer writes a bare exe path to HKCU\...\Run without a
# WorkingDir. At boot, Windows uses System32 as CWD, so the app can't
# find config.json. We rewrite the Run key to use a VBS wrapper that
# sets the working directory before launching the app.
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$launcherExe = Join-Path $installDir "$ProductName.exe"
$vbsPath = Join-Path $installDir "lhb-launcher.vbs"

if (Test-Path $runKey) {
    $existingRun = (Get-ItemProperty -Path $runKey -Name $ProductName -ErrorAction SilentlyContinue).$ProductName
    if ($existingRun) {
        Write-Log "Rewriting auto-start Run key with VBS wrapper for correct WorkingDir"

        # Create a VBS launcher that sets the working directory
        $vbsContent = @"
Set objShell = CreateObject("WScript.Shell")
objShell.CurrentDirectory = "$installDir"
objShell.Run """$launcherExe""", 0, False
"@
        Set-Content -Path $vbsPath -Value $vbsContent -Encoding ASCII -Force
        Write-Log "Created VBS launcher at $vbsPath"

        # Update the Run key to use the VBS launcher
        $wscriptPath = "$env:SystemRoot\System32\wscript.exe"
        Set-ItemProperty -Path $runKey -Name $ProductName -Value "`"$wscriptPath`" `"$vbsPath`""
        Write-Log "Run key updated to use VBS launcher"
    }
}

# ---------------------------------------------------------------------------
# 5. Microsoft Defender exclusion (best-effort)
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
# 6. Done
# ---------------------------------------------------------------------------
Write-Log "Install script completed."
exit 0