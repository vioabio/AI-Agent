<#
.SYNOPSIS
  Project an explicitly captured N6b feedback bundle into immutable evidence files.

.DESCRIPTION
  This script is deliberately fail-closed.  It does not invent an answer, call a
  production API, or pretend that a feedback lifecycle was executed.  A caller
  must provide an input JSON bundle containing the records captured by an
  authorised submit/triage/review/correction/reindex/evaluation/re-answer run.
  The script validates the lineage fields, asks the repository validator to
  validate the exact projection, and only then atomically publishes the three
  files consumed by tools/validate-feedback-lineage.mjs.

  The output files are immutable evidence.  Existing files are rejected even
  when -NoClobber is omitted; use a new feedback run directory for a retry.
  Only a fixed, redacted field allow-list is projected, so arbitrary input
  properties (including accidental secrets) are never copied to evidence.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PublishedReleaseId,

    [Parameter(Mandatory = $true)]
    [string]$FeedbackRunId,

    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,

    # A JSON object following ops/feedback/README.md.  A directory containing
    # the three canonical files is also accepted for re-projection/audit use.
    [string]$InputBundle,

    # Kept in the CLI for parity with the runbook.  Evidence is always
    # immutable, so omission does not enable overwrite.
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$script:Committed = New-Object 'System.Collections.Generic.List[string]'
$script:TempDir = $null

