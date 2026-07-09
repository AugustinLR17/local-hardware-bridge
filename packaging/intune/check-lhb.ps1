<#
.SYNOPSIS
    Verifie que Local Hardware Bridge est installe, en auto-start, et lance.
    Corrige l'auto-start et demarre l'app si besoin. (Contexte UTILISATEUR)
    Exit 0 = OK apres verification/correction. Exit 1 = probleme non corrigeable.
#>
$ProductName = "Local Hardware Bridge"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$actions = @()

# --- 1. Installee ? ---
$installDir = (Get-ItemProperty "HKCU:\SOFTWARE\$ProductName" -ErrorAction SilentlyContinue).Install_Dir
if (-not $installDir) { $installDir = Join-Path $env:LOCALAPPDATA $ProductName }
$exePath = Join-Path $installDir "$ProductName.exe"
if (-not (Test-Path $exePath)) {
    Write-Output "NOT_INSTALLED: $exePath absent - deployer l'app Win32"
    exit 1
}

# --- 2. Auto-start (cle Run pointant directement sur l'exe signe) ---
# Plus de lanceur VBS/wscript : l'app reancre son dossier de travail au
# demarrage (AppHome), donc pointer Run sur l'exe suffit et evite les regles
# Defender ASR qui bloquent les scripts lancant des executables.
$staleVbs = Join-Path $installDir "lhb-launcher.vbs"
if (Test-Path $staleVbs) { Remove-Item $staleVbs -Force -ErrorAction SilentlyContinue; $actions += "VBS obsolete supprime" }
$expected = "`"$exePath`""
$runVal = (Get-ItemProperty $RunKey -Name $ProductName -ErrorAction SilentlyContinue).$ProductName
if ($runVal -ne $expected) {
    Set-ItemProperty -Path $RunKey -Name $ProductName -Value $expected
    $actions += "cle Run corrigee"
}

# --- 3. Lancee ? ---
$port = 57212
try {
    $cfg = Get-Content (Join-Path $installDir "config.json") -Raw | ConvertFrom-Json
    if ($cfg.server.port) { $port = [int]$cfg.server.port }
} catch {}
function Test-Health {
    try { (Invoke-WebRequest "http://127.0.0.1:$port/system/health" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 }
    catch { $false }
}
if (-not ((Get-Process -Name $ProductName -ErrorAction SilentlyContinue) -or (Test-Health))) {
    Start-Process -FilePath $exePath -WorkingDirectory $installDir
    $actions += "app demarree"
    Start-Sleep -Seconds 8
}

if ($actions.Count -eq 0) { $actions += "rien a faire" }
if (Test-Health) {
    Write-Output ("OK: " + ($actions -join "; ") + " | health OK port $port")
    exit 0
}
Write-Output ("KO: " + ($actions -join "; ") + " | health injoignable port $port")
exit 1
