param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # Keep Windows JDK local sockets in a short, ASCII path.
    $previous = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = "$previous -Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=$root"

    # Derive the artefact name from the app's versionName so releases never drift.
    $gradle = Get-Content -LiteralPath (Join-Path $root 'app\build.gradle.kts') -Raw
    $match = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw 'versionName not found in app\build.gradle.kts' }
    $apkName = "OmniBar-$($match.Groups[1].Value)-compat.apk"

    $tasks = @(':app:assembleCompat', ':app:lintCompat')
    if (-not $SkipTests) { $tasks += ':app:testCompatUnitTest' }
    # Gradle and the JDK write warnings to stderr (e.g. "Picked up JAVA_TOOL_OPTIONS").
    # PowerShell 5.1 turns those into error records, which would abort the script under
    # $ErrorActionPreference = 'Stop'. Only the exit code should decide success.
    $previousPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat @tasks --console=plain 2>&1
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPref
    }
    if ($gradleExit -ne 0) { throw "Gradle failed: $gradleExit" }
    New-Item -ItemType Directory -Path (Join-Path $root 'dist') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'app\build\outputs\apk\compat\app-compat.apk') `
        -Destination (Join-Path $root "dist\$apkName")
    $hash = Get-FileHash -LiteralPath (Join-Path $root "dist\$apkName") -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  $apkName" |
        Set-Content -LiteralPath (Join-Path $root "dist\$apkName.sha256") -Encoding ascii
    Write-Output "built: $apkName"
    $hash
} finally {
    $env:JAVA_TOOL_OPTIONS = $previous
    Pop-Location
}