function Invoke-N6bProjection {
    [void](Assert-Token $PublishedReleaseId 'PublishedReleaseId')
    [void](Assert-FeedbackRunId $FeedbackRunId)

    $evidencePath = Resolve-RepoPath $EvidenceDir 'EvidenceDir'
    if (Test-Path -LiteralPath $evidencePath -PathType Leaf) {
        throw 'EvidenceDir 不能是文件。'
    }

    if ([string]::IsNullOrWhiteSpace($InputBundle)) {
        throw '缺少 -InputBundle：未捕获的真实反馈闭环不能生成证据；脚本保持 BLOCKED。'
    }
    $bundlePath = Resolve-RepoPath $InputBundle 'InputBundle'
    if (-not (Test-Path -LiteralPath $bundlePath)) {
        throw 'InputBundle 不存在。'
    }

    $bundle = Read-Bundle $bundlePath
    Assert-BundleMetadata $bundle
    $parts = Get-BundleParts $bundle $bundlePath

    $feedbackRows = Convert-Rows $parts.Feedback 'feedback closure' $true 'CLOSED'
    $registryRows = Convert-Rows $parts.Registry 'answer release registry' $false 'PUBLISHED'
    $reanswerRows = Convert-Rows $parts.Reanswer 're-answer verification' $true $null
    Assert-ReanswerStatuses $reanswerRows
    Assert-SameAnswerSet $feedbackRows $registryRows $reanswerRows
    Assert-CrossFileLineage $feedbackRows $registryRows $reanswerRows

    $outputFeedback = Join-Path $evidencePath 'n6-feedback-closure.jsonl'
    $outputRegistry = Join-Path $evidencePath 'n6-answer-release-registry.json'
    $outputReanswer = Join-Path $evidencePath 'n6b-reanswer-verification.jsonl'
    $targets = @($outputFeedback, $outputRegistry, $outputReanswer)
    foreach ($target in $targets) {
        if (Test-Path -LiteralPath $target) {
            throw "证据文件已存在（不可覆盖）: $(Split-Path -Leaf $target)"
        }
    }

    # Build and validate in a private sibling directory first.  No final file
    # is visible until all three projections pass the Node validator.
    New-Item -ItemType Directory -Force -Path $evidencePath | Out-Null
    $script:TempDir = Join-Path $evidencePath ('.n6b-projection-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $script:TempDir | Out-Null
    $tempFeedback = Join-Path $script:TempDir 'n6-feedback-closure.jsonl'
    $tempRegistry = Join-Path $script:TempDir 'n6-answer-release-registry.json'
    $tempReanswer = Join-Path $script:TempDir 'n6b-reanswer-verification.jsonl'
    $tempReport = Join-Path $script:TempDir 'lineage-validation.json'

    Write-JsonLines $tempFeedback $feedbackRows
    Write-JsonLines $tempReanswer $reanswerRows
    $registryDocument = [ordered]@{
        schemaVersion = 'v1.1'
        producer = 'run-n6b-feedback-closure'
        projectionMode = 'input-bundle'
        feedbackRunId = $FeedbackRunId.Trim()
        publishedReleaseId = $PublishedReleaseId.Trim()
        entries = @($registryRows)
        redacted = $true
    }
    Write-JsonFile $tempRegistry $registryDocument

    Invoke-LineageValidator $tempFeedback $tempRegistry $tempReanswer $tempReport

    # File.Move(source, target) fails if another process created target after
    # the preflight check.  Thus concurrent runs cannot silently overwrite.
    Move-AtomicNoReplace $tempFeedback $outputFeedback
    Move-AtomicNoReplace $tempRegistry $outputRegistry
    Move-AtomicNoReplace $tempReanswer $outputReanswer

    $summary = [ordered]@{
        schemaVersion = 'v1.1'
        producer = 'run-n6b-feedback-closure'
        status = 'projected'
        projectionMode = 'input-bundle'
        feedbackRunId = $FeedbackRunId.Trim()
        publishedReleaseId = $PublishedReleaseId.Trim()
        answerCount = @($feedbackRows).Count
        outputs = @(
            (Convert-ToRepoRelative $outputFeedback),
            (Convert-ToRepoRelative $outputRegistry),
            (Convert-ToRepoRelative $outputReanswer)
        )
        redacted = $true
        liveExecution = $false
        note = '仅投影并校验显式输入；未调用生产 API，不能单独证明闭环已执行。'
    }
    $summary | ConvertTo-Json -Compress -Depth 6

    # Commit completed: do not let the finally block treat these files as a
    # failed partial commit.  The list is cleared only after all three moves.
    $script:Committed.Clear()
}

function Read-Bundle([string]$path) {
    if ((Get-Item -LiteralPath $path).PSIsContainer) {
        $feedback = Join-Path $path 'n6-feedback-closure.jsonl'
        $registry = Join-Path $path 'n6-answer-release-registry.json'
        $reanswer = Join-Path $path 'n6b-reanswer-verification.jsonl'
        foreach ($required in @($feedback, $registry, $reanswer)) {
            if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
                throw 'InputBundle 目录缺少三个 canonical N6b 文件。'
            }
        }
        return [pscustomobject]@{
            schemaVersion = 'v1.1'
            feedbackRunId = $null
            publishedReleaseId = $null
            feedbackClosure = Read-JsonLines $feedback 'feedback closure input'
            answerReleaseRegistry = Read-JsonObject $registry 'answer registry input'
            reanswerVerification = Read-JsonLines $reanswer 're-answer input'
        }
    }

    $item = Get-Item -LiteralPath $path
    if ($item.Length -gt 10MB) { throw 'InputBundle 超过 10 MB，拒绝在内存中处理。' }
    try {
        $text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        $value = $text | ConvertFrom-Json
    }
    catch {
        throw 'InputBundle 不是合法 JSON。'
    }
    if ($null -eq $value -or $value -is [Array] -or $value -is [string]) {
        throw 'InputBundle 顶层必须是 JSON 对象。'
    }
    return $value
}

function Get-BundleParts([object]$bundle, [string]$bundlePath) {
    $feedback = Get-PropertyValue $bundle @('feedbackClosure', 'feedback', 'closure')
    $registry = Get-PropertyValue $bundle @('answerReleaseRegistry', 'registry', 'answerRegistry')
    $reanswer = Get-PropertyValue $bundle @('reanswerVerification', 'reanswer', 'verification')
    if (-not $feedback.Found -or -not $registry.Found -or -not $reanswer.Found) {
        throw 'InputBundle 必须包含 feedbackClosure、answerReleaseRegistry、reanswerVerification 三部分。'
    }
    $registryEntries = Get-PropertyValue $registry.Value @('entries', 'answers')
    if ($registryEntries.Found) { $registryValue = $registryEntries.Value } else { $registryValue = $registry.Value }
    return [pscustomobject]@{
        Feedback = $feedback.Value
        Registry = $registryValue
        Reanswer = $reanswer.Value
    }
}

