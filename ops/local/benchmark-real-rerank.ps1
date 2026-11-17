[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BuildDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$VectorCacheFile
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$artifactRoot = (Resolve-Path (Join-Path $projectRoot 'artifacts')).Path
$buildPath = (Resolve-Path (Join-Path $projectRoot $BuildDirectory)).Path
$outputPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$vectorPath = (Resolve-Path (Join-Path $projectRoot $VectorCacheFile)).Path
foreach ($path in @($buildPath,$outputPath,$vectorPath)) {
    if (-not $path.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Use isolated build, output and vector backup paths within artifacts.'
    }
}
if (Test-Path -LiteralPath $outputPath) { throw 'Use a new output directory.' }
if ((Test-Path -LiteralPath ($vectorPath + '-wal')) -or (Test-Path -LiteralPath ($vectorPath + '-shm'))) {
    throw 'Prepare a consistent public guide vector backup first.'
}
$reportFile = Get-ChildItem -LiteralPath (Join-Path $buildPath 'surefire-reports') -Filter 'TEST-*.xml' | Select-Object -First 1
[xml]$testReport = Get-Content -LiteralPath $reportFile.FullName -Raw
$testClasspath = ($testReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$dependencies = @($testClasspath -split [IO.Path]::PathSeparator | Where-Object { $_.EndsWith('.jar') })
if (-not $dependencies.Count) { throw 'Run the isolated backend tests before this benchmark.' }
$classpath = (@((Join-Path $buildPath 'classes')) + $dependencies) -join [IO.Path]::PathSeparator
[xml]$idea = Get-Content -LiteralPath (Join-Path $projectRoot '.run/VioAiAgentApplication.run.xml') -Raw
$names = @('VIO_GUIDE_EMBEDDING_ENDPOINT','VIO_GUIDE_EMBEDDING_API_KEY','VIO_GUIDE_EMBEDDING_MODEL','VIO_GUIDE_EMBEDDING_DIMENSIONS',
    'VIO_GUIDE_EMBEDDING_TIMEOUT_SECONDS','VIO_GUIDE_RERANK_ENDPOINT','VIO_GUIDE_RERANK_MODEL','VIO_GUIDE_RERANK_API_KEY','VIO_GUIDE_RERANK_TIMEOUT_MS')
$previous = @{}
$java = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr\bin\java.exe'
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
}
if (-not (Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to an approved JDK 21 or newer.' }
Push-Location $projectRoot
try {
    foreach ($name in $names) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $previous[$name]
        if ([string]::IsNullOrWhiteSpace($value)) {
            $entry = @($idea.SelectNodes('//envs/env') | Where-Object { $_.GetAttribute('name') -eq $name })
            if ($entry.Count -eq 1) { $value = $entry[0].GetAttribute('value') }
        }
        if ([string]::IsNullOrWhiteSpace($value) -and $name -eq 'VIO_GUIDE_RERANK_TIMEOUT_MS') { $value = '3000' }
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Configure $name locally." }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    $nativePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $java '-Djava.net.useSystemProxies=true' -cp $classpath tools/campus/LiveGuideRerankBenchmark.java $outputPath $vectorPath
        $collectionExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $nativePreference }
    if ($collectionExitCode -ne 0) { throw 'Real retrieval collection failed. Inspect the safe local report.' }
    & node tools/campus/real-rerank-compare.mjs (Join-Path $outputPath 'report.json') (Join-Path $outputPath 'comparison.json')
    if ($LASTEXITCODE -ne 0) { throw 'Real retrieval comparison did not pass evidence validation.' }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
