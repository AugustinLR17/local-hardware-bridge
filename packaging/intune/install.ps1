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
# 4. Ensure auto-start points directly at the (signed) exe
# ---------------------------------------------------------------------------
# The app re-anchors its working directory to the install folder on startup
# (AppHome.anchor()), so it finds config.json even though Windows uses System32
# as the CWD at logon. Pointing the Run key straight at the signed exe — instead
# of a wscript/VBS wrapper — avoids Defender ASR rules that block scripts from
# launching executables, the usual cause of "auto-start silently fails" on
# managed (Intune) fleets. This is a safety net; the NSIS installer already
# writes the same value.
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$launcherExe = Join-Path $installDir "$ProductName.exe"
$staleVbs = Join-Path $installDir "lhb-launcher.vbs"

if (Test-Path $launcherExe) {
    Set-ItemProperty -Path $runKey -Name $ProductName -Value "`"$launcherExe`""
    Write-Log "Auto-start Run key set to launch the exe directly: $launcherExe"
}

# Remove the obsolete VBS launcher left by previous versions (no longer used).
if (Test-Path $staleVbs) {
    Remove-Item -Path $staleVbs -Force -ErrorAction SilentlyContinue
    Write-Log "Removed obsolete VBS launcher: $staleVbs"
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
# 6. Start the app now (so the bridge is reachable without waiting for logon)
# ---------------------------------------------------------------------------
# The auto-start Run key only fires at the next logon. In User context we can
# launch the (signed) exe immediately so the bridge answers on 127.0.0.1 right
# after deployment. Best-effort — never fail the install if the launch fails.
try {
    $running = Get-Process -Name $ProductName -ErrorAction SilentlyContinue
    if (-not $running -and (Test-Path $launcherExe)) {
        Start-Process -FilePath $launcherExe -WorkingDirectory $installDir
        Write-Log "Launched $ProductName."
    } else {
        Write-Log "App already running (or exe missing) — skipping immediate launch."
    }
} catch {
    Write-Log "Could not launch app now (it will start at next logon): $($_.Exception.Message)"
}

# ---------------------------------------------------------------------------
# 7. Done
# ---------------------------------------------------------------------------
Write-Log "Install script completed."
exit 0