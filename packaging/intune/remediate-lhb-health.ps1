<#
.SYNOPSIS
    Intune Remediation - remediation script for Local Hardware Bridge health.
.DESCRIPTION
    Fixes what detect-lhb-health.ps1 flagged (run in USER context):
      1. Not installed        -> cannot fix here; exits 1 (deploy the Win32 app).
      2. Config missing/empty -> rebuilds config.json; if a legacy WebApp
         Hardware Bridge config exists, grafts its printer/serial mappings into
         enterprise defaults (keeps port 57212 and auto-update; the legacy
         default port 12212 is NOT carried over).
      3. Auto-start missing   -> recreates the VBS launcher + HKCU Run key
         (same logic as install.ps1).
      4. Not running          -> starts the app via the VBS launcher and waits
         for /system/health.
    Exit 0 = healthy after remediation. Exit 1 = still broken (see output).
#>

$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$LegacyConfig = Join-Path $env:LOCALAPPDATA "WebApp Hardware Bridge\config.json"

$actions = @()

function Write-Log([string]$Msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$ts] $Msg"
}

# --- Locate install ----------------------------------------------------------
$installDir = $null
if (Test-Path $InstallRegKey) {
    $installDir = (Get-ItemProperty -Path $InstallRegKey -Name "Install_Dir" -ErrorAction SilentlyContinue).Install_Dir
}
if (-not $installDir) { $installDir = Join-Path $env:LOCALAPPDATA $ProductName }
$exePath = Join-Path $installDir "$ProductName.exe"

if (-not (Test-Path $exePath)) {
    Write-Output "NOT_INSTALLED: cannot remediate, deploy the Win32 app"
    exit 1
}

# --- 2. Config ---------------------------------------------------------------
$configPath = Join-Path $installDir "config.json"

# Enterprise defaults (mirrors packaging/intune/config-template.json)
$defaults = [ordered]@{
    server = [ordered]@{
        authentication = @{ enabled = $false }
        bind = "127.0.0.1"
        port = 57212
    }
    serial  = [ordered]@{ enabled = $false; mappings = @() }
    printer = [ordered]@{ enabled = $true; fallbackToDefault = $true; mappings = @() }
    update  = [ordered]@{ enabled = $true; autoDownload = $true; autoInstall = $true }
}

$cfg = $null
if (Test-Path $configPath) {
    try { $cfg = Get-Content -Path $configPath -Raw | ConvertFrom-Json } catch { $cfg = $null }
}

$needsConfigWrite = $false
if (-not $cfg) {
    $cfg = [pscustomobject]$defaults
    $needsConfigWrite = $true
    $actions += "config rebuilt from defaults"
}

# Graft legacy WHB mappings when current mappings are empty
$curMappings = @()
try { $curMappings = @($cfg.printer.mappings) } catch {}
if ($curMappings.Count -eq 0 -and (Test-Path $LegacyConfig)) {
    try {
        $legacy = Get-Content -Path $LegacyConfig -Raw | ConvertFrom-Json
        $legacyPrinter = @($legacy.printer.mappings)
        if ($legacyPrinter.Count -gt 0) {
            $cfg.printer.mappings = $legacyPrinter
            # Carry the printer behavior flags if present
            foreach ($flag in "autoAddUnknownType", "fallbackToDefault") {
                if ($null -ne $legacy.printer.$flag) {
                    $cfg.printer | Add-Member -NotePropertyName $flag -NotePropertyValue $legacy.printer.$flag -Force
                }
            }
            $needsConfigWrite = $true
            $actions += "migrated $($legacyPrinter.Count) printer mapping(s) from WebApp Hardware Bridge"
        }
        $legacySerial = @($legacy.serial.mappings)
        if ($legacySerial.Count -gt 0 -and @($cfg.serial.mappings).Count -eq 0) {
            $cfg.serial.mappings = $legacySerial
            if ($legacy.serial.enabled) { $cfg.serial.enabled = $true }
            $needsConfigWrite = $true
            $actions += "migrated $($legacySerial.Count) serial mapping(s)"
        }
        # NOTE: server section (port 12212 era) is deliberately NOT migrated.
    } catch {
        Write-Log "Legacy config unreadable, skipping migration: $($_.Exception.Message)"
    }
}

if ($needsConfigWrite) {
    $cfg | ConvertTo-Json -Depth 10 | Set-Content -Path $configPath -Encoding UTF8
    Write-Log "config.json written: $configPath"
}

# --- 3. Auto-start (VBS launcher + Run key, same as install.ps1) --------------
$vbsPath = Join-Path $installDir "lhb-launcher.vbs"
if (-not (Test-Path $vbsPath)) {
    $vbsContent = @"
Set objShell = CreateObject("WScript.Shell")
objShell.CurrentDirectory = "$installDir"
objShell.Run """$exePath""", 0, False
"@
    Set-Content -Path $vbsPath -Value $vbsContent -Encoding ASCII -Force
    $actions += "VBS launcher recreated"
}

$wscriptPath = "$env:SystemRoot\System32\wscript.exe"
$expectedRun = "`"$wscriptPath`" `"$vbsPath`""
$runVal = (Get-ItemProperty -Path $RunKey -Name $ProductName -ErrorAction SilentlyContinue).$ProductName
if ($runVal -ne $expectedRun) {
    Set-ItemProperty -Path $RunKey -Name $ProductName -Value $expectedRun
    $actions += "autostart Run key fixed"
}

# --- 4. Running ---------------------------------------------------------------
$port = 57212
try { if ($cfg.server.port) { $port = [int]$cfg.server.port } } catch {}

function Test-Health {
    try {
        $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$port/system/health" -UseBasicParsing -TimeoutSec 3
        return ($resp.StatusCode -eq 200)
    } catch { return $false }
}

$running = ($null -ne (Get-Process -Name $ProductName -ErrorAction SilentlyContinue)) -or (Test-Health)

if (-not $running) {
    Write-Log "Starting $ProductName via VBS launcher"
    Start-Process -FilePath $wscriptPath -ArgumentList "`"$vbsPath`""
    $actions += "app started"
} elseif ($needsConfigWrite) {
    # Config changed while the app was running: restart so it picks it up
    Write-Log "Restarting $ProductName to apply migrated config"
    Stop-Process -Name $ProductName -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Start-Process -FilePath $wscriptPath -ArgumentList "`"$vbsPath`""
    $actions += "app restarted with new config"
}

# Wait up to 30 s for health
$healthy = $false
for ($i = 0; $i -lt 15; $i++) {
    if (Test-Health) { $healthy = $true; break }
    Start-Sleep -Seconds 2
}

# --- Verdict ------------------------------------------------------------------
if ($actions.Count -eq 0) { $actions += "nothing to do" }
if ($healthy) {
    Write-Output ("REMEDIATED: " + ($actions -join "; ") + " | health OK on port $port")
    exit 0
}
Write-Output ("STILL_BROKEN: " + ($actions -join "; ") + " | health check failed on port $port")
exit 1
