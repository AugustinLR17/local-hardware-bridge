<#
.SYNOPSIS
    Intune Remediation - detection script for Local Hardware Bridge health.
.DESCRIPTION
    Checks, per user session (run in USER context):
      1. LHB is installed (registry Install_Dir + launcher exe present)
      2. config.json exists, parses, and has printer mappings
         (or none are recoverable from a legacy WebApp Hardware Bridge config)
      3. Auto-start is registered (HKCU\...\Run "Local Hardware Bridge")
      4. The app is currently running (process or /system/health responds)
    Exit 0 = compliant. Exit 1 = remediation needed (remediate-lhb-health.ps1).
    The last output line is shown in the Intune console per device.
#>

$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$LegacyConfig = Join-Path $env:LOCALAPPDATA "WebApp Hardware Bridge\config.json"

$issues = @()

# --- 1. Installed -----------------------------------------------------------
$installDir = $null
if (Test-Path $InstallRegKey) {
    $installDir = (Get-ItemProperty -Path $InstallRegKey -Name "Install_Dir" -ErrorAction SilentlyContinue).Install_Dir
}
if (-not $installDir) { $installDir = Join-Path $env:LOCALAPPDATA $ProductName }
$exePath = Join-Path $installDir "$ProductName.exe"

if (-not (Test-Path $exePath)) {
    # Not installed: remediation cannot fix this (the Win32 app must install it),
    # but flag it so the device shows up in the report.
    Write-Output "NOT_INSTALLED: $exePath missing"
    exit 1
}

# --- 2. Config --------------------------------------------------------------
$configPath = Join-Path $installDir "config.json"
$configOk = $false
$mappingCount = -1
if (Test-Path $configPath) {
    try {
        $cfg = Get-Content -Path $configPath -Raw | ConvertFrom-Json
        $mappingCount = @($cfg.printer.mappings).Count
        $configOk = $true
    } catch {
        $issues += "CONFIG_UNPARSEABLE"
    }
} else {
    $issues += "CONFIG_MISSING"
}

if ($configOk -and $mappingCount -eq 0 -and (Test-Path $LegacyConfig)) {
    # Empty mappings while a legacy WHB config with mappings exists -> migrate
    try {
        $legacy = Get-Content -Path $LegacyConfig -Raw | ConvertFrom-Json
        if (@($legacy.printer.mappings).Count -gt 0) {
            $issues += "CONFIG_EMPTY_LEGACY_AVAILABLE"
        }
    } catch { <# unreadable legacy config: nothing to migrate #> }
}

# --- 3. Auto-start ----------------------------------------------------------
$runVal = (Get-ItemProperty -Path $RunKey -Name $ProductName -ErrorAction SilentlyContinue).$ProductName
if (-not $runVal) {
    $issues += "AUTOSTART_MISSING"
}

# --- 4. Running -------------------------------------------------------------
$running = $null -ne (Get-Process -Name $ProductName -ErrorAction SilentlyContinue)
if (-not $running) {
    # Fallback: health endpoint (process name can differ when run from a JAR)
    $port = 57212
    try { if ($cfg -and $cfg.server.port) { $port = [int]$cfg.server.port } } catch {}
    try {
        $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$port/system/health" -UseBasicParsing -TimeoutSec 3
        if ($resp.StatusCode -eq 200) { $running = $true }
    } catch {}
}
if (-not $running) {
    $issues += "NOT_RUNNING"
}

# --- Verdict ----------------------------------------------------------------
if ($issues.Count -eq 0) {
    Write-Output "OK: installed, config ($mappingCount mapping(s)), autostart, running"
    exit 0
}
Write-Output ("NEEDS_REMEDIATION: " + ($issues -join ", "))
exit 1
