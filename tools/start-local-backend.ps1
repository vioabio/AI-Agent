param([int]$Port = 8123)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repoRoot
if ($Port -lt 1 -or $Port -gt 65535) { throw 'Invalid port' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. Stop the old backend in IDEA first; this script will not terminate an unknown process."
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to your installed JDK 21 directory first.'
}
# Reuse local IDEA environment settings without displaying or copying secrets.
$runConfig = Join-Path $repoRoot '.run/VioAiAgentApplication.run.xml'
if (Test-Path -LiteralPath $runConfig) {
    [xml]$settings = Get-Content -LiteralPath $runConfig -Raw
    foreach ($entry in $settings.SelectNodes('//envs/env')) {
        [Environment]::SetEnvironmentVariable($entry.GetAttribute('name'), $entry.GetAttribute('value'), 'Process')
    }
}
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:VIO_POKEMON_REFERENCE_ENABLED = 'true'
# Maven recompiles changed classes before starting; refreshing Vite cannot do this.
& (Join-Path $repoRoot 'mvnw.cmd') spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port --spring.profiles.active=local --vio.pokemon.reference.enabled=true"
exit $LASTEXITCODE
