param(
    [switch]$VerifyOnly,
    [int]$WaitSeconds = 0
)

$ErrorActionPreference = 'Stop'

$launcherPath = Join-Path $env:LOCALAPPDATA 'RuneLite\RuneLite.exe'
$configureShortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\RuneLite\RuneLite (configure).lnk'
$credentialsPath = Join-Path $HOME '.runelite\credentials.properties'

function Write-Step($message) {
    Write-Host " - $message"
}

function Show-CredentialStatus() {
    if (Test-Path $credentialsPath) {
        $file = Get-Item $credentialsPath
        Write-Host "credentials.properties found:" -ForegroundColor Green
        Write-Host "  $($file.FullName)"
        Write-Host "  Last updated: $($file.LastWriteTime)"
        return $true
    }

    Write-Host "credentials.properties is missing." -ForegroundColor Yellow
    Write-Host "Expected at: $credentialsPath"
    return $false
}

if (!(Test-Path $launcherPath)) {
    throw "RuneLite launcher was not found at $launcherPath"
}

$versionInfo = (Get-Item $launcherPath).VersionInfo
$versionText = if ($versionInfo.FileVersion) { $versionInfo.FileVersion } elseif ($versionInfo.ProductVersion) { $versionInfo.ProductVersion } else { 'unknown' }
Write-Host "RuneLite launcher: $launcherPath"
Write-Host "Launcher version: $versionText"

if ($VerifyOnly) {
    Show-CredentialStatus | Out-Null
    exit 0
}

$alreadyConfigured = Show-CredentialStatus
if ($alreadyConfigured) {
    Write-Host ""
    Write-Host "Your authenticated dev credential file already exists." -ForegroundColor Green
    Write-Host "You can launch the local dev client with:"
    Write-Host "  .\scripts\Run-AuthenticatedDevClient.ps1"
    exit 0
}

Write-Host ""
Write-Host "Opening RuneLite configure..." -ForegroundColor Cyan
if (Test-Path $configureShortcut) {
    Start-Process $configureShortcut
} else {
    Start-Process $launcherPath -ArgumentList '--configure'
}

Write-Host ""
Write-Host "Finish these steps in the launcher UI:" -ForegroundColor Cyan
Write-Step "In the Client arguments box, enter --insecure-write-credentials"
Write-Step "Click Save"
Write-Step "Launch RuneLite once through the Jagex Launcher and log in"
Write-Step "After RuneLite finishes launching, rerun this script with -VerifyOnly"

if ($WaitSeconds -gt 0) {
    Write-Host ""
    Write-Host "Waiting up to $WaitSeconds seconds for credentials.properties to appear..."
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        Start-Sleep -Seconds 2
        if (Test-Path $credentialsPath) {
            break
        }
    } while ((Get-Date) -lt $deadline)

    Write-Host ""
    Show-CredentialStatus | Out-Null
}
