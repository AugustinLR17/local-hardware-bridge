$ErrorActionPreference="SilentlyContinue"; $global:fails=0
function New-Env{ $d="/tmp/o/$(Get-Random)"; New-Item -ItemType Directory "$d/Local Hardware Bridge" -Force|Out-Null; $env:LOCALAPPDATA=$d }
function Run($label,$file,$expectOut,$expectExit){
  $out = & pwsh -NoProfile -File "$PSScriptRoot/../$file" 2>$null; $c=$LASTEXITCODE
  $ok = ($out -match $expectOut) -and ($c -eq $expectExit)
  Write-Host ("{0} {1}: '{2}' exit={3}" -f ($(if($ok){"PASS"}else{"FAIL"}),$label,($out|Select -Last 1),$c))
  if(-not $ok){$global:fails++}
}
# ---- check-lhb ----
New-Env
Run "check.non-installee" "check-lhb.ps1" "NOT_INSTALLED" 1
New-Env; $ld="$env:LOCALAPPDATA/Local Hardware Bridge"
Set-Content "$ld/Local Hardware Bridge.exe" "stub"
'{"server":{"port":57391}}' | Set-Content "$ld/config.json"
Run "check.installee-pas-lancee" "check-lhb.ps1" "KO:" 1
if(-not (Test-Path "$ld/lhb-launcher.vbs")){Write-Host "PASS check.pas-de-vbs"}else{Write-Host "FAIL check.vbs-ne-doit-plus-exister";$global:fails++}
$j=Start-Job { $l=[System.Net.HttpListener]::new(); $l.Prefixes.Add("http://127.0.0.1:57391/"); $l.Start()
  while($true){ $c=$l.GetContext(); $b=[Text.Encoding]::UTF8.GetBytes('{"status":"UP"}'); $c.Response.OutputStream.Write($b,0,$b.Length); $c.Response.Close() } }
Start-Sleep 2
Run "check.installee-et-lancee" "check-lhb.ps1" "OK:" 0
Stop-Job $j; Remove-Job $j -Force
# ---- install.ps1 : installeur absent ----
New-Env; Push-Location /tmp/o
Run "install.exe-absent" "install.ps1" "" $(if($IsWindows){1603}else{67})  # Linux tronque les codes de sortie a 8 bits
Pop-Location
# ---- update-config.ps1 : backup + remplacement ----
New-Env; $ld="$env:LOCALAPPDATA/Local Hardware Bridge"
'{"printer":{"mappings":[{"type":"OLD"}]}}' | Set-Content "$ld/config.json"
& pwsh -NoProfile -File $PSScriptRoot/../update-config.ps1 2>$null | Out-Null
$new = Get-Content "$ld/config.json" -Raw
if((Test-Path "$ld/config.json.bak") -and ($new -notmatch '"OLD"') -and ($new -match 'fallbackToDefault')){Write-Host "PASS update-config.bak+template"}else{Write-Host "FAIL update-config: bak=$(Test-Path "$ld/config.json.bak")"; $global:fails++}
# ---- uninstall.ps1 : nettoyage complet ----
New-Env; $ld="$env:LOCALAPPDATA/Local Hardware Bridge"
Set-Content "$ld/config.json" "x"; Set-Content "$ld/uninstall.exe" "stub"
Run "uninstall.cleanup" "uninstall.ps1" "completed" 0
if(-not (Test-Path $ld)){Write-Host "PASS uninstall.dossier-supprime"}else{Write-Host "FAIL uninstall.dossier";$global:fails++}
Write-Host ("RESULT: " + $(if($global:fails){"$global:fails FAIL"}else{"ALL PASS"}))
exit $global:fails
