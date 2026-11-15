[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BuildDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateRange(1024,65535)][int]$Port = 18124,
    [switch]$Skills,
    [switch]$ContextAssembly,
    [switch]$AnswerBlocks,
    [ValidateRange(1024,65535)][int]$FrontendPort = 3000,
    [switch]$DisableDomainTracing,
    [ValidateRange(0.0,1.0)][double]$TracingSampleRate = 1.0,
    [string]$ReferenceCacheDirectory,
    [ValidateSet('None','EngineUnavailable','LowBudget')][string]$TestFault = 'None',
    [switch]$SemanticSearch,
    [string]$VectorCacheFile,
    [string]$GuideCorpusFile,
    [string]$PersistentDataDirectory,
    [switch]$SemanticFixture,
    [switch]$Rerank,
    [switch]$RerankFixture,
    [ValidateSet('stage5-fixture-v1','stage5-fixture-v2')][string]$SemanticFixtureModel = 'stage5-fixture-v1'
)
$ErrorActionPreference = 'Stop'
if ($RerankFixture -and -not $Rerank) { throw 'Use -Rerank with the controlled rerank fixture.' }
function Write-ProcessMetadata {
    param([string]$Path, [object]$Value)
    $temporaryPath = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temporaryPath, ($Value | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            try {
                [IO.File]::Move($temporaryPath, $Path, $true)
                return
            } catch [IO.IOException], [UnauthorizedAccessException] {
                if ($attempt -eq 39) { throw }
                Start-Sleep -Milliseconds 25
            }
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath }
    }
}
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$artifactRoot = (Resolve-Path (Join-Path $projectRoot 'artifacts')).Path
$buildPath = (Resolve-Path (Join-Path $projectRoot $BuildDirectory)).Path
$outputPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
foreach ($path in @($buildPath, $outputPath)) {
    if (-not $path.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use a build and new output directory within artifacts.' }
}
if ($Port -eq 8123 -or @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Choose a free isolated port.' }
if ($SemanticFixture -and -not $SemanticSearch) { throw 'SemanticFixture requires SemanticSearch.' }
if ($VectorCacheFile) {
    $vectorSource = (Resolve-Path (Join-Path $projectRoot $VectorCacheFile)).Path
    $vectorFile = Get-Item -LiteralPath $vectorSource
    if (-not $SemanticSearch -or -not $vectorSource.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $vectorFile.Extension -ne '.sqlite' -or $vectorFile.Length -gt 16000000) { throw 'Use a bounded isolated SQLite vector backup with SemanticSearch.' }
    if ((Test-Path -LiteralPath ($vectorSource + '-wal')) -or (Test-Path -LiteralPath ($vectorSource + '-shm'))) { throw 'Prepare a consistent SQLite backup before seeding vectors.' }
}
if (Test-Path -LiteralPath $outputPath) { throw 'Output directory already exists.' }
$statePath = $outputPath
if ($PersistentDataDirectory) {
    $statePath = (Resolve-Path (Join-Path $projectRoot $PersistentDataDirectory)).Path
    if (-not $statePath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath (Join-Path $statePath 'process.json'))) { throw 'Reuse only a named isolated backend data directory under artifacts.' }
    if ($VectorCacheFile -or $ReferenceCacheDirectory -or $GuideCorpusFile -or $SemanticFixture -or $TestFault -ne 'None') { throw 'State reuse must preserve its existing caches and default corpus.' }
}
$guideCorpusPath = $null
if ($GuideCorpusFile) {
    $guideCorpusPath = (Resolve-Path (Join-Path $projectRoot $GuideCorpusFile)).Path
    $guideCorpus = Get-Item -LiteralPath $guideCorpusPath
    if (-not $guideCorpusPath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $guideCorpus.Extension -ne '.json' -or $guideCorpus.Length -gt 2000000) { throw 'Use a bounded isolated JSON corpus under artifacts.' }
    $corpus = [IO.File]::ReadAllText($guideCorpusPath) | ConvertFrom-Json
    if ($corpus.schemaVersion -ne 3 -or $corpus.documents.Count -ne 14) { throw 'Use the isolated 14-document overview corpus.' }
}
$cacheFiles = @()
if ($ReferenceCacheDirectory) {
    $cachePath = (Resolve-Path (Join-Path $projectRoot $ReferenceCacheDirectory)).Path
    if (-not $cachePath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Reference cache must be within artifacts.' }
    $cacheFiles = @(Get-ChildItem -LiteralPath $cachePath -File -Filter '*.json' | Sort-Object Name)
    if ($cacheFiles.Count -eq 0 -or $cacheFiles.Count -gt 10000) { throw 'Use a bounded public reference cache.' }
    foreach ($file in $cacheFiles) {
        if ($file.Length -gt 2000000 -or $file.Name -notmatch '^(pokemon|pokemon-species|ability|move|item|nature)-[a-z0-9-]+\.json$') { throw 'Unexpected reference cache file.' }
        $entry = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $payloadHash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($entry.payload))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        if ($entry.schemaVersion -ne 1 -or $entry.url -notmatch '^https://pokeapi\.co/api/v2/(pokemon|pokemon-species|ability|move|item|nature)/[a-z0-9-]+/$' -or $entry.sha256 -ne "sha256:$payloadHash") { throw 'Public reference cache failed integrity validation.' }
    }
}
$classes = Join-Path $buildPath 'classes'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'com/vio/vioaiagent/VioAiAgentApplication.class'))) { throw 'Compile the isolated backend first.' }
$reportFile = Get-ChildItem (Join-Path $buildPath 'surefire-reports') -Filter 'TEST-*.xml' | Select-Object -First 1
if (-not $reportFile) { throw 'Use an isolated build with completed test reports.' }
[xml]$testReport = [IO.File]::ReadAllText($reportFile.FullName)
$testClassPath = ($testReport.testsuite.properties.property | Where-Object name -eq 'surefire.test.class.path').value
$dependencies = @($testClassPath -split [IO.Path]::PathSeparator | Where-Object { $_.EndsWith('.jar') })
if ($dependencies.Count -eq 0) { throw 'Runtime dependency classpath is unavailable.' }
$classpath = (@($classes) + $dependencies) -join [IO.Path]::PathSeparator
$java = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr\bin\java.exe'
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
if (-not (Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to an available JDK.' }
[xml]$ideaConfig = [IO.File]::ReadAllText((Join-Path $projectRoot '.run/VioAiAgentApplication.run.xml'))
$key = @($ideaConfig.SelectNodes('//envs/env') | Where-Object { $_.GetAttribute('name') -eq 'DEEPSEEK_API_KEY' })
if ($key.Count -ne 1 -or $key[0].GetAttribute('value').Length -lt 10) { throw 'Configure DEEPSEEK_API_KEY in the local IDEA run configuration.' }
New-Item -ItemType Directory -Path $outputPath | Out-Null
if ($VectorCacheFile) { Copy-Item -LiteralPath $vectorSource -Destination (Join-Path $outputPath 'vectors.sqlite') }
if ($cacheFiles.Count -gt 0) {
    $targetCache = New-Item -ItemType Directory -Path (Join-Path $outputPath 'pokeapi')
    $cacheManifest = foreach ($file in $cacheFiles) {
        Copy-Item -LiteralPath $file.FullName -Destination $targetCache.FullName
        [ordered]@{ file=$file.Name; sha256=(Get-FileHash -LiteralPath (Join-Path $targetCache.FullName $file.Name) -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    ConvertTo-Json -InputObject @($cacheManifest) | Set-Content (Join-Path $outputPath 'reference-cache-manifest.json') -Encoding UTF8
}
$previousKey = $env:DEEPSEEK_API_KEY
$previousProfiles = $env:SPRING_PROFILES_ACTIVE
$previousEmbedding = @{}
$previousRerank = @{}
$stateLock = $null
try {
    $stateLock = [IO.File]::Open((Join-Path $statePath 'startup.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    if ($PersistentDataDirectory) {
        $markerFile = Join-Path $statePath 'state-process.json'
        if (-not (Test-Path -LiteralPath $markerFile)) { $markerFile = Join-Path $statePath 'process.json' }
        $marker = [IO.File]::ReadAllText($markerFile) | ConvertFrom-Json
        $prior = Get-Process -Id $marker.pid -ErrorAction SilentlyContinue
        if ($marker.startedAt -is [DateTime]) { $priorStart = [DateTimeOffset]$marker.startedAt }
        else { $priorStart = [DateTimeOffset]::Parse($marker.startedAt, [Globalization.CultureInfo]::InvariantCulture) }
        if ($prior -and $prior.StartTime.ToUniversalTime().Ticks -eq $priorStart.UtcDateTime.Ticks) { throw 'Stop the named isolated backend before reopening its state.' }
        foreach ($name in @('accounts.sqlite','history.sqlite','teams.sqlite','guides.sqlite')) {
            $database = Get-Item -LiteralPath (Join-Path $statePath $name)
            if ($database.Length -gt 64000000) { throw 'Use bounded isolated test databases.' }
        }
    }
    $env:DEEPSEEK_API_KEY = $key[0].GetAttribute('value')
    $env:SPRING_PROFILES_ACTIVE = 'local,deepseek'
    if ($SemanticSearch) {
        $fixtureEmbedding = @{'VIO_GUIDE_EMBEDDING_API_KEY'='controlled-test-only'; 'VIO_GUIDE_EMBEDDING_ENDPOINT'='http://127.0.0.1:4333/embeddings'; 'VIO_GUIDE_EMBEDDING_MODEL'=$SemanticFixtureModel; 'VIO_GUIDE_EMBEDDING_DIMENSIONS'='2'; 'VIO_GUIDE_EMBEDDING_TIMEOUT_SECONDS'='2'}
        foreach ($name in @('VIO_GUIDE_EMBEDDING_API_KEY','VIO_GUIDE_EMBEDDING_ENDPOINT','VIO_GUIDE_EMBEDDING_MODEL','VIO_GUIDE_EMBEDDING_DIMENSIONS','VIO_GUIDE_EMBEDDING_TIMEOUT_SECONDS')) {
            if ($SemanticFixture) { $embeddingValue = $fixtureEmbedding[$name] }
            else {
                $entry = @($ideaConfig.SelectNodes('//envs/env') | Where-Object { $_.GetAttribute('name') -eq $name })
                if ($entry.Count -ne 1 -or [string]::IsNullOrWhiteSpace($entry[0].GetAttribute('value'))) { throw "Configure $name in the local IDEA run configuration." }
                $embeddingValue = $entry[0].GetAttribute('value')
            }
            $previousEmbedding[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $embeddingValue, 'Process')
        }
    }
    if ($Rerank) {
        $fixtureRerank = @{'VIO_GUIDE_RERANK_ENDPOINT'='http://127.0.0.1:4334/rerank'; 'VIO_GUIDE_RERANK_MODEL'='controlled-rerank-v1'; 'VIO_GUIDE_RERANK_API_KEY'='controlled-test-only'; 'VIO_GUIDE_RERANK_TIMEOUT_MS'='500'}
        foreach ($name in @('VIO_GUIDE_RERANK_ENDPOINT','VIO_GUIDE_RERANK_MODEL','VIO_GUIDE_RERANK_API_KEY','VIO_GUIDE_RERANK_TIMEOUT_MS')) {
            $previousRerank[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            $value = $previousRerank[$name]
            if ($RerankFixture) { $value = $fixtureRerank[$name] }
            elseif ([string]::IsNullOrWhiteSpace($value)) {
                $entry = @($ideaConfig.SelectNodes('//envs/env') | Where-Object { $_.GetAttribute('name') -eq $name })
                if ($entry.Count -eq 1) { $value = $entry[0].GetAttribute('value') }
            }
            if ([string]::IsNullOrWhiteSpace($value)) {
                if ($name -eq 'VIO_GUIDE_RERANK_TIMEOUT_MS') { $value = '3000' }
                else { throw "Configure $name in the process or local IDEA run configuration." }
            }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
    $arguments = @('-cp', ('"' + $classpath + '"'), 'com.vio.vioaiagent.VioAiAgentApplication',
        '--server.address=127.0.0.1', "--server.port=$Port", '--server.servlet.context-path=/api',
        '--vio.http.cors.allowed-origins[0]=http://localhost:3000',
        '--vio.http.cors.allowed-origins[1]=http://127.0.0.1:3000',
        "--vio.http.cors.allowed-origins[2]=http://127.0.0.1:$FrontendPort",
        '--spring.profiles.active=local,deepseek', '--vio.model.provider=deepseek',
        '--vio.local-accounts.enabled=true', '--vio.local-history.enabled=true',
        "--vio.local-accounts.path=$statePath/accounts.sqlite", "--vio.local-history.path=$statePath/history.sqlite",
        "--vio.local-teams.path=$statePath/teams.sqlite", "--vio.local-guides.path=$statePath/guides.sqlite",
        "--vio.pokemon.reference.path=$statePath/pokeapi", "--vio.local-guides.semantic.path=$statePath/vectors.sqlite",
        '--vio.agent.specialists.enabled=true', "--vio.agent.skills.enabled=$($Skills.IsPresent.ToString().ToLowerInvariant())",
        "--vio.agent.context-assembly.enabled=$($ContextAssembly.IsPresent.ToString().ToLowerInvariant())",
        "--vio.agent.answer-blocks.enabled=$($AnswerBlocks.IsPresent.ToString().ToLowerInvariant())",
        "--vio.local-guides.semantic.enabled=$($SemanticSearch.IsPresent.ToString().ToLowerInvariant())", "--vio.local-guides.rerank.enabled=$($Rerank.IsPresent.ToString().ToLowerInvariant())",
        "--vio.observability.tracing.enabled=$((-not $DisableDomainTracing.IsPresent).ToString().ToLowerInvariant())",
        "--vio.observability.tracing.sample-rate=$($TracingSampleRate.ToString([Globalization.CultureInfo]::InvariantCulture))",
        '--vio.observability.tracing.endpoint=http://127.0.0.1:4318/v1/traces', '--management.tracing.enabled=false',
        '--vio.agent.guide-research.enabled=false', '--spring.ai.mcp.client.enabled=false')
    if ($guideCorpusPath) {
        $corpusUri = ([Uri]$guideCorpusPath).AbsoluteUri
        $arguments += ('"--vio.local-guides.location=' + $corpusUri + '"')
    }
    if ($TestFault -eq 'EngineUnavailable') {
        $missingNode = Join-Path $outputPath 'unavailable-node.exe'
        if (Test-Path -LiteralPath $missingNode) { throw 'Controlled missing executable must not exist.' }
        $arguments += ('"--vio.damage-engine.node=' + $missingNode + '"')
    }
    if ($TestFault -eq 'LowBudget') {
        $arguments += @('--vio.run.task-budget.max-model-calls=2', '--vio.run.task-budget.token-limit=32768')
    }
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputPath 'backend.log') -RedirectStandardError (Join-Path $outputPath 'backend-error.log')
    $metadata = [ordered]@{ pid=$process.Id; startedAt=$process.StartTime.ToUniversalTime().ToString('o'); port=$Port; skills=$Skills.IsPresent; contextAssembly=$ContextAssembly.IsPresent; answerBlocks=$AnswerBlocks.IsPresent; domainTracing=(-not $DisableDomainTracing.IsPresent); tracingSampleRate=$TracingSampleRate; buildDirectory=$buildPath; outputDirectory=$outputPath; status='STARTING' }
    $metadata.testFault = $TestFault
    $metadata.semanticSearch = $SemanticSearch.IsPresent
    $metadata.vectorCacheSeeded = [bool]$VectorCacheFile
    $metadata.semanticFixture = $SemanticFixture.IsPresent
    $metadata.rerank = $Rerank.IsPresent
    $metadata.rerankFixture = $RerankFixture.IsPresent
    $metadata.isolatedGuideCorpus = [bool]$GuideCorpusFile
    $metadata.dataDirectory = $statePath
    $metadata.frontendPort = $FrontendPort
    Write-ProcessMetadata (Join-Path $outputPath 'process.json') $metadata
    Write-ProcessMetadata (Join-Path $statePath 'state-process.json') $metadata
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        $process.Refresh()
        if ($process.HasExited) { break }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/auth/session" -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $metadata.status = 'READY'; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if ($metadata.status -ne 'READY') {
        $metadata.status = 'START_FAILED'
        Write-ProcessMetadata (Join-Path $outputPath 'process.json') $metadata
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $process.Id }
        throw 'Isolated backend did not become ready. Inspect its local logs.'
    }
    Write-ProcessMetadata (Join-Path $outputPath 'process.json') $metadata
    Write-Output "Isolated backend ready, PID $($process.Id), port $Port."
} finally {
    $env:DEEPSEEK_API_KEY = $previousKey
    $env:SPRING_PROFILES_ACTIVE = $previousProfiles
    foreach ($name in $previousEmbedding.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEmbedding[$name], 'Process') }
    foreach ($name in $previousRerank.Keys) { [Environment]::SetEnvironmentVariable($name, $previousRerank[$name], 'Process') }
    if ($stateLock) { $stateLock.Dispose() }
}
