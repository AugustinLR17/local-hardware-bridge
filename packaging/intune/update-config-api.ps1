<#
.SYNOPSIS
    Live config update via LHB HTTP API (no restart required).
.DESCRIPTION
    Pushes a new config.json to a running Local Hardware Bridge instance
    via the HTTP API (PUT /config.json). The app applies the config
    immediately without restarting.

    This is the preferred method when:
    - The app is already running on all target machines
    - You want zero-downtime config changes
    - You don't want to disrupt active print jobs or serial connections

    Deployment via Intune:
      Devices → Scripts → Add → upload this script + config-template.json

    The script:
    1. Reads config-template.json from the script directory.
    2. Sends it as a PUT /config.json request to http://127.0.0.1:57212.
    3. Includes the auth token from the current config (auto-detected).
    4. Reports success or failure per machine.

.PARAMETER Port
    The LHB port (default: 57212).

.PARAMETER Token
    The auth token. If not provided, the script reads it from the
    existing config.json on the machine.

.NOTES
    Designed for Intune "Scripts" deployment in User context.
    Exit codes: 0 = success, 1603 = failure.
    Requires config-template.json in the same directory as this script.
#>

[CmdletBinding()]
param(
    [int]$Port = 57212,
    [string]$Token = "",
    [string]$ConfigTemplate = ""
)

$ErrorActionPreference = "Stop"
$ProductName = "Local Hardware Bridge"
$InstallRegKey = "HKCU:\SOFTWARE\$ProductName"
$BaseUrl = "http://127.0.0.1:$Port"

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
# 1. Validate the config template exists
# ---------------------------------------------------------------------------
if (-not (Test-Path $ConfigTemplate)) {
    Write-Log "ERROR: config-template.json not found at $ConfigTemplate"
    exit 1603
}

Write-Log "Config template: $ConfigTemplate"
$configJson = Get-Content -Path $ConfigTemplate -Raw -Encoding UTF8

# ---------------------------------------------------------------------------
# 2. Determine the auth token
# ---------------------------------------------------------------------------
if (-not $Token) {
    # Try to read the token from the existing config.json
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

    $existingConfigPath = Join-Path $installDir "config.json"
    if (Test-Path $existingConfigPath) {
        try {
            $existingConfig = Get-Content -Path $existingConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($existingConfig.server.authentication.enabled -eq $true) {
                $Token = $existingConfig.server.authentication.token
                Write-Log "Auto-detected auth token from existing config.json"
            } else {
                Write-Log "Auth is disabled in existing config — no token needed."
            }
        } catch {
            Write-Log "WARNING: Could not parse existing config.json — will try without token."
        }
    } else {
        Write-Log "No existing config.json found — will try without token."
    }
}

# ---------------------------------------------------------------------------
# 3. Check if the app is running (health check)
# ---------------------------------------------------------------------------
Write-Log "Checking if LHB is running at $BaseUrl..."

try {
    $healthResponse = Invoke-WebRequest -Uri "$BaseUrl/system/health" -Method GET -TimeoutSec 5 -UseBasicParsing
    if ($healthResponse.StatusCode -eq 200) {
        Write-Log "LHB is running (health check OK)."
    } else {
        Write-Log "ERROR: Health check returned status $($healthResponse.StatusCode)."
        exit 1603
    }
} catch {
    Write-Log "ERROR: LHB is not running or not reachable at $BaseUrl."
    Write-Log "If the app is not installed yet, deploy it first via the Intune Win32 app."
    Write-Log "If you need to update the config AND restart the app, use update-config.ps1 instead."
    exit 1603
}

# ---------------------------------------------------------------------------
# 4. Push the new config via PUT /config.json
# ---------------------------------------------------------------------------
$headers = @{}
if ($Token -and $Token -ne "") {
    $headers["Authorization"] = "Bearer $Token"
}

Write-Log "Pushing new config to LHB API..."

try {
    $response = Invoke-WebRequest -Uri "$BaseUrl/config.json" -Method PUT -Headers $headers -Body $configJson -ContentType "application/json" -TimeoutSec 10 -UseBasicParsing

    if ($response.StatusCode -eq 200) {
        Write-Log "Config updated successfully via API (no restart needed)."
    } else {
        Write-Log "ERROR: API returned status $($response.StatusCode)."
        exit 1603
    }
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -eq 401) {
        Write-Log "ERROR: Authentication failed (401). The token may have changed in the new config."
        Write-Log "If you are changing the token itself, use update-config.ps1 (file replacement + restart) instead."
        exit 1603
    } else {
        Write-Log "ERROR: Failed to push config: $($_.Exception.Message)"
        exit 1603
    }
}

# ---------------------------------------------------------------------------
# 5. Verify the new config is active
# ---------------------------------------------------------------------------
try {
    $verifyResponse = Invoke-WebRequest -Uri "$BaseUrl/system/health" -Method GET -TimeoutSec 5 -UseBasicParsing
    Write-Log "Post-update health check: OK (status $($verifyResponse.StatusCode))."
} catch {
    Write-Log "WARNING: Post-update health check failed — the app may be processing the config change."
}

# ---------------------------------------------------------------------------
# 6. Done
# ---------------------------------------------------------------------------
Write-Log "Live config update completed."
exit 0
