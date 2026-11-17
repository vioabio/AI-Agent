[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$Offline, [ValidateRange(1,65535)][int]$Port = 8123)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) {
    $message = "Port $Port is still listening. Stop the backend in IDEA before rebuilding; running target/classes will not be overwritten."
    if ($WhatIfPreference) { Write-Output $message; return }
    throw $message
}
if (-not $PSCmdlet.ShouldProcess($projectRoot, 'Compile backend into target/classes')) { return }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $fallbackJdk = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr'
    if (-not (Test-Path -LiteralPath (Join-Path $fallbackJdk 'bin/java.exe'))) { throw 'Set JAVA_HOME to an approved JDK 21 or newer.' }
    $env:JAVA_HOME = $fallbackJdk
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$mavenArgs = @('-B', '-ntp', "-Dvio.build.directory=$projectRoot/target", '-DskipTests')
if ($Offline) { $mavenArgs += '-o' }
$mavenArgs += 'compile'
Push-Location $projectRoot
try {
    $nativeErrorPreference = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; & (Join-Path $projectRoot 'mvnw.cmd') @mavenArgs; $buildExitCode = $LASTEXITCODE }
    finally { $ErrorActionPreference = $nativeErrorPreference }
    if ($buildExitCode -ne 0) { throw 'Backend compilation failed. Do not start the old build.' }
    Write-Output 'Backend compiled. Run VioAiAgentApplication in IDEA with your existing model environment.'
} finally { Pop-Location }
