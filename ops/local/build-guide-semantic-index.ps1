param([uri]$BaseUri = 'http://127.0.0.1:8123/api', [string]$Username)
$ErrorActionPreference = 'Stop'
if (-not $BaseUri.IsLoopback -or $BaseUri.Scheme -notin @('http', 'https') -or $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment) {
    throw '此脚本仅允许连接本机后端。'
}
$apiRoot = $BaseUri.AbsoluteUri.TrimEnd('/')
$guideSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Get-GuideCsrfHeaders {
    $csrfCookie = $guideSession.Cookies.GetCookies($BaseUri) | Where-Object Name -eq 'CSRF-TOKEN' | Select-Object -First 1
    if (-not $csrfCookie) { throw '未取得 CSRF Cookie，请确认本地账号登录模式已启用。' }
    return @{ 'X-CSRF-TOKEN' = [uri]::UnescapeDataString($csrfCookie.Value) }
}
Invoke-RestMethod -Uri "$apiRoot/auth/session" -WebSession $guideSession | Out-Null
if (-not $Username) { $Username = Read-Host '本地账号用户名' }
$guidePassword = Read-Host '本地账号密码' -AsSecureString
try {
    $guideLogin = @{ username = $Username; password = [System.Net.NetworkCredential]::new('', $guidePassword).Password } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$apiRoot/auth/local/login" -WebSession $guideSession -Headers (Get-GuideCsrfHeaders) -ContentType 'application/json' -Body $guideLogin | Out-Null
    $guideLogin = $null
    Invoke-RestMethod -Uri "$apiRoot/auth/session" -WebSession $guideSession | Out-Null
    $guideState = Invoke-RestMethod -Uri "$apiRoot/pokemon/guides/semantic" -WebSession $guideSession
    if (-not $guideState.enabled -or -not $guideState.configured) { throw 'Embedding 尚未开启或模型配置不完整；请先配置环境变量并重启后端。' }
    Write-Host '开始显式构建攻略向量索引（已有有效缓存时复用）。'
    $guideResult = Invoke-RestMethod -Method Post -Uri "$apiRoot/pokemon/guides/semantic/index" -WebSession $guideSession -Headers (Get-GuideCsrfHeaders) -ContentType 'application/json' -Body '{}' -TimeoutSec 90
    $guideResult | Select-Object enabled, configured, ready, mode, model, dimensions, indexedChunks, state | Format-List
    if (-not $guideResult.ready) { throw '索引未就绪；原有 BM25 检索仍可使用。请按 state 检查接口、模型与维度。' }
} finally {
    $guideLogin = $null
    $guidePassword.Dispose()
    try { Invoke-RestMethod -Method Post -Uri "$apiRoot/logout" -WebSession $guideSession -Headers (Get-GuideCsrfHeaders) -ContentType 'application/json' -Body '{}' | Out-Null } catch { }
}
