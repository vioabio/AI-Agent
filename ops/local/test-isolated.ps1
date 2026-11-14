param(
    [ValidatePattern('^[A-Za-z0-9_.,*#$]+$')][string]$Tests,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# Every run gets its own output; never recompiles the running target/classes JVM.
$buildPath = Join-Path $projectRoot ('artifacts/isolated-build-' + [guid]::NewGuid().ToString('N'))
$mavenArgs = @('-B', '-ntp', "-Dvio.build.directory=$buildPath")
if ($Offline) { $mavenArgs += '-o' }
if ($Tests) { $mavenArgs += "-Dtest=$Tests" }
$mavenArgs += 'test'
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'mvnw.cmd') @mavenArgs
    $testExitCode = $LASTEXITCODE
    Write-Output "Isolated build output: $buildPath"
    if ($testExitCode -ne 0) { throw "Maven tests failed (exit $testExitCode)" }
} finally { Pop-Location }
