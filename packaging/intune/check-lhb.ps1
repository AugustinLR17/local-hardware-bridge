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

# --- 2. Auto-start (lanceur VBS + cle Run) ---
$vbsPath = Join-Path $installDir "lhb-launcher.vbs"
if (-not (Test-Path $vbsPath)) {
    Set-Content -Path $vbsPath -Encoding ASCII -Force -Value @"
Set objShell = CreateObject("WScript.Shell")
objShell.CurrentDirectory = "$installDir"
objShell.Run """$exePath""", 0, False
"@
    $actions += "VBS recree"
}
$wscript = "$env:SystemRoot\System32\wscript.exe"
$expected = "`"$wscript`" `"$vbsPath`""
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
    Start-Process $wscript -ArgumentList "`"$vbsPath`""
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
