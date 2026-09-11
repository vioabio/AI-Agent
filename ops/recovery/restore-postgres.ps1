<#
.SYNOPSIS
  Restore an explicitly selected PostgreSQL backup in a fresh staging project.

.DESCRIPTION
  This wrapper is an evidence boundary around pg_restore and Docker Compose.
  It never chooses a project from an environment variable and never targets
  production.  The default path (and -WhatIf) only validates safe paths and
  writes a pending, redacted plan.  A destructive restore requires an explicit
  isolated project, approval id, checksum, Compose file and database identity;
  every native command is passed as an argument array and its exit code is
  checked before the next mutation.

  The wrapper does not read application-local configuration or print database
  credentials.  Password authentication must already be available inside the
  isolated database container (for example through its reviewed Secret Store
  configuration); no password is inferred or placed on a command line.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,

    [Parameter(Mandatory = $true)]
    [string]$ProjectName,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    # Execute mode requires an explicit Compose file and database identity.
    # They remain optional in WhatIf so the documented dry-run can be created
    # before an adapter is approved.
    [string]$ComposeFile = '',
    [string]$DatabaseService = '',
    [string]$DatabaseName = '',
    [string]$DatabaseUser = '',
    [string]$ExpectedSha256 = '',
    [string]$ApprovalId = '',

    [switch]$FreshIsolatedProject,

    [Alias('Execute')]
    [switch]$ExecuteDestructive,

    [switch]$WhatIf,
    [switch]$NoClobber,

    [ValidateRange(10, 3600)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Test-ReparsePoint([IO.FileSystemInfo]$Item) {
    if ($null -eq $Item) { return $false }
    if (-not [string]::IsNullOrWhiteSpace([string]$Item.LinkType)) { return $true }
    return (($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
}

function Assert-ParentChainSafe([string]$Path, [string]$Name) {
    $root = $script:RepoRoot.TrimEnd('\')
    $current = Split-Path -Parent $Path
    while ($current -and $current.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $current -PathType Container) {
            $item = Get-Item -LiteralPath $current -Force
            if (Test-ReparsePoint $item) { throw "$Name 的父目录不允许通过符号链接或重解析点访问。" }
            if ([StringComparer]::OrdinalIgnoreCase.Equals($current.TrimEnd('\'), $root)) { break }
        }
        $next = Split-Path -Parent $current
        if ($next -eq $current) { break }
        $current = $next
    }
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)] [string]$Value,
        [Parameter(Mandatory = $true)] [string]$Name,
        [switch]$MustExist
    )
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0 -or
        $Value.IndexOf([char]10) -ge 0 -or $Value.IndexOf([char]13) -ge 0) {
        throw "$Name 不能为空或包含无效字符。"
    }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) {
        [IO.Path]::GetFullPath($Value)
    } else {
        [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value))
    }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name 必须位于仓库内。"
    }
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        throw "$Name 不能是目录。"
    }
    if ($MustExist) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "$Name 不存在或不是普通文件。"
        }
        $item = Get-Item -LiteralPath $candidate -Force
        if (Test-ReparsePoint $item) { throw "$Name 不允许通过符号链接或重解析点读取。" }
        Assert-ParentChainSafe $candidate $Name
    } else {
        # Walk existing parents so an output cannot be redirected through a
        # junction/symlink outside the repository.
        Assert-ParentChainSafe $candidate $Name
    }
    return $candidate
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

function Assert-SafeToken([object]$Value, [string]$Name) {
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text) -or $text -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$') {
        throw "$Name 必须是简单标识符。"
    }
    return $text.Trim()
}

function Assert-ProjectName([string]$Value) {
    $project = Assert-SafeToken $Value 'ProjectName'
    if ($project.Length -lt 3 -or $project -match '(?i)(^|[-_.])(prod|production|default|live|main)([-_.]|$)') {
        throw 'ProjectName 不能指向 prod/default/live 项目。'
    }
    if ($project.ToLowerInvariant() -in @('staging', 'sandbox', 'test', 'restore', 'drill', 'canary')) {
        throw 'ProjectName 不能使用共享隔离环境保留名；必须是本次演练唯一名称。'
    }
    if ($project -notmatch '(?i)(restore|drill|staging|canary|sandbox|test)') {
        throw 'ProjectName 必须包含 restore/drill/staging/canary/sandbox/test 隔离标识。'
    }
    return $project
}

