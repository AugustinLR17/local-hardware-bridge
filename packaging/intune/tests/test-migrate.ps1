$ErrorActionPreference="SilentlyContinue"
function New-Env([string]$name){ $d="/tmp/t/$name"; Remove-Item $d -Recurse -Force; New-Item -ItemType Directory "$d/Local Hardware Bridge","$d/WebApp Hardware Bridge" -Force | Out-Null; $env:LOCALAPPDATA=$d; $d }
function LHB($m){ @{printer=@{enabled=$true;mappings=$m};server=@{port=57212}} | ConvertTo-Json -Depth 10 | Set-Content "$env:LOCALAPPDATA/Local Hardware Bridge/config.json" }
function WHB($m){ @{printer=@{enabled=$true;mappings=$m};server=@{port=12212}} | ConvertTo-Json -Depth 10 | Set-Content "$env:LOCALAPPDATA/WebApp Hardware Bridge/config.json" }
$MAP=@(@{type="MAIN";name="Lexmark"},@{type="TICKET";name="Epson"})
$script="$PSScriptRoot/../migrate-whb-config.ps1"
function Run($label,$expectOut,$expectExit){
  $out = & pwsh -NoProfile -File $script 2>$null; $code=$LASTEXITCODE
  $ok = ($out -match $expectOut) -and ($code -eq $expectExit)
  Write-Host ("{0} {1}: out='{2}' exit={3}" -f ($(if($ok){"PASS"}else{"FAIL"}),$label,($out -join ";"),$code))
  if(-not $ok){$global:fails++}
}
$global:fails=0
# 1 pas de WHB
New-Env one | Out-Null; LHB @()
Run "1.sans-WHB" "NOOP: pas de config WHB" 0
# 2 WHB vide
New-Env two | Out-Null; LHB @(); WHB @()
Run "2.WHB-vide" "NOOP: config WHB vide" 0
# 3 LHB configuree et plus recente
New-Env three | Out-Null; WHB $MAP; Start-Sleep 1; LHB @(@{type="MAIN";name="Konica"})
Run "3.LHB-recente-nonvide" "NOOP: config LHB non vide et plus recente" 0
# 4 LHB fraiche (vide) plus recente que WHB -> DOIT migrer
New-Env four | Out-Null; WHB $MAP; Start-Sleep 1; LHB @()
Run "4.LHB-vide-migre" "MIGRATED: 2 mapping" 0
$m=(Get-Content "$env:LOCALAPPDATA/Local Hardware Bridge/config.json" -Raw|ConvertFrom-Json)
if(@($m.printer.mappings).Count -eq 2 -and $m.server.port -eq 57212 -and (Test-Path "$env:LOCALAPPDATA/Local Hardware Bridge/config.json.bak")){Write-Host "PASS 4b.contenu+port57212+bak"}else{Write-Host "FAIL 4b";$global:fails++}
# 5 re-run apres migration -> idempotent
Run "5.idempotent" "NOOP" 0
# 6 sections identiques
New-Env six | Out-Null; WHB $MAP; LHB $MAP
Run "6.identiques" "NOOP: section printer deja identique" 0
Write-Host ("RESULT: " + $(if($global:fails){"$global:fails FAIL"}else{"ALL PASS"}))
exit $global:fails
