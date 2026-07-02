<#
.SYNOPSIS
    Intune uninstall wrapper for Local Hardware Bridge.
.DESCRIPTION
    1. Stops any running instance of Local Hardware Bridge.
    2. Runs the NSIS uninstaller silently (/S).
    3. Removes the Defender exclusion if it was set by install.ps1.
    4. Cleans up any residual config files.
.NOTES
    Exit codes: 0 = success (even if some cleanup steps were no-ops).
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"
$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"

function Write-Log([string]$Msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$ts] $Msg"
}

# ---------------------------------------------------------------------------
# 1. Stop running instance
# ---------------------------------------------------------------------------
Write-Log "Stopping $ProductName if running..."
Stop-Process -Name "Local Hardware Bridge" -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# ---------------------------------------------------------------------------
# 2. Determine install directory and run uninstaller
# ---------------------------------------------------------------------------
$installDir = $null
if (Test-Path $InstallRegKey) {
    $regVal = Get-ItemProperty -Path $InstallRegKey -Name "Install_Dir" -ErrorAction SilentlyContinue
    if ($regVal) {
        $installDir = $regVal.Install_Dir
    }
}

if (-not $installDir) {
    $installDir = "$env:LOCALAPPDATA\$ProductName"
}

$uninstaller = Join-Path $installDir "uninstall.exe"

if (Test-Path $uninstaller) {
    Write-Log "Running uninstaller: $uninstaller /S"
    $process = Start-Process -FilePath $uninstaller -ArgumentList "/S" -Wait -PassThru -NoNewWindow
    Write-Log "Uninstaller exited with code $($process.ExitCode)"
} else {
    Write-Log "Uninstaller not found at $uninstaller — app may already be uninstalled."
}

# ---------------------------------------------------------------------------
# 3. Remove Defender exclusion (best-effort, only if elevated)
# ---------------------------------------------------------------------------
try {
    $defender = Get-MpComputerStatus -ErrorAction SilentlyContinue
    if ($defender -and $defender.AMServiceEnabled) {
        Remove-MpPreference -ExclusionPath $installDir -ErrorAction SilentlyContinue
        Write-Log "Defender exclusion removed for $installDir"
    }
} catch {
    Write-Log "Defender exclusion removal skipped (no admin rights)."
}

# ---------------------------------------------------------------------------
# 4. Clean up residual files (config.json, logs, downloads, etc.)
# ---------------------------------------------------------------------------
if (Test-Path $installDir) {
    Write-Log "Cleaning up residual files in $installDir"
    Remove-Item -Path $installDir -Recurse -Force -ErrorAction SilentlyContinue
}

# Also clean up the auto-start registry value (in case the uninstaller missed it)
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name $ProductName -ErrorAction SilentlyContinue

Write-Log "Uninstall script completed."
exit 0