function Assert-BundleMetadata([object]$bundle) {
    $schema = Get-PropertyValue $bundle @('schemaVersion')
    if ($schema.Found -and [string]$schema.Value -cne 'v1.1') {
        throw 'InputBundle schemaVersion 必须为 v1.1。'
    }
    foreach ($spec in @(
        @{ Names = @('feedbackRunId', 'n6bRunId'); Expected = $FeedbackRunId; Label = 'feedbackRunId' },
        @{ Names = @('publishedReleaseId', 'newReleaseId', 'targetReleaseId'); Expected = $PublishedReleaseId; Label = 'publishedReleaseId' }
    )) {
        $field = Get-PropertyValue $bundle $spec.Names
        if ($field.Found -and [string]::IsNullOrWhiteSpace([string]$field.Value) -eq $false -and
            ([string]$field.Value).Trim() -cne $spec.Expected.Trim()) {
            throw "InputBundle $($spec.Label) 与命令行参数不一致。"
        }
    }
}

function Convert-Rows([object]$value, [string]$label, [bool]$requireFeedbackId, [object]$requiredStatus) {
    $rows = As-Rows $value $label
    if (@($rows).Count -eq 0) { throw "$label 不能为空。" }
    $result = New-Object System.Collections.Generic.List[object]
    $seen = New-Object System.Collections.Generic.List[string]
    $index = 0
    foreach ($raw in @($rows)) {
        $index++
        if ($null -eq $raw -or $raw -is [Array] -or $raw -is [string]) {
            throw "$label 第 $index 项必须是 JSON 对象。"
        }
        $projected = [ordered]@{}
        $fieldAliases = [ordered]@{
            answerId = @('answerId')
            runId = @('runId')
            traceId = @('traceId')
            feedbackRunId = @('feedbackRunId', 'n6bRunId')
            evaluationRunId = @('evaluationRunId', 'evalRunId')
            previousReleaseId = @('previousReleaseId', 'oldReleaseId', 'sourceReleaseId')
            publishedReleaseId = @('publishedReleaseId', 'newReleaseId', 'targetReleaseId')
            operatorId = @('operatorId', 'operator', 'actorId')
            status = @('status', 'state')
            lineageStatus = @('lineageStatus')
        }
        foreach ($field in $fieldAliases.Keys) {
            $property = Get-PropertyValue $raw $fieldAliases[$field]
            if (-not $property.Found) { throw "$label 第 $index 项缺少字段 $field。" }
            $projected[$field] = Assert-Token $property.Value "$label[$index].$field"
        }
        if ($requireFeedbackId) {
            $property = Get-PropertyValue $raw @('feedbackId')
            if (-not $property.Found) { throw "$label 第 $index 项缺少字段 feedbackId。" }
            $projected['feedbackId'] = Assert-Token $property.Value "$label[$index].feedbackId"
        }
        # Preserve additional release/correction identifiers only when the
        # caller supplied them explicitly.  They are scalar, allow-listed
        # fields; no value is inferred or defaulted.
        foreach ($optional in @(
            'dataReleaseId', 'knowledgeReleaseId', 'indexReleaseId',
            'newDataReleaseId', 'newKnowledgeReleaseId', 'newIndexReleaseId',
            'correctionCaseId', 'notificationId', 'targetedEvaluationRunId'
        )) {
            $property = Get-PropertyValue $raw @($optional)
            if ($property.Found) {
                $projected[$optional] = Assert-Token $property.Value "$label[$index].$optional"
            }
        }
        if ([string]$projected.lineageStatus -cne 'COMPLETE') {
            throw "$label 第 $index 项 lineageStatus 必须为 COMPLETE。"
        }
        if ($null -ne $requiredStatus -and [string]$projected.status -cne $requiredStatus) {
            throw "$label 第 $index 项 status 必须为 $requiredStatus。"
        }
        if ([string]$projected.previousReleaseId -ceq [string]$projected.publishedReleaseId) {
            throw "$label 第 $index 项 previousReleaseId 与 publishedReleaseId 不能相同。"
        }
        if ([string]$projected.feedbackRunId -cne $FeedbackRunId.Trim()) {
            throw "$label 第 $index 项 feedbackRunId 与命令行参数不一致。"
        }
        if ([string]$projected.publishedReleaseId -cne $PublishedReleaseId.Trim()) {
            throw "$label 第 $index 项 publishedReleaseId 与命令行参数不一致。"
        }
        if ($seen -contains [string]$projected.answerId) {
            throw "$label 存在重复 answerId。"
        }
        [void]$seen.Add([string]$projected.answerId)
        [void]$result.Add([pscustomobject]$projected)
    }
    return $result.ToArray()
}

