<#
.SYNOPSIS
    Downloads IntuneWinAppUtil.exe with retry logic.
.DESCRIPTION
    GitHub sometimes drops connections on CI runners, so we retry up to
    3 times per URL with a backoff, and fall back to a versioned release
    URL if the /latest/ redirect fails.
#>
$ErrorActionPreference = "Stop"

$urls = @(
    "https://github.com/microsoft/Microsoft-Win32-Content-Prep-Tool/releases/latest/download/IntuneWinAppUtil.exe",
    "https://github.com/microsoft/Microsoft-Win32-Content-Prep-Tool/releases/download/v1.8.4/IntuneWinAppUtil.exe"
)

$downloaded = $false
foreach ($url in $urls) {
    for ($i = 1; $i -le 3; $i++) {
        Write-Host "Attempt $i - $url"
        try {
            Invoke-WebRequest -Uri $url -OutFile "IntuneWinAppUtil.exe" -UseBasicParsing
            $downloaded = $true
            break
        } catch {
            Write-Host "Failed: $($_.Exception.Message)"
            Start-Sleep -Seconds 5
        }
    }
    if ($downloaded) { break }
}

if (-not $downloaded) {
    throw "Failed to download IntuneWinAppUtil.exe after all retries"
}

Write-Host "Downloaded IntuneWinAppUtil.exe successfully"
