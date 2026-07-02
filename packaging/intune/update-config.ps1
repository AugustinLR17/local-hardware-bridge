<#
.SYNOPSIS
    Intune config-only update script for Local Hardware Bridge.
.DESCRIPTION
    Replaces config.json on all machines with a new config-template.json
    and restarts the application. Use this when you need to push config
    changes (new printer mappings, token change, endpoint passwords, etc.)
    WITHOUT deploying a new version of the app itself.

    Deployment via Intune:
      Devices → Scripts → Add → upload this script + config-template.json

    The script:
    1. Locates the install directory (registry or fallback).
    2. Backs up the existing config.json to config.json.bak.
    3. Copies config-template.json → config.json (overwrite).
    4. Restarts the app via the VBS launcher (preserves WorkingDir fix).

.NOTES
    Designed for Intune "Scripts" deployment in User context.
    Exit codes: 0 = success, 1603 = failure.
    Requires config-template.json in the same directory as this script.
#>

[CmdletBinding()]
param(
    [string]$ConfigTemplate = ""
)

$ErrorActionPreference = "Stop"
$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"

# ---------------------------------------------------------------------------
# 0. Resolve script directory
# ---------------------------------------------------------------------------
if ($PSScriptRoot) {
    $ScriptDir = $PSScriptRoot
} elseif ($MyInvocation.MyCommand.Path) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
} else {
    $ScriptDir = (Get-Location).Path
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
# 1. Determine the install directory
# ---------------------------------------------------------------------------
$installDir = $null
if (Test-Path $InstallRegKey) {
    $regVal = Get-ItemProperty -Path $InstallRegKey -Name "Install_Dir" -ErrorAction SilentlyContinue
    if ($regVal) {
        $installDir = $regVal.Install_Dir
    }
}

if (-not $installDir -or -not (Test-Path $installDir)) {
    $installDir = "$env:LOCALAPPDATA\$ProductName"
    Write-Log "Registry lookup failed, falling back to $installDir"
}

if (-not (Test-Path $installDir)) {
    Write-Log "ERROR: Install directory not found: $installDir — is LHB installed?"
    exit 1603
}

Write-Log "Install directory: $installDir"

# ---------------------------------------------------------------------------
# 2. Validate the config template exists
# ---------------------------------------------------------------------------
if (-not (Test-Path $ConfigTemplate)) {
    Write-Log "ERROR: config-template.json not found at $ConfigTemplate"
    exit 1603
}

Write-Log "Config template: $ConfigTemplate"

# ---------------------------------------------------------------------------
# 3. Backup existing config.json (if present)
# ---------------------------------------------------------------------------
$configDest = Join-Path $installDir "config.json"
$configBackup = Join-Path $installDir "config.json.bak"

if (Test-Path $configDest) {
    Write-Log "Backing up existing config.json to config.json.bak"
    Copy-Item -Path $configDest -Destination $configBackup -Force
} else {
    Write-Log "No existing config.json found — first deployment."
}

# ---------------------------------------------------------------------------
# 4. Deploy the new config
# ---------------------------------------------------------------------------
Write-Log "Deploying new config-template.json -> config.json"
Copy-Item -Path $ConfigTemplate -Destination $configDest -Force
Write-Log "Config deployed successfully."

# ---------------------------------------------------------------------------
# 5. Restart the application
# ---------------------------------------------------------------------------
Write-Log "Restarting $ProductName..."

# Stop the running instance
Stop-Process -Name "Local Hardware Bridge" -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Restart via the VBS launcher (preserves the WorkingDir fix from install.ps1)
$vbsPath = Join-Path $installDir "lhb-launcher.vbs"
$launcherExe = Join-Path $installDir "$ProductName.exe"

if (Test-Path $vbsPath) {
    Write-Log "Starting via VBS launcher: $vbsPath"
    Start-Process -FilePath "wscript.exe" -ArgumentList "`"$vbsPath`""
} else {
    # Fallback: create a VBS launcher if it doesn't exist
    Write-Log "VBS launcher not found — creating one."
    $vbsContent = @"
Set objShell = CreateObject("WScript.Shell")
objShell.CurrentDirectory = "$installDir"
objShell.Run """$launcherExe""", 0, False
"@
    Set-Content -Path $vbsPath -Value $vbsContent -Encoding ASCII -Force
    Start-Process -FilePath "wscript.exe" -ArgumentList "`"$vbsPath`""
}

Start-Sleep -Seconds 3

# Verify the app is running
$proc = Get-Process -Name "Local Hardware Bridge" -ErrorAction SilentlyContinue
if ($proc) {
    Write-Log "App restarted successfully (PID: $($proc.Id))."
} else {
    Write-Log "WARNING: App process not detected after restart — it may still be starting up."
}

# ---------------------------------------------------------------------------
# 6. Done
# ---------------------------------------------------------------------------
Write-Log "Config update completed."
exit 0