function Assert-ReanswerStatuses([object[]]$rows) {
    foreach ($row in @($rows)) {
        if ([string]$row.status -cnotin @('VERIFIED', 'CLOSED', 'REANSWERED')) {
            throw 're-answer verification status 必须为 VERIFIED、CLOSED 或 REANSWERED。'
        }
    }
}

function Assert-SameAnswerSet([object[]]$feedback, [object[]]$registry, [object[]]$reanswer) {
    $feedbackIds = @($feedback | ForEach-Object { [string]$_.answerId })
    foreach ($pair in @(
        @{ Name = 'registry'; Rows = $registry },
        @{ Name = 're-answer'; Rows = $reanswer }
    )) {
        $ids = @($pair.Rows | ForEach-Object { [string]$_.answerId })
        foreach ($id in $feedbackIds) {
            if (-not (@($ids | Where-Object { $_ -ceq $id }).Count)) {
                throw "$($pair.Name) 缺少 answerId。"
            }
        }
        foreach ($id in $ids) {
            if (-not (@($feedbackIds | Where-Object { $_ -ceq $id }).Count)) {
                throw "$($pair.Name) 包含未在 feedback closure 中登记的 answerId。"
            }
        }
    }
}

function Assert-CrossFileLineage([object[]]$feedback, [object[]]$registry, [object[]]$reanswer) {
    foreach ($item in @($feedback)) {
        $registered = Find-Answer $registry $item.answerId
        $verified = Find-Answer $reanswer $item.answerId
        foreach ($field in @('runId', 'traceId', 'feedbackRunId', 'evaluationRunId', 'previousReleaseId', 'publishedReleaseId')) {
            if ([string]$item.$field -cne [string]$registered.$field -or
                [string]$item.$field -cne [string]$verified.$field) {
                throw "$($item.answerId) 的 $field 在三个证据文件间不一致。"
            }
        }
        if ([string]$item.feedbackId -cne [string]$verified.feedbackId) {
            throw "$($item.answerId) 的 feedbackId 在 feedback/re-answer 间不一致。"
        }
    }
}

function Find-Answer([object[]]$rows, [string]$answerId) {
    $matches = @($rows | Where-Object { [string]$_.answerId -ceq [string]$answerId })
    if ($matches.Count -ne 1) { throw "无法唯一定位 answerId。" }
    return $matches[0]
}

function Assert-FeedbackRunId([string]$value) {
    $token = Assert-Token $value 'FeedbackRunId'
    if ($token -notmatch '^[A-Za-z0-9._-]{3,128}$') {
        throw 'FeedbackRunId 格式无效。'
    }
}

function Assert-Token([object]$value, [string]$label) {
    if ($null -eq $value) { throw "$label 缺失或为占位符。" }
    if ($value -is [Array] -or $value -is [hashtable] -or $value -is [pscustomobject]) {
        throw "$label 必须是标量字符串。"
    }
    $text = ([string]$value).Trim()
    if ([string]::IsNullOrWhiteSpace($text) -or $text -match '(?i)^(unknown|untraceable|trace-unknown|pending|tbd|null|undefined|none|n/a)$' -or
        $text -match '(?i)^<[^>]+>$' -or $text -match '(?i)(^|[-_])(pending|unknown|untraceable)$') {
        throw "$label 缺失或为占位符。"
    }
    if ($text.Length -gt 256) { throw "$label 长度超限。" }
    return $text
}

function Get-PropertyValue([object]$object, [string[]]$names) {
    if ($null -eq $object) { return [pscustomobject]@{ Found = $false; Value = $null } }
    foreach ($name in $names) {
        $property = $object.PSObject.Properties[$name]
        if ($null -ne $property) {
            return [pscustomobject]@{ Found = $true; Value = $property.Value }
        }
    }
    return [pscustomobject]@{ Found = $false; Value = $null }
}

function As-Rows([object]$value, [string]$label) {
    if ($null -eq $value) { return @() }
    if ($value -is [Array]) { return @($value) }
    # ConvertFrom-Json unwraps a one-element JSON array in some PowerShell
    # versions.  Treat a single object as one row, but never accept scalars.
    if ($value -is [string] -or $value -is [ValueType]) { throw "$label 必须是对象数组。" }
    return @($value)
}