function Normalize-Sha256([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $normalized = $Value.Trim() -replace '^sha256:', ''
    if ($normalized -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'ExpectedSha256 必须是 64 位十六进制 SHA-256（可带 sha256: 前缀）。'
    }
    return $normalized.ToLowerInvariant()
}

function Write-ImmutableText {
    param([Parameter(Mandatory = $true)] [string]$Path, [Parameter(Mandatory = $true)] [string]$Content)
    if (Test-Path -LiteralPath $Path) {
        throw "输出已存在（证据不可覆盖）: $(Relative-RepoPath $Path)"
    }
    $parent = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, $Content, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            try { [IO.File]::Delete($temporary) } catch { }
        }
    }
}

function Get-Executable([string]$Name, [string]$Label) {
    try { $command = Get-Command -Name $Name -CommandType Application -ErrorAction Stop }
    catch { throw "$Label 不在 PATH 中。" }
    if ($null -eq $command -or [string]::IsNullOrWhiteSpace([string]$command.Source)) {
        throw "$Label 路径解析失败。"
    }
    return $command.Source
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)] [string]$Executable,
        [Parameter(Mandatory = $true)] [string[]]$Arguments,
        [Parameter(Mandatory = $true)] [string]$Label
    )
    # Never use Invoke-Expression or a shell string; this keeps project,
    # service and database identifiers as distinct native arguments.
    & $Executable @Arguments 1>$null 2>$null
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "$Label 失败（exit code $exitCode）。" }
    return $exitCode
}

