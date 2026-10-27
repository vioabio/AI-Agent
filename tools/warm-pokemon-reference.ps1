param(
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,79}$')][string]$Query = 'squirtle',
    [string]$CachePath = (Join-Path $PSScriptRoot '../.local-data/pokeapi'),
    [string]$Proxy = ''
)
$ErrorActionPreference = 'Stop'
$cacheDirectory = [IO.Path]::GetFullPath($CachePath)
if ($cacheDirectory.Length -gt ([IO.Path]::GetPathRoot($cacheDirectory)).Length) { $cacheDirectory = $cacheDirectory.TrimEnd([char[]]@('\','/')) }
[IO.Directory]::CreateDirectory($cacheDirectory) | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$cacheTool = Join-Path $PSScriptRoot 'reference-cache.mjs'
if (!(Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required for cache integrity checks' }
$proxyMode = 'system'
if (!$Proxy) {
    $preferences = Join-Path ([IO.Path]::GetDirectoryName($cacheDirectory)) 'pokeapi-network.properties'
    if (Test-Path -LiteralPath $preferences) {
        if ((Get-Item -LiteralPath $preferences).Length -gt 16384) { throw 'Network preferences are too large' }
        foreach ($line in (Get-Content -LiteralPath $preferences)) {
            if ($line -match '^\s*proxy\s*=\s*(.*?)\s*$') { $Proxy = $Matches[1]; $proxyMode = 'local-preferences' }
        }
    }
} else { $proxyMode = 'explicit' }
$proxyValue = $Proxy.Trim()
if ($proxyValue -and $proxyValue -ine 'direct') {
    $proxyUri = $null
    if ($proxyValue -notmatch '^http://(?:\[[0-9a-fA-F:.]+\]|[^/:?#@]+):[0-9]{1,5}/?$' -or !([Uri]::TryCreate($proxyValue, [UriKind]::Absolute, [ref]$proxyUri)) -or $proxyUri.Scheme -ne 'http' -or !$proxyUri.Host -or $proxyUri.Port -lt 1 -or $proxyUri.Port -gt 65535 -or $proxyUri.UserInfo -or $proxyUri.Query -or $proxyUri.Fragment -or $proxyUri.AbsolutePath -ne '/') {
        throw 'Proxy must be http://host:port without credentials, path or query'
    }
}
$preflightText = & node $cacheTool inspect --cache $cacheDirectory --pokemon $Query
$preflightExit = $LASTEXITCODE
if ($preflightExit -ne 0 -and $preflightExit -ne 2) { throw 'Reference cache inspection failed' }
$preflight = ($preflightText | Out-String) | ConvertFrom-Json
if (@($preflight.queries | Where-Object { $_.status -eq 'INVALID' }).Count -gt 0) { throw 'Existing reference cache is invalid; preserve it and inspect before retrying' }
if ($preflight.ready) { Write-Output "Reference ready from validated cache: $Query (no download)"; return }

function Get-PayloadDigest([string]$Payload) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return 'sha256:' + ([BitConverter]::ToString($algorithm.ComputeHash($utf8.GetBytes($Payload)))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}
function Get-ReferencePayload([string]$Url) {
    Add-Type -AssemblyName System.Net.Http
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    if ($proxyValue -ieq 'direct') { $handler.UseProxy = $false }
    elseif ($proxyValue) { $handler.Proxy = [Net.WebProxy]::new($proxyUri); $handler.UseProxy = $true }
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('VioPokemonLocalBootstrap/1.0')
    try {
        $response = $client.GetAsync($Url).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne 200) { throw "Reference request returned HTTP $([int]$response.StatusCode)" }
            return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } finally { $response.Dispose() }
    } finally { $client.Dispose() }
}

function Save-ReferenceSnapshot([string]$Kind, [string]$Key) {
    $url = "https://pokeapi.co/api/v2/$Kind/$Key/"
    $existingFile = Join-Path $cacheDirectory "$Kind-$Key.json"
    $snapshot = $null
    if (Test-Path -LiteralPath $existingFile) {
        $existing = [IO.File]::ReadAllText($existingFile) | ConvertFrom-Json
        $payload = [string]$existing.payload
        $resource = $payload | ConvertFrom-Json
        $sourceMatches = @("https://pokeapi.co/api/v2/$Kind/$($resource.id)/", "https://pokeapi.co/api/v2/$Kind/$($resource.name)/") -contains $existing.url
        if ($existing.schemaVersion -ne 1 -or !$sourceMatches -or $existing.sha256 -ne (Get-PayloadDigest $payload)) { throw 'Existing snapshot integrity failed' }
        $snapshot = [IO.File]::ReadAllText($existingFile)
    } else { $payload = Get-ReferencePayload $url }
    if ($utf8.GetByteCount($payload) -gt 2000000) { throw 'Reference exceeds size limit' }
    $resource = $payload | ConvertFrom-Json
    if ($resource.id -le 0 -or !$resource.name) { throw 'Missing resource identity' }
    if ($Key -match '^\d+$') {
        if ([long]$Key -ne [long]$resource.id) { throw 'Resource ID mismatch' }
    } elseif ($Key -ne $resource.name) { throw 'Resource name mismatch' }
    if (!$snapshot) { $snapshot = @{schemaVersion=1;url=$url;payload=$payload;sha256=(Get-PayloadDigest $payload);retrievedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Compress }
    $keys = @($Key)
    if ($Kind -eq 'pokemon') { $keys += @([string]$resource.id, [string]$resource.name) }
    foreach ($alias in ($keys | Select-Object -Unique)) {
        if ($alias -notmatch '^[a-z0-9][a-z0-9-]{0,79}$') { throw 'Invalid resource alias' }
        $target = Join-Path $cacheDirectory "$Kind-$alias.json"
        # Preserve any existing snapshot: checksum failures must remain visible for diagnosis.
        if (Test-Path -LiteralPath $target) { continue }
        $temporary = Join-Path $cacheDirectory ([Guid]::NewGuid().ToString() + '.tmp')
        try {
            [IO.File]::WriteAllText($temporary, $snapshot, $utf8)
            [IO.File]::Move($temporary, $target)
        } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
    }
    return $resource
}
$pokemon = Save-ReferenceSnapshot 'pokemon' $Query
$species = [string]$pokemon.species.name
if ($species -notmatch '^[a-z0-9][a-z0-9-]{0,79}$') { throw 'Invalid species identity' }
$null = Save-ReferenceSnapshot 'pokemon-species' $species
$verifiedText = & node $cacheTool inspect --cache $cacheDirectory --pokemon $Query
if ($LASTEXITCODE -ne 0) { throw 'Downloaded reference did not pass the final cache check' }
Write-Output "Local PokéAPI reference ready: $($pokemon.name) #$($pokemon.id); proxy mode=$proxyMode"
