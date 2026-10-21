param(
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,79}$')][string]$Query = 'squirtle',
    [string]$CachePath = (Join-Path $PSScriptRoot '../.local-data/pokeapi')
)
$ErrorActionPreference = 'Stop'
$cacheDirectory = [IO.Path]::GetFullPath($CachePath)
[IO.Directory]::CreateDirectory($cacheDirectory) | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)

function Save-ReferenceSnapshot([string]$Kind, [string]$Key) {
    # Use PowerShell's system proxy for a local diagnostic/bootstrap; source URLs remain fixed.
    $url = "https://pokeapi.co/api/v2/$Kind/$Key/"
    $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 20 -MaximumRedirection 0
    $payload = [string]$response.Content
    if ($utf8.GetByteCount($payload) -gt 2000000) { throw 'Reference exceeds size limit' }
    $resource = $payload | ConvertFrom-Json
    if ($resource.id -le 0 -or !$resource.name) { throw 'Missing resource identity' }
    if ($Key -match '^\d+$') {
        if ([long]$Key -ne [long]$resource.id) { throw 'Resource ID mismatch' }
    } elseif ($Key -ne $resource.name) { throw 'Resource name mismatch' }
    $hashAlgorithm = [Security.Cryptography.SHA256]::Create()
    try { $digest = 'sha256:' + ([BitConverter]::ToString($hashAlgorithm.ComputeHash($utf8.GetBytes($payload)))).Replace('-', '').ToLowerInvariant() }
    finally { $hashAlgorithm.Dispose() }
    $snapshot = @{schemaVersion=1;url=$url;payload=$payload;sha256=$digest;retrievedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Compress
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
Write-Output "Cached official PokéAPI reference: $($pokemon.name) #$($pokemon.id)"
