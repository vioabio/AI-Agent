[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$buildDirectory = Join-Path $projectRoot '.local-data/mcp-server-build'
if (-not $PSCmdlet.ShouldProcess($buildDirectory, 'Build standalone MCP and write local client configuration')) { return }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr'
}
$javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'Set JAVA_HOME to an approved JDK 21 or newer.' }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
New-Item -ItemType Directory -Force -Path $buildDirectory | Out-Null
$classpathFile = Join-Path $buildDirectory 'runtime-classpath.txt'
$buildLog = Join-Path $buildDirectory 'build.log'
$mavenArgs = @('-B', '-ntp', "-Dvio.build.directory=$buildDirectory", "-Dmdep.outputFile=$classpathFile", '-DincludeScope=runtime', '-DskipTests')
if ($Offline) { $mavenArgs += '-o' }
$mavenArgs += @('compile', 'dependency:build-classpath')
Push-Location $projectRoot
try {
    $nativeErrorPreference = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; & (Join-Path $projectRoot 'mvnw.cmd') @mavenArgs *> $buildLog; $buildExitCode = $LASTEXITCODE }
    finally { $ErrorActionPreference = $nativeErrorPreference }
    if ($buildExitCode -ne 0) { throw "MCP build failed. See $buildLog" }
    $classpath = (Join-Path $buildDirectory 'classes') + [IO.Path]::PathSeparator + ([IO.File]::ReadAllText($classpathFile)).Trim()
    $configuration = @{ mcpServers = @{ 'vio-pokemon' = @{
        command = $javaExecutable
        args = @('-cp', $classpath, 'com.vio.vioaiagent.mcp.PokemonMcpServerMain')
        env = @{ VIO_MCP_PROJECT_ROOT = $projectRoot; VIO_MCP_CACHE_DIRECTORY = (Join-Path $projectRoot '.local-data/pokeapi') }
    } } }
    $configFile = Join-Path $projectRoot '.local-data/mcp-client-config.json'
    [IO.File]::WriteAllText($configFile, ($configuration | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "MCP client configuration: $configFile"
    Write-Output 'No model Key is required. Import this configuration in a compatible local STDIO MCP client.'
} finally { Pop-Location }
