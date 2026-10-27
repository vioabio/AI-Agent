param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string[]]$Resources=@('ability:torrent','ability:rain-dish','ability:snow-cloak','ability:cursed-body','move:water-pulse','move:ice-beam','move:shadow-ball','move:protect','move:spikes','move:destiny-bond','nature:modest','nature:timid'),
    [string]$Proxy=''
)
$ErrorActionPreference='Stop'
$utf8=[Text.UTF8Encoding]::new($false)
if ($Resources.Count -lt 1 -or $Resources.Count -gt 32 -or @($Resources | Select-Object -Unique).Count -ne $Resources.Count) { throw 'Select 1..32 unique resource identities' }
foreach ($resource in $Resources) { if ($resource -notmatch '^(ability|move|nature):[a-z0-9][a-z0-9-]{0,79}$') { throw 'Invalid fixed-origin resource identity' } }
$target=[IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $target) { throw 'Output directory must be new; existing evidence is never replaced' }
if (!(Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($target)))) { throw 'Create the output parent directory first' }
if (!$Proxy) {
    $preferences=Join-Path $PSScriptRoot '../.local-data/pokeapi-network.properties'
    if (Test-Path -LiteralPath $preferences) { foreach ($line in Get-Content -LiteralPath $preferences) { if ($line -match '^\s*proxy\s*=\s*(.*?)\s*$') { $Proxy=$Matches[1] } } }
}
$handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false
if ($Proxy -eq 'direct') { $handler.UseProxy=$false }
elseif ($Proxy) {
    $proxyUri=[Uri]$Proxy
    if ($proxyUri.Scheme -ne 'http' -or !$proxyUri.Host -or $proxyUri.Port -lt 1 -or $proxyUri.UserInfo -or $proxyUri.Query -or $proxyUri.Fragment -or $proxyUri.AbsolutePath -ne '/') { throw 'Proxy must be a credential-free HTTP host and port' }
    $handler.Proxy=[Net.WebProxy]::new($proxyUri);$handler.UseProxy=$true
}
function Get-Digest([byte[]]$Bytes) { $algorithm=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()} }
function Write-NewFile([string]$Path,[byte[]]$Bytes) { $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write);try{$stream.Write($Bytes,0,$Bytes.Length)}finally{$stream.Dispose()} }
$client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(25);$client.MaxResponseContentBufferSize=2000000
$client.DefaultRequestHeaders.UserAgent.ParseAdd('VioPokemonMechanismReference/1.0')
$snapshots=@();$files=@()
try {
    foreach ($resource in $Resources) {
        $kind,$slug=$resource.Split(':');$url="https://pokeapi.co/api/v2/$kind/$slug/"
        $response=$client.GetAsync($url).GetAwaiter().GetResult()
        try { if ([int]$response.StatusCode -ne 200) { throw "Source returned HTTP $([int]$response.StatusCode)" };$payload=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } finally {$response.Dispose()}
        $body=$payload | ConvertFrom-Json
        if ($body.name -ne $slug -or $body.id -lt 1 -or $utf8.GetByteCount($payload) -gt 2000000) { throw 'Resource identity or size mismatch' }
        $at=[DateTime]::UtcNow.ToString('o');$checksum='sha256:'+(Get-Digest $utf8.GetBytes($payload));$file="$kind-$slug.json"
        $encoded=$utf8.GetBytes((@{schemaVersion=1;url=$url;payload=$payload;sha256=$checksum;retrievedAt=$at} | ConvertTo-Json -Compress)+"`n")
        $snapshots+=@{file=$file;bytes=$encoded};$files+=@{file=$file;fileSha256=(Get-Digest $encoded);url=$url;payloadSha256=$checksum;retrievedAt=$at}
    }
} finally {$client.Dispose()}
[IO.Directory]::CreateDirectory($target) | Out-Null
foreach($snapshot in $snapshots) { Write-NewFile (Join-Path $target $snapshot.file) $snapshot.bytes }
$manifest=@{schemaVersion='mechanics-raw-v1';files=$files;scope='Fixed-origin public resource snapshots, not competition legality or freshness guarantee'}
Write-NewFile (Join-Path $target 'manifest.json') $utf8.GetBytes(($manifest | ConvertTo-Json -Depth 8)+"`n")
Write-Output "Captured $($files.Count) public resources; existing source versions were preserved."
