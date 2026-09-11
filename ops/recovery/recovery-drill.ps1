[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,
    [string]$BackupFile = "",
    [switch]$ExecuteDestructive
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

if (-not [Uri]::IsWellFormedUriString($BaseUrl, [UriKind]::Absolute)) {
    throw "BaseUrl 必须是完整的 http/https 地址。"
}
$baseUri = [Uri]$BaseUrl
if ($baseUri.Scheme -notin @('http', 'https')) {
    throw "BaseUrl 只允许 http/https。"
}

function Require-Environment([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "缺少环境变量 $Name；脚本不会猜测凭据或数据库地址。"
    }
    return $value
}

function Invoke-RequiredCurl([string]$Uri) {
    # Windows PowerShell 默认不会把 native 程序的非零退出码转成异常；
    # 显式检查 LASTEXITCODE，确保恢复探针失败时脚本立即 fail-closed，
    # 而不是继续执行停止/启动或打印“OK”。
    & curl.exe --fail --silent --show-error $Uri | Out-Null
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "HTTP 探针失败: $Uri (curl exit code $exitCode)"
    }
}

Write-Host "[1/4] readiness 预检: $BaseUrl/api/actuator/health/readiness"
Invoke-RequiredCurl "$BaseUrl/api/actuator/health/readiness"
Write-Host "readiness=OK"

Write-Host "[2/4] API 业务健康预检"
Invoke-RequiredCurl "$BaseUrl/api/health"
Write-Host "health=OK"

if (-not $ExecuteDestructive) {
    Write-Host "[3/4] dry-run：未执行删除容器、恢复数据库或索引切换。"
    Write-Host "传入 -ExecuteDestructive 并在隔离环境中按 README 逐步执行。"
    Write-Host "[4/4] dry-run 完成"
    exit 0
}

$apiKey = Require-Environment 'VIO_API_KEY'
$composeProject = Require-Environment 'COMPOSE_PROJECT_NAME'

if ([string]::IsNullOrWhiteSpace($BackupFile) -or -not (Test-Path -LiteralPath $BackupFile)) {
    throw "-ExecuteDestructive 模式必须传入已校验的备份文件路径。"
}

if ($PSCmdlet.ShouldProcess($composeProject, '停止演练环境应用容器')) {
    docker compose -p $composeProject stop vio-ai-agent frontend
}

Write-Host "[3/4] 容器已停止；数据库恢复由值班人员按 README 手工确认。"
Write-Host "备份文件: $BackupFile"

if ($PSCmdlet.ShouldProcess($composeProject, '启动演练环境并等待 readiness')) {
    docker compose -p $composeProject start postgres vio-ai-agent frontend
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            Invoke-RequiredCurl "$BaseUrl/api/actuator/health/readiness"
            break
        } catch {
            if ($attempt -eq 30) { throw "readiness 在 30 次尝试后仍未通过。" }
            Start-Sleep -Seconds 5
        }
    }
}

Write-Host "[4/4] 固定探针（不会打印 API Key）"
# 不把 Secret 拼到 curl 命令行参数：Windows 进程观察器/审计器可能读取完整
# command line。Invoke-WebRequest 将 Header 保留在当前进程内存中，脚本只输出
# 探针结果，不输出响应正文或凭据。
$probeHeaders = @{ 'X-API-Key' = $apiKey }
$probeResponse = Invoke-WebRequest -UseBasicParsing -Method Get `
    -Uri "$BaseUrl/api/metrics" -Headers $probeHeaders
if ($probeResponse.StatusCode -lt 200 -or $probeResponse.StatusCode -ge 300) {
    throw "metrics 探针返回 HTTP $($probeResponse.StatusCode)"
}
Write-Host "recovery_probe=OK；请把 RPO/RTO、runId、traceId 和截图填入 README 表格。"
