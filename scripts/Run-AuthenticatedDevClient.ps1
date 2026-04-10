param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-11.0.30.7-hotspot',
    [switch]$SkipCredentialCheck
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$credentialsPath = Join-Path $HOME '.runelite\credentials.properties'
$sideloadDir = Join-Path $HOME '.runelite\sideloaded-plugins'
$devBackupDir = Join-Path $HOME '.runelite\_plugin-backups\dev-launch-disabled'
$movedPluginCopies = @()

if (!(Test-Path $JavaHome)) {
    throw "Java 11 was not found at $JavaHome"
}

if (-not $SkipCredentialCheck -and !(Test-Path $credentialsPath)) {
    throw "Missing $credentialsPath. Run .\scripts\Setup-AuthenticatedDev.ps1 first and launch RuneLite once via the Jagex Launcher."
}

if (Test-Path $sideloadDir) {
    $sideloadedCopies = Get-ChildItem -LiteralPath $sideloadDir -Filter 'clan-promotion-tracker-*.jar' -File -ErrorAction SilentlyContinue
    if (@($sideloadedCopies).Count -gt 0) {
        if (!(Test-Path $devBackupDir)) {
            New-Item -ItemType Directory -Path $devBackupDir | Out-Null
        }

        foreach ($copy in $sideloadedCopies) {
            $destination = Join-Path $devBackupDir $copy.Name
            Move-Item -LiteralPath $copy.FullName -Destination $destination -Force
            $movedPluginCopies += [PSCustomObject]@{
                Name = $copy.Name
                BackupPath = $destination
                RestorePath = Join-Path $sideloadDir $copy.Name
            }
        }

        Write-Host "Temporarily moved $($movedPluginCopies.Count) sideloaded Clan Promotion Tracker jar(s) to avoid duplicate plugin entries during dev launch."
    }
}

Push-Location $repoRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    & .\gradlew.bat run
} finally {
    foreach ($copy in $movedPluginCopies) {
        if (Test-Path -LiteralPath $copy.BackupPath) {
            Move-Item -LiteralPath $copy.BackupPath -Destination $copy.RestorePath -Force
        }
    }

    if ($movedPluginCopies.Count -gt 0) {
        Write-Host "Restored sideloaded Clan Promotion Tracker jar(s) after dev launch."
    }

    Pop-Location
}
