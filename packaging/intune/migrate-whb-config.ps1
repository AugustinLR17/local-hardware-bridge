<#
.SYNOPSIS
    Copie la configuration IMPRIMANTES de l'ancienne app WebApp Hardware Bridge
    (fork d'origine) dans Local Hardware Bridge, en ECRASANT la section printer
    de LHB - SAUF si la config WHB est absente ou sans mappings (alors: no-op).
    La config serveur de WHB (port 12212...) n'est PAS reprise. Redemarre LHB.
    (Contexte UTILISATEUR)
#>
$ProductName = "Local Hardware Bridge"
$LegacyConfig = Join-Path $env:LOCALAPPDATA "WebApp Hardware Bridge\config.json"

$installDir = (Get-ItemProperty "HKCU:\SOFTWARE\$ProductName" -ErrorAction SilentlyContinue).Install_Dir
if (-not $installDir) { $installDir = Join-Path $env:LOCALAPPDATA $ProductName }
$configPath = Join-Path $installDir "config.json"

if (-not (Test-Path $configPath)) { Write-Output "SKIP: LHB config absente ($configPath)"; exit 1 }
if (-not (Test-Path $LegacyConfig)) { Write-Output "NOOP: pas de config WHB"; exit 0 }

try { $legacy = Get-Content $LegacyConfig -Raw | ConvertFrom-Json }
catch { Write-Output "NOOP: config WHB illisible"; exit 0 }

$legacyMappings = @($legacy.printer.mappings)
if ($legacyMappings.Count -eq 0) { Write-Output "NOOP: config WHB vide (0 mapping)"; exit 0 }

try { $cfg = Get-Content $configPath -Raw | ConvertFrom-Json }
catch { Write-Output "KO: config LHB illisible"; exit 1 }

# Sauvegarde puis ECRASE la section printer avec celle de WHB
Copy-Item $configPath "$configPath.bak" -Force
$cfg.printer = $legacy.printer
$cfg | ConvertTo-Json -Depth 10 | Set-Content $configPath -Encoding UTF8

# Redemarre LHB pour appliquer
Stop-Process -Name $ProductName -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
$vbs = Join-Path $installDir "lhb-launcher.vbs"
if (Test-Path $vbs) { Start-Process "$env:SystemRoot\System32\wscript.exe" -ArgumentList "`"$vbs`"" }
else { Start-Process (Join-Path $installDir "$ProductName.exe") -WorkingDirectory $installDir }

Write-Output "MIGRATED: $($legacyMappings.Count) mapping(s) imprimante copies depuis WHB (backup: config.json.bak)"
exit 0
