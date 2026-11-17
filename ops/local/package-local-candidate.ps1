[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackendJar,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$artifactRoot = (Resolve-Path (Join-Path $projectRoot 'artifacts')).Path
$outputPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$jarPath = (Resolve-Path (Join-Path $projectRoot $BackendJar)).Path
if (-not $outputPath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) `
    -or -not $jarPath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use isolated build and candidate paths under artifacts.'
}
if ((Test-Path -LiteralPath $outputPath) -or (Test-Path -LiteralPath ($outputPath + '.zip'))) { throw 'Use a new candidate directory.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    if (@($jar.Entries | Where-Object { $_.FullName -match '(^|/)application-local\.(yml|yaml|properties)(\.|$)|(^|/)\.env(\.|$)' }).Count) {
        throw 'Backend JAR contains private configuration. Rebuild with resource exclusions before packaging.'
    }
} finally { $jar.Dispose() }
Push-Location $projectRoot
try {
    $null = New-Item -ItemType Directory -Path $outputPath
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $outputPath 'app.jar')
    Copy-Item -LiteralPath 'vio-ai-agent-frontend/dist' -Destination (Join-Path $outputPath 'frontend-dist') -Recurse
    $engine = New-Item -ItemType Directory -Path (Join-Path $outputPath 'tools/pokemon-engine')
    foreach ($file in Get-ChildItem -LiteralPath tools/pokemon-engine -File) {
        if ($file.Name -notlike '*.test.cjs') { Copy-Item -LiteralPath $file.FullName -Destination $engine.FullName }
    }
    Copy-Item -LiteralPath tools/pokemon-engine/node_modules -Destination (Join-Path $engine.FullName 'node_modules') -Recurse
    $sourcePath = New-Item -ItemType Directory -Path (Join-Path $outputPath 'source')
    & git diff --binary HEAD "--output=$($sourcePath.FullName)/changes.patch"
    if ($LASTEXITCODE -ne 0) { throw 'Source diff capture failed.' }
    $baseCommit = (& git rev-parse HEAD).Trim()
    $newFiles = @(& git -c core.quotepath=false ls-files --others --exclude-standard)
    foreach ($relativePath in $newFiles) {
        if ($relativePath -like '* - 副本.md' -or $relativePath -notmatch '^(src/|tools/|ops/|document/|evaluation/)') { continue }
        $destination = Join-Path $sourcePath.FullName ('new-files/' + $relativePath)
        $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
        Copy-Item -LiteralPath $relativePath -Destination $destination
    }
    $notice = @'
# VIO v1.1.0-rc.1 本地候选

本包包含可执行后端app.jar、已构建frontend-dist、本地伤害引擎及锁定依赖、源码差异和校验清单。

源码恢复请在独立检出目录切换到manifest.json中的baseCommit，执行git apply source/changes.patch，再合并source/new-files下的新增文件。恢复后按项目README在本机配置模型凭证并运行。

后端需JDK21或更新版本，伤害引擎需Node.js。frontend-dist需由既有前端服务或同源代理提供，API路径为/api。候选运行的个人数据使用自行配置的目录。

产品候选标识为v1.1.0-rc.1-local，Maven构建版本为0.0.1-SNAPSHOT，详见manifest.json。该包供本地审核，正式发布状态为待审核。
'@
    [IO.File]::WriteAllText((Join-Path $outputPath 'CANDIDATE.md'),$notice,[Text.UTF8Encoding]::new($false))
    $inventory = @(Get-ChildItem -LiteralPath $outputPath -Recurse -File | Sort-Object FullName | ForEach-Object {
        [ordered]@{ path=[IO.Path]::GetRelativePath($outputPath,$_.FullName).Replace('\','/'); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $manifest = [ordered]@{schemaVersion='vio-local-candidate-v1';candidate='v1.1.0-rc.1-local';status='READY_FOR_LOCAL_REVIEW';
        repository='https://github.com/vioabio/AI-Agent.git';baseCommit=$baseCommit;sourceState='WORKTREE_PATCH_AND_ADDITIONS';
        backendProjectVersion='0.0.1-SNAPSHOT';humanApproval='PENDING';files=$inventory}
    [IO.File]::WriteAllText((Join-Path $outputPath 'manifest.json'),($manifest | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Compress-Archive -LiteralPath $outputPath -DestinationPath ($outputPath + '.zip') -CompressionLevel Optimal
    $hash = (Get-FileHash -LiteralPath ($outputPath + '.zip') -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($outputPath + '.zip.sha256'),$hash + '  ' + [IO.Path]::GetFileName($outputPath + '.zip') + "`n",[Text.UTF8Encoding]::new($false))
    Write-Output "Local candidate prepared with $($inventory.Count) checksummed files."
} finally { Pop-Location }