function Invoke-NativeText {
    param(
        [Parameter(Mandatory = $true)] [string]$Executable,
        [Parameter(Mandatory = $true)] [string[]]$Arguments,
        [Parameter(Mandatory = $true)] [string]$Label
    )
    $lines = @(& $Executable @Arguments 2>$null)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "$Label 失败（exit code $exitCode）。" }
    return @($lines | ForEach-Object { ([string]$_).Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Assert-ComposeFileSafe([string]$Path) {
    # Compose's -p flag is always supplied below.  A file-level project name
    # or COMPOSE_PROJECT_NAME interpolation would make the target ambiguous.
    $extension = [IO.Path]::GetExtension($Path).ToLowerInvariant()
    if ($extension -notin @('.yml', '.yaml')) { throw 'ComposeFile 必须是 .yml 或 .yaml。' }
    $fileName = [IO.Path]::GetFileName($Path)
    if ($fileName -match '(?i)(application-local|mcp-servers|^\.env)') { throw 'ComposeFile 不得指向本地敏感配置。' }
    $content = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ($content -match '(?im)^\s*name\s*:') { throw 'ComposeFile 不得声明顶层 name。' }
    if ($content -match '(?i)COMPOSE_PROJECT_NAME') { throw 'ComposeFile 不得依赖隐式 COMPOSE_PROJECT_NAME。' }
    if ($content -match '(?im)^\s*external\s*:\s*(?:true|yes)\s*$') { throw 'ComposeFile 不得复用 external volume/network。' }
}

function Get-ComposeServices {
    param([string]$Docker, [string]$Compose, [string]$Project)
    $args = @('compose', '-p', $Project, '-f', $Compose, 'config', '--services')
    return Invoke-NativeText $Docker $args 'Compose 配置校验'
}

function Assert-FreshComposeProject {
    param([string]$Docker, [string]$Compose, [string]$Project, [string]$Service)
    $ids = @(Invoke-NativeText $Docker @('compose', '-p', $Project, '-f', $Compose, 'ps', '-aq') '隔离项目容器检查')
    if ($ids.Count -gt 0) { throw 'ProjectName 已存在容器；必须使用全新的隔离项目。' }
    $volumes = @(Invoke-NativeText $Docker @('volume', 'ls', '--filter', "label=com.docker.compose.project=$Project", '--format', '{{.Name}}') '隔离项目卷检查')
    if ($volumes.Count -gt 0) { throw 'ProjectName 已存在数据卷；拒绝复用旧状态。' }
    $networks = @(Invoke-NativeText $Docker @('network', 'ls', '--filter', "label=com.docker.compose.project=$Project", '--format', '{{.Name}}') '隔离项目网络检查')
    if ($networks.Count -gt 0) { throw 'ProjectName 已存在网络；拒绝复用旧状态。' }
    $services = @(Get-ComposeServices $Docker $Compose $Project)
    if (-not ($services | Where-Object { $_.Trim() -ceq $Service })) {
        throw "ComposeFile 未声明 DatabaseService=$Service。"
    }
}

function Get-DatabaseContainerId {
    param([string]$Docker, [string]$Compose, [string]$Project, [string]$Service)
    $ids = @(Invoke-NativeText $Docker @('compose', '-p', $Project, '-f', $Compose, 'ps', '-q', $Service) '数据库容器定位')
    if ($ids.Count -ne 1 -or $ids[0] -notmatch '^[0-9a-fA-F]{12,64}$') {
        throw '数据库容器 ID 无效或不唯一。'
    }
    return $ids[0].Trim()
}

function Wait-DatabaseReady {
    param([string]$Docker, [string]$Compose, [string]$Project, [string]$Service, [string]$User, [string]$Database, [int]$Timeout)
    $attempts = [Math]::Max(1, [Math]::Ceiling($Timeout / 2))
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        & $Docker 'compose' '-p' $Project '-f' $Compose 'exec' '-T' $Service 'pg_isready' '-U' $User '-d' $Database 1>$null 2>$null
        if ($LASTEXITCODE -eq 0) { return }
        if ($attempt -lt $attempts) { Start-Sleep -Seconds 2 }
    }
    throw 'PostgreSQL readiness 在超时内未通过。'
}

if ($WhatIf -and $ExecuteDestructive) { throw '-WhatIf 与 -ExecuteDestructive 不能同时使用。' }

# Resolve the output first so ordinary validation failures can still leave a
# redacted blocked record.  Unsafe output paths fail before any external call.
$outputPath = Resolve-RepoPath $Output 'Output'
$project = Assert-ProjectName $ProjectName
$backupPath = Resolve-RepoPath $BackupFile 'BackupFile' -MustExist
$backupItem = Get-Item -LiteralPath $backupPath -Force
if ($backupItem.Length -le 0) { throw 'BackupFile 为空。' }
if ([StringComparer]::OrdinalIgnoreCase.Equals($outputPath, $backupPath)) { throw 'Output 不能覆盖 BackupFile。' }

$expected = Normalize-Sha256 $ExpectedSha256
$composePath = $null
if (-not [string]::IsNullOrWhiteSpace($ComposeFile)) {
    $composePath = Resolve-RepoPath $ComposeFile 'ComposeFile' -MustExist
    if ([StringComparer]::OrdinalIgnoreCase.Equals($outputPath, $composePath)) { throw 'Output 不能覆盖 ComposeFile。' }
}

$service = $null
$database = $null
$user = $null
if (-not [string]::IsNullOrWhiteSpace($DatabaseService)) { $service = Assert-SafeToken $DatabaseService 'DatabaseService' }
if (-not [string]::IsNullOrWhiteSpace($DatabaseName)) { $database = Assert-SafeToken $DatabaseName 'DatabaseName' }
if (-not [string]::IsNullOrWhiteSpace($DatabaseUser)) { $user = Assert-SafeToken $DatabaseUser 'DatabaseUser' }

$status = 'environment-pending'
$passed = $false
$mutationPerformed = $false
$backupSha256 = $null
$formatStatus = 'not-checked'
$composeStatus = 'not-executed'
$restoreStatus = 'not-executed'
$cleanupStatus = 'not-needed'
$containerIdPresent = $false
$errorType = $null
$reason = 'WhatIf/plan：仅生成 pending 证据；未读取备份内容、未调用 pg_restore 或 Docker Compose'
$commandSummary = 'pg_restore --list; docker compose -p <isolated-project> ...; pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error --no-password'

if ($ExecuteDestructive) {
    try {
        if (-not $FreshIsolatedProject) { throw '执行模式必须显式提供 -FreshIsolatedProject。' }
        if ([string]::IsNullOrWhiteSpace($ApprovalId) -or $ApprovalId -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$' -or $ApprovalId -match '(?i)(pending|unknown|tbd|<)') {
            throw '执行模式必须提供有效 ApprovalId。'
        }
        if ($null -eq $expected) { throw '执行模式必须提供 ExpectedSha256；不会猜测备份完整性。' }
        if ($null -eq $composePath) { throw '执行模式必须显式提供 ComposeFile。' }
        if ($null -eq $service -or $null -eq $database -or $null -eq $user) {
            throw '执行模式必须显式提供 DatabaseService、DatabaseName 和 DatabaseUser。'
        }
        Assert-ComposeFileSafe $composePath
        $docker = Get-Executable 'docker' 'Docker CLI'
        $pgRestore = Get-Executable 'pg_restore' 'pg_restore'

        # Verify checksum and archive format before creating a container or
        # touching a volume.  Output from pg_restore is intentionally dropped.
        $backupSha256 = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($backupSha256 -ne $expected) { throw 'BackupFile checksum 不匹配；拒绝恢复。' }
        Invoke-NativeChecked $pgRestore @('--list', $backupPath) 'pg_restore 备份格式校验' | Out-Null
        $formatStatus = 'verified'

        Assert-FreshComposeProject $docker $composePath $project $service
        $mutationPerformed = $true
        Invoke-NativeChecked $docker @('compose', '-p', $project, '-f', $composePath, 'up', '-d', '--no-deps', $service) 'Compose 启动隔离数据库' | Out-Null
        $composeStatus = 'started'
        Wait-DatabaseReady $docker $composePath $project $service $user $database $TimeoutSeconds
        $containerId = Get-DatabaseContainerId $docker $composePath $project $service
        $containerIdPresent = $true
        $remoteBackup = '/tmp/vio-restore-' + [Guid]::NewGuid().ToString('N') + '.dump'
        try {
            Invoke-NativeChecked $docker @('cp', $backupPath, "${containerId}:$remoteBackup") '备份复制到隔离数据库容器' | Out-Null
            $restoreArgs = @('compose', '-p', $project, '-f', $composePath, 'exec', '-T', $service, 'pg_restore', '--exit-on-error', '--clean', '--if-exists', '--no-owner', '--no-privileges', '--no-password', '--username', $user, '--dbname', $database, $remoteBackup)
            Invoke-NativeChecked $docker $restoreArgs '隔离 PostgreSQL 恢复' | Out-Null
            $restoreStatus = 'passed'
        } finally {
            try {
                Invoke-NativeChecked $docker @('compose', '-p', $project, '-f', $composePath, 'exec', '-T', '--user', '0', $service, 'rm', '-f', $remoteBackup) '清理容器内临时备份' | Out-Null
                $cleanupStatus = 'passed'
            } catch {
                $cleanupStatus = 'blocked'
                if ($null -eq $errorType) { $errorType = 'CleanupFailure' }
                throw
            }
        }
        if ($cleanupStatus -ne 'passed') { throw '容器内临时备份清理未通过。' }
        $status = 'passed'
        $passed = $true
        $reason = '已在全新隔离 staging Compose project 完成 checksum/格式校验和 pg_restore；未读取或输出密码'
    } catch {
        if ($null -eq $errorType) { $errorType = $_.Exception.GetType().Name }
        $status = 'blocked'
        $passed = $false
        $reason = '执行分支失败或未完成；保持 fail-closed，未生成通过证据'
        if ($cleanupStatus -eq 'not-needed' -and $containerIdPresent) { $cleanupStatus = 'unknown' }
    }
}

$record = @(
    'schemaVersion=v1.1',
    'producer=restore-postgres',
    "generatedAt=$([DateTime]::UtcNow.ToString('o'))",
    "status=$status",
    "passed=$($passed.ToString().ToLowerInvariant())",
    'environment=staging',
    'environmentScope=staging-only',
    "projectName=$project",
    "freshIsolatedProject=$($FreshIsolatedProject.ToString().ToLowerInvariant())",
    "approvalIdPresent=$((-not [string]::IsNullOrWhiteSpace($ApprovalId)).ToString().ToLowerInvariant())",
    "backupFile=$(Relative-RepoPath $backupPath)",
    "backupBytes=$($backupItem.Length)",
    "expectedSha256Present=$((($null -ne $expected).ToString()).ToLowerInvariant())",
    "backupSha256=$(if($backupSha256){$backupSha256}else{'not-computed'})",
    "formatStatus=$formatStatus",
    "composeFile=$(if($composePath){Relative-RepoPath $composePath}else{'not-selected'})",
    "databaseService=$(if($service){$service}else{'not-selected'})",
    "databaseName=$(if($database){$database}else{'not-selected'})",
    "databaseUser=$(if($user){$user}else{'not-selected'})",
    "composeStatus=$composeStatus",
    "restoreStatus=$restoreStatus",
    "cleanupStatus=$cleanupStatus",
    "containerIdPresent=$($containerIdPresent.ToString().ToLowerInvariant())",
    "mutationPerformed=$($mutationPerformed.ToString().ToLowerInvariant())",
    "errorType=$(if($errorType){$errorType}else{'none'})",
    "commandSummary=$commandSummary",
    "reason=$reason",
    'redacted=true'
) -join [Environment]::NewLine

try {
    Write-ImmutableText $outputPath ($record + [Environment]::NewLine)
} catch {
    Write-Error $_
    exit 1
}
Write-Output $record

if ($ExecuteDestructive -and -not $passed) { exit 1 }
exit 0
