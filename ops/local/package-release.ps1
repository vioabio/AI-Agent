[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackendJar,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+$')][string]$ReleaseVersion = 'v1.1.0'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$artifactRoot = (Resolve-Path (Join-Path $projectRoot 'artifacts')).Path
$outputPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
if (-not $outputPath.StartsWith($artifactRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a new distribution directory under artifacts.'
}
Push-Location $projectRoot
try {
    & git diff --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Commit tracked changes before creating a release.' }
    & git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Commit staged changes before creating a release.' }
    $sourceCommit = (& git rev-parse HEAD).Trim()
    $releaseNote = Join-Path $projectRoot "document/发布记录/$ReleaseVersion.md"
    if (-not (Test-Path -LiteralPath $releaseNote)) { throw 'Add the versioned release record first.' }

    # Reuse private-JAR exclusion, bounded output paths and the established file copier.
    & (Join-Path $PSScriptRoot 'package-local-candidate.ps1') -BackendJar $BackendJar -OutputDirectory $OutputDirectory
    $reviewReport = $outputPath + '.candidate-verification.json'
    & node (Join-Path $projectRoot 'tools/campus/local-candidate-verify.mjs') $outputPath $reviewReport
    if ($LASTEXITCODE -ne 0) { throw 'Distribution staging integrity check failed.' }
    if ((Get-Item -LiteralPath (Join-Path $outputPath 'source/changes.patch')).Length -ne 0) {
        throw 'A release must identify a committed source tree.'
    }
    if (Test-Path -LiteralPath (Join-Path $outputPath 'source/new-files')) {
        throw 'A release cannot include uncommitted source additions.'
    }
    Remove-Item -LiteralPath (Join-Path $outputPath 'CANDIDATE.md')
    $publishedBase = "https://github.com/vioabio/AI-Agent/blob/$ReleaseVersion/"
    $noteText = [IO.File]::ReadAllText($releaseNote).Replace('](../../', "]($publishedBase").Replace('](../', "](${publishedBase}document/")
    [IO.File]::WriteAllText((Join-Path $outputPath 'RELEASE.md'), $noteText, [Text.UTF8Encoding]::new($false))
    $notice = @"
# $ReleaseVersion 运行包

源码提交为 $sourceCommit。后端为 app.jar，前端为 frontend-dist，本地伤害引擎及锁定依赖位于 tools/pokemon-engine。

运行需要 JDK 21 或更新版本以及 Node.js。通过项目 README 配置模型与可选服务，使用同源代理托管前端并转发 /api。个人数据库与凭证由使用者在本机配置。请从 Git 标签或上述提交取得完整源码和部署模板。

发布授权来自用户本次明确发布请求。回答质量按发布记录中的 AI 辅助复核范围登记，人工领域审核状态单独记录。
"@
    [IO.File]::WriteAllText((Join-Path $outputPath 'RUNNING.md'), $notice, [Text.UTF8Encoding]::new($false))
    function Get-Inventory {
        @(Get-ChildItem -LiteralPath $outputPath -Recurse -File | Where-Object FullName -ne (Join-Path $outputPath 'manifest.json') | Sort-Object FullName | ForEach-Object {
            $relativePath = [IO.Path]::GetRelativePath($outputPath, $_.FullName).Replace('\','/')
            if ($relativePath -match '(^|/)(\.run|\.idea|\.local-data)(/|$)|(^|/)\.env(\.|$)|(^|/)application-local\.(yml|yaml|properties)(\.|$)') {
                throw 'Private configuration or runtime data found in distribution.'
            }
            [ordered]@{ path=$relativePath; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        })
    }
    $files = Get-Inventory
    $checksumText = ($files | ForEach-Object { "$($_.sha256)  $($_.path)" }) -join "`n"
    [IO.File]::WriteAllText((Join-Path $outputPath 'SHA256SUMS'), $checksumText + "`n", [Text.UTF8Encoding]::new($false))
    $files = Get-Inventory
    $manifest = [ordered]@{
        schemaVersion='vio-local-release-v1'; version=$ReleaseVersion; status='RELEASED';
        repository='https://github.com/vioabio/AI-Agent.git'; sourceCommit=$sourceCommit; sourceState='COMMITTED';
        backendProjectVersion='0.0.1-SNAPSHOT'; releaseAuthorization='USER_REQUEST'; qualityReview='AI_ASSISTED';
        createdAtUtc=[DateTime]::UtcNow.ToString('o'); files=$files
    }
    [IO.File]::WriteAllText((Join-Path $outputPath 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $actual = Get-Inventory
    if (($actual | ConvertTo-Json -Depth 8 -Compress) -ne ($files | ConvertTo-Json -Depth 8 -Compress)) {
        throw 'Distribution changed after manifest creation.'
    }
    # This archive was created in this invocation by the staging packager.
    Remove-Item -LiteralPath ($outputPath + '.zip')
    Compress-Archive -LiteralPath $outputPath -DestinationPath ($outputPath + '.zip') -CompressionLevel Optimal
    $archiveHash = (Get-FileHash -LiteralPath ($outputPath + '.zip') -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($outputPath + '.zip.sha256'), $archiveHash + '  ' + [IO.Path]::GetFileName($outputPath + '.zip') + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "$ReleaseVersion distribution prepared with $($files.Count) checksummed files from $sourceCommit."
} finally { Pop-Location }
