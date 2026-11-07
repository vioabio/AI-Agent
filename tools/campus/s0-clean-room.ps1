param([switch]$FreshMavenRepository, [switch]$WarmReferenceData)
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$snapshotRoot = Join-Path $sourceRoot ('tmp/s0-clean-' + [Guid]::NewGuid().ToString('N'))
$reportRoot = Join-Path $sourceRoot ('artifacts/campus/s0/clean-room-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($reportRoot) | Out-Null
[IO.Directory]::CreateDirectory($snapshotRoot) | Out-Null
$files = @(git -C $sourceRoot -c core.quotepath=false ls-files -c -o --exclude-standard | Select-Object -Unique)
if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed' }
# No .git metadata, ignored local secrets, existing databases, node_modules or target are copied.
$inventory = foreach ($relative in $files) {
    $source = Join-Path $sourceRoot $relative
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    $destination = Join-Path $snapshotRoot $relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
    @{ path = $relative; sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$inventory | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $reportRoot 'source-inventory.json') -Encoding utf8
$metadata = @{ snapshotPath = $snapshotRoot; sourceCommit = (git -C $sourceRoot rev-parse HEAD); freshMavenRepository = [bool]$FreshMavenRepository; officialReferenceBootstrap = [bool]$WarmReferenceData; npmCache = 'fresh snapshot-local cache'; modelMode = 'startup-only fixture key; no model requests'; startedAt = [DateTime]::UtcNow.ToString('o') }
$metadata | ConvertTo-Json | Set-Content (Join-Path $reportRoot 'environment.json') -Encoding utf8
$oldKey = $env:DEEPSEEK_API_KEY
$oldSearch = $env:VIO_TOOLS_SEARCH_ENABLED
$oldMcp = $env:VIO_MCP_ENABLED
$backend = $null
$frontend = $null
try {
    Push-Location $snapshotRoot
    Copy-Item src/main/resources/application-local.example.yml src/main/resources/application-local.yml
    if ($WarmReferenceData) {
        & (Join-Path $sourceRoot 'tools/warm-pokemon-reference.ps1') -Query squirtle -CachePath (Join-Path $snapshotRoot '.local-data/pokeapi') *> (Join-Path $reportRoot 'official-squirtle-bootstrap.log')
        & (Join-Path $sourceRoot 'tools/warm-pokemon-reference.ps1') -Query charmander -CachePath (Join-Path $snapshotRoot '.local-data/pokeapi') *> (Join-Path $reportRoot 'official-charmander-bootstrap.log')
    }
    & npm --prefix tools/pokemon-engine ci --cache (Join-Path $snapshotRoot '.npm-cache') *> (Join-Path $reportRoot 'npm-engine.log')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh engine npm ci failed' }
    & npm --prefix vio-ai-agent-frontend ci --cache (Join-Path $snapshotRoot '.npm-cache') *> (Join-Path $reportRoot 'npm-frontend.log')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh frontend npm ci failed' }
    & npm --prefix vio-ai-agent-frontend run build *> (Join-Path $reportRoot 'frontend-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh frontend build failed' }
    if (Get-NetTCPConnection -State Listen -LocalPort 13000 -ErrorAction SilentlyContinue) { throw 'Port 13000 is occupied' }
    $viteScript = Join-Path $snapshotRoot 'vio-ai-agent-frontend/node_modules/vite/bin/vite.js'
    $frontend = Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"' + $viteScript + '"'), '--host', '127.0.0.1', '--port', '13000', '--strictPort') -WorkingDirectory (Join-Path $snapshotRoot 'vio-ai-agent-frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reportRoot 'frontend-stdout.log') -RedirectStandardError (Join-Path $reportRoot 'frontend-stderr.log')
    $frontendReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if ($frontend.HasExited) { throw 'Isolated Vite exited' }
        try { $page = Invoke-WebRequest 'http://127.0.0.1:13000/game-master' -TimeoutSec 2; if ($page.StatusCode -eq 200) { $frontendReady = $true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    if (!$frontendReady) { throw 'Isolated frontend did not become ready' }
    $metadata.frontendHttpStatus = 200
    $mavenArgs = @('-DskipTests', 'package')
    if ($FreshMavenRepository) { $mavenArgs += "-Dmaven.repo.local=$snapshotRoot/.m2" }
    & .\mvnw.cmd @mavenArgs *> (Join-Path $reportRoot 'backend-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh backend package failed' }
    $jar = Join-Path $snapshotRoot 'target/vio-ai-agent-0.0.1-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Backend package did not produce the executable jar' }
    $env:DEEPSEEK_API_KEY = 's0-startup-only-not-a-real-provider-key'
    $env:VIO_TOOLS_SEARCH_ENABLED = 'false'
    $env:VIO_MCP_ENABLED = 'false'
    if (Get-NetTCPConnection -State Listen -LocalPort 18123 -ErrorAction SilentlyContinue) { throw 'Port 18123 is occupied; do not test an unrelated service' }
    $backend = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList @('-jar', ('"' + $jar + '"'), '--spring.profiles.active=local,deepseek', '--server.port=18123') -WorkingDirectory $snapshotRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reportRoot 'backend-stdout.log') -RedirectStandardError (Join-Path $reportRoot 'backend-stderr.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($backend.HasExited) { throw 'Isolated backend exited before readiness' }
        try { $status = Invoke-RestMethod 'http://127.0.0.1:18123/api/auth/session' -TimeoutSec 2; if ($status.mode -eq 'local') { $ready = $true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'Isolated backend did not become ready' }
    & node (Join-Path $sourceRoot 'tools/campus/s0-baseline.mjs') --base-url http://127.0.0.1:18123/api --output $reportRoot --runtime-source isolated-source-copy --model-id not-called
    if ($LASTEXITCODE -ne 0) { throw 'Isolated HTTP baseline failed; inspect report.json' }
    $metadata.status = 'PASSED'
} catch {
    $metadata.status = 'FAILED'
    $metadata.error = $_.Exception.Message
    throw
} finally {
    if ($backend -and !$backend.HasExited) { Stop-Process -Id $backend.Id }
    if ($frontend -and !$frontend.HasExited) { Stop-Process -Id $frontend.Id }
    Pop-Location
    $env:DEEPSEEK_API_KEY = $oldKey
    $env:VIO_TOOLS_SEARCH_ENABLED = $oldSearch
    $env:VIO_MCP_ENABLED = $oldMcp
    $metadata.finishedAt = [DateTime]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json | Set-Content (Join-Path $reportRoot 'environment.json') -Encoding utf8
    Write-Output "S0 isolated evidence: $reportRoot"
}
