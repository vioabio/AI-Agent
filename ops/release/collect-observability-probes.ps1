[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$BaseUrl,
    [Parameter(Mandatory = $true)] [string]$PrometheusUrl,
    [Parameter(Mandatory = $true)] [string]$AlertmanagerUrl,
    [Parameter(Mandatory = $true)] [string]$GrafanaUrl,
    [Parameter(Mandatory = $true)] [string]$OutputDir,
    [Parameter(Mandatory = $true)] [ValidateSet('prod','staging')] [string]$Environment,
    [Parameter(Mandatory = $true)] [string]$ReleaseId,
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
function Safe([object]$Value) { if ($null -eq $Value) { return $null }; $text = ([string]$Value).Replace("`r", ' ').Replace("`n", ' '); return $text.Substring(0, [Math]::Min(240, $text.Length)) }
function Id([string]$Value, [string]$Name) { if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(pending|unknown|tbd|<)') { throw "$Name 缺失或无效" }; return $Value.Trim() }
function Url([string]$Value, [string]$Name) { $u = $null; if (-not [Uri]::TryCreate($Value.TrimEnd('/'), [UriKind]::Absolute, [ref]$u) -or $u.Scheme -notin @('http','https')) { throw "$Name 必须是 http/https URL" }; return $Value.TrimEnd('/') }
function Out([string]$Value) { $root = (Get-Location).Path; $p = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $root $Value)) }; if (-not $p.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputDir 必须位于仓库内' }; New-Item -ItemType Directory -Force -Path $p | Out-Null; return $p }
function Write-Json([string]$Path, [object]$Body) { if ($NoClobber -and (Test-Path -LiteralPath $Path)) { throw "输出已存在（-NoClobber）: $Path" }; $tmp = "$Path.$PID.$([Guid]::NewGuid().ToString('N')).tmp"; try { $Body | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $tmp -Encoding UTF8; Move-Item -LiteralPath $tmp -Destination $Path -Force:(!$NoClobber) } finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue } } }
function Probe([string]$Name, [string]$Uri) { $watch = [Diagnostics.Stopwatch]::StartNew(); $status = $null; $passed = $false; $reason = $null; try { $r = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Uri -ErrorAction Stop; $status = [int]$r.StatusCode; $passed = $status -ge 200 -and $status -lt 300; if (-not $passed) { $reason = 'HTTP status outside 2xx' } } catch { try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch { }; $reason = 'endpoint unavailable (redacted)' }; $watch.Stop(); return [ordered]@{ schemaVersion='v1.1'; probeId=$Name; status=$status; latencyMs=$watch.ElapsedMilliseconds; passed=$passed; endpoint=(Url $Uri 'probe URL'); reason=$reason; releaseId=$ReleaseId; environment=$Environment; redacted=$true } }

$release = Id $ReleaseId 'ReleaseId'
if ($Environment -eq 'prod') { foreach ($value in @($BaseUrl,$PrometheusUrl,$AlertmanagerUrl,$GrafanaUrl)) { if ($value -match '(?i)(localhost|127\.0\.0\.1|staging|test)') { throw 'prod observability 禁止 localhost/staging/test 地址' } } }
$base = Url $BaseUrl 'BaseUrl'; $prom = Url $PrometheusUrl 'PrometheusUrl'; $alert = Url $AlertmanagerUrl 'AlertmanagerUrl'; $grafana = Url $GrafanaUrl 'GrafanaUrl'; $dir = Out $OutputDir
$rows = @()
if ($WhatIf) { $rows += [ordered]@{schemaVersion='v1.1'; probeId='what-if'; status=$null; latencyMs=$null; passed=$false; reason='WhatIf：未访问监控端点'; releaseId=$release; environment=$Environment; redacted=$true} }
else {
    $rows += Probe 'backend-readiness' "$base/api/ready"
    $rows += Probe 'prometheus-ready' "$prom/-/ready"
    $rows += Probe 'prometheus-rules' "$prom/api/v1/rules"
    $rows += Probe 'alertmanager-ready' "$alert/-/ready"
    $rows += Probe 'alertmanager-alerts' "$alert/api/v2/alerts"
    $rows += Probe 'grafana-health' "$grafana/api/health"
}
$names = @('prometheus-targets.json','prometheus-rules.json','alertmanager-events.json','grafana-health.json')
for ($i=0; $i -lt $names.Count; $i++) { $subset = @($rows | Where-Object { $_.probeId -in @('prometheus-ready','prometheus-rules','prometheus-targets','alertmanager-alerts','grafana-health') }); Write-Json (Join-Path $dir $names[$i]) ([ordered]@{schemaVersion='v1.1'; producer='collect-observability-probes'; releaseId=$release; environment=$Environment; collectedAt=[DateTime]::UtcNow.ToString('o'); probes=$subset; redacted=$true}) }
Write-Json (Join-Path $dir 'observability-probes.json') ([ordered]@{schemaVersion='v1.1'; producer='collect-observability-probes'; releaseId=$release; environment=$Environment; collectedAt=[DateTime]::UtcNow.ToString('o'); probes=$rows; passed=(@($rows | Where-Object { -not $_.passed }).Count -eq 0 -and $rows.Count -gt 0); redacted=$true})
$rows | ConvertTo-Json -Depth 8
if (@($rows | Where-Object { -not $_.passed }).Count -gt 0) { exit 1 }
