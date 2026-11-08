[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,48}$')]
    [string]$ReleaseId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$SourceRef = 'HEAD',
    [switch]$ValidateOnly,
    [switch]$OfflineMaven
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Program (exit $LASTEXITCODE)" }
}

Push-Location $repoRoot
try {
    foreach ($command in @('git.exe', 'tar.exe', 'node.exe', 'npm.cmd')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing command: $command" }
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        throw 'Set JAVA_HOME to a JDK 21 directory first.'
    }
    # Java writes -version to stderr. Windows PowerShell 5 treats a redirected
    # native stderr line as an error when ErrorActionPreference is Stop.
    $javaStart = New-Object System.Diagnostics.ProcessStartInfo
    $javaStart.FileName = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $javaStart.Arguments = '-version'
    $javaStart.UseShellExecute = $false
    $javaStart.CreateNoWindow = $true
    $javaStart.RedirectStandardOutput = $true
    $javaStart.RedirectStandardError = $true
    $javaProcess = New-Object System.Diagnostics.Process
    $javaProcess.StartInfo = $javaStart
    try {
        if (-not $javaProcess.Start()) { throw 'Cannot start Java.' }
        $javaDescription = $javaProcess.StandardOutput.ReadToEnd() + $javaProcess.StandardError.ReadToEnd()
        $javaProcess.WaitForExit()
        if ($javaProcess.ExitCode -ne 0) { throw 'Cannot read Java version.' }
    } finally { $javaProcess.Dispose() }
    if ($javaDescription -notmatch 'version "21[.\"]') { throw 'This package requires JDK 21.' }
    $nodeVersion = (& node.exe --version).Trim()
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.(\d+)\.(\d+)$') { throw 'Cannot read Node.js version.' }
    $nodeMajor = [int]$Matches[1]; $nodeMinor = [int]$Matches[2]
    if (-not (($nodeMajor -eq 20 -and $nodeMinor -ge 19) -or ($nodeMajor -eq 22 -and $nodeMinor -ge 12) -or $nodeMajor -gt 22)) {
        throw 'Node.js does not meet the frontend engines requirement.'
    }
    $commit = (& git.exe rev-parse --verify --end-of-options "${SourceRef}^{commit}").Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve SourceRef to a commit.' }
    $sourcePaths = @('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src/main',
        'vio-ai-agent-frontend', 'tools/pokemon-engine', 'contracts',
        'evaluation/v1.1/manifest.json', 'evaluation/v1.1/metric-dictionary.json')
    $tracked = @(& git.exe ls-tree -r --name-only $commit -- @sourcePaths)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list committed package sources.' }
    foreach ($path in $tracked) {
        if ($path -match '(^|/)(\.run|\.idea|\.local-data|node_modules)(/|$)|(^|/)\.env($|\.)|(^|/)application-local\.(yml|yaml|properties)($|\.)|(^|/)mcp-servers\.json($|\.)') {
            throw "Private or generated path found in commit: $path"
        }
    }
    foreach ($template in @('application-public.yml.example', 'agent.env.example', 'vio-agent.service', 'nginx-http.conf.template', 'README.md')) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot "ops/aliyun/$template"))) { throw "Missing deployment template: $template" }
    }
    Write-Host "Source commit: $commit"
    Write-Host "Node: $nodeVersion; JDK: 21; tracked source files: $($tracked.Count)"
    Write-Host 'Only the selected committed source tree is built. Private workspace configuration is not copied.'
    if ($ValidateOnly) { return }

    $outputRoot = Join-Path $repoRoot "artifacts/aliyun/$ReleaseId"
    if (Test-Path -LiteralPath $outputRoot) { throw "Output already exists; select a new ReleaseId: $outputRoot" }
    $publicSource = Join-Path $outputRoot 'public-source'
    $bundle = Join-Path $outputRoot 'bundle'
    New-Item -ItemType Directory -Path $publicSource, $bundle -Force | Out-Null
    # .NET ZIP extraction preserves the repository's Chinese filenames on
    # Windows PowerShell 5; Windows bsdtar can reject those Git tar entries.
    $sourceZip = Join-Path $outputRoot 'source.zip'
    # Git archive otherwise applies the host's core.autocrlf setting. That
    # changes digest-pinned JSON fixtures and makes a clean JAR fail startup.
    Invoke-Checked 'git.exe' (@('-c', 'core.autocrlf=false', 'archive', '--format=zip', "--output=$sourceZip", $commit) + $sourcePaths)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($sourceZip, $publicSource)

    Push-Location $publicSource
    try {
        $mavenArgs = @('-B', '-ntp', '-DskipTests')
        if ($OfflineMaven) { $mavenArgs += '-o' }
        $mavenArgs += 'package'
        Invoke-Checked (Join-Path $publicSource 'mvnw.cmd') $mavenArgs
        Invoke-Checked 'npm.cmd' @('--prefix', (Join-Path $publicSource 'vio-ai-agent-frontend'), 'ci')
        Invoke-Checked 'npm.cmd' @('--prefix', (Join-Path $publicSource 'vio-ai-agent-frontend'), 'run', 'build')
    } finally { Pop-Location }

    $jars = @(Get-ChildItem -LiteralPath (Join-Path $publicSource 'target') -Filter '*.jar' -File)
    if ($jars.Count -ne 1) { throw "Expected one backend jar, found $($jars.Count)." }
    Copy-Item -LiteralPath $jars[0].FullName -Destination (Join-Path $bundle 'app.jar')
    Copy-Item -LiteralPath (Join-Path $publicSource 'vio-ai-agent-frontend/dist') -Destination (Join-Path $bundle 'frontend') -Recurse
    New-Item -ItemType Directory -Path (Join-Path $bundle 'tools') | Out-Null
    Copy-Item -LiteralPath (Join-Path $publicSource 'tools/pokemon-engine') -Destination (Join-Path $bundle 'tools/pokemon-engine') -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot 'ops/aliyun') -Destination (Join-Path $bundle 'deploy') -Recurse
    $manifest = [ordered]@{
        schemaVersion = 'aliyun-native-release-v1'
        releaseId = $ReleaseId
        sourceCommit = $commit
        builtAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        buildNode = $nodeVersion
        note = 'Application source is committed. Deployment templates come from the current workspace. No runtime data or credentials are included.'
    }
    [IO.File]::WriteAllText((Join-Path $bundle 'release.json'), ($manifest | ConvertTo-Json), $utf8)
    $checksumLines = @(Get-ChildItem -LiteralPath $bundle -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($bundle.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    })
    [IO.File]::WriteAllText((Join-Path $bundle 'SHA256SUMS'), (($checksumLines -join "`n") + "`n"), $utf8)
    $archive = Join-Path $outputRoot "vio-agent-$ReleaseId.tar.gz"
    Invoke-Checked 'tar.exe' @('-czf', $archive, '-C', $bundle, '.')
    $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$archive.sha256", "$archiveHash  $([IO.Path]::GetFileName($archive))`n", $utf8)
    Write-Host "Package: $archive"
    Write-Host "Checksum: $archive.sha256"
    Write-Host 'Build success is not a Linux/public deployment or answer-quality acceptance result.'
} finally { Pop-Location }