function Read-JsonLines([string]$path, [string]$label) {
    $rows = New-Object System.Collections.Generic.List[object]
    $lineNo = 0
    foreach ($line in @(Get-Content -LiteralPath $path -Encoding UTF8)) {
        $lineNo++
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $row = $line | ConvertFrom-Json }
        catch { throw "$label 第 $lineNo 行不是合法 JSON。" }
        [void]$rows.Add($row)
    }
    return $rows.ToArray()
}

function Read-JsonObject([string]$path, [string]$label) {
    try { $value = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { throw "$label 不是合法 JSON。" }
    return $value
}

function Write-JsonLines([string]$path, [object[]]$rows) {
    $lines = @($rows | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 8 })
    Write-Utf8NoBom $path (($lines -join [Environment]::NewLine) + [Environment]::NewLine)
}

function Write-JsonFile([string]$path, [object]$value) {
    Write-Utf8NoBom $path (($value | ConvertTo-Json -Depth 10) + [Environment]::NewLine)
}

function Write-Utf8NoBom([string]$path, [string]$content) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($path, $content, $encoding)
}

function Invoke-LineageValidator([string]$feedback, [string]$registry, [string]$reanswer, [string]$report) {
    $validator = Join-Path $script:RepoRoot 'tools/validate-feedback-lineage.mjs'
    if (-not (Test-Path -LiteralPath $validator -PathType Leaf)) { throw '反馈血缘校验器不存在。' }
    $arguments = @(
        $validator,
        '--feedback', $feedback,
        '--registry', $registry,
        '--reanswer', $reanswer,
        '--published-release', $PublishedReleaseId.Trim(),
        '--feedback-run-id', $FeedbackRunId.Trim(),
        '--report', $report,
        '--no-clobber'
    )
    & node @arguments 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) { throw '反馈血缘校验器拒绝了 InputBundle；未发布证据文件。' }
}

function Move-AtomicNoReplace([string]$source, [string]$target) {
    try {
        [IO.File]::Move($source, $target)
        [void]$script:Committed.Add($target)
    }
    catch {
        throw '证据提交失败（目标可能已存在）；未覆盖既有文件。'
    }
}

function Resolve-RepoPath([string]$value, [string]$label) {
    $candidate = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $script:RepoRoot $value }
    $full = [IO.Path]::GetFullPath($candidate)
    $root = [IO.Path]::GetFullPath($script:RepoRoot).TrimEnd([char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar))
    $prefix = $root + [IO.Path]::DirectorySeparatorChar
    if ($full.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or -not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$label 必须位于仓库目录内。"
    }
    # Resolve existing links/junctions before reading or writing.  An input
    # symlink that points to a local secret outside the repository must not be
    # accepted merely because its lexical path starts with the repo prefix.
    if (Test-Path -LiteralPath $full) {
        try {
            $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $full -ErrorAction Stop).ProviderPath)
        }
        catch {
            throw "$label 无法解析路径。"
        }
        if ($resolved.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
            -not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "$label 通过链接越出仓库目录。"
        }
        return $resolved
    }
    return $full
}

function Convert-ToRepoRelative([string]$path) {
    $root = [IO.Path]::GetFullPath($script:RepoRoot).TrimEnd([char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar))
    $relative = $path.Substring($root.Length).TrimStart([char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar))
    return $relative.Replace('\', '/')
}

# Functions are declared above before the entry point is invoked.  This is
# important for Windows PowerShell, which executes function declarations in
# source order rather than hoisting them like a JavaScript module.
try {
    Invoke-N6bProjection
    exit 0
}
catch {
    # Do not print the input payload or exception detail that could contain a
    # user-supplied token.  Field/path context is enough for an operator.
    Write-Error $_.Exception.Message
    exit 1
}
finally {
    # Roll back only files created by this invocation.  This protects against a
    # partial three-file commit while never deleting pre-existing evidence.
    foreach ($path in $script:Committed.ToArray()) {
        try {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                [IO.File]::Delete($path)
            }
        }
        catch {
            # Preserve the original failure; an operator can remove the exact
            # temporary/partial path after inspecting the failed run directory.
        }
    }
    if ($null -ne $script:TempDir) {
        try {
            if (Test-Path -LiteralPath $script:TempDir) {
                Remove-Item -LiteralPath $script:TempDir -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        catch { }
    }
}
