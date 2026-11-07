$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOutput = Join-Path $taskRoot 'src/main/resources/pokemon-reference'
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskSource = 'https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv'
$taskRaw = (Invoke-WebRequest -Uri $taskSource -TimeoutSec 20).Content
$taskRows = @($taskRaw -split '\r?\n' | Where-Object {
    $taskColumns = $_ -split ',', 4
    $taskId = 0
    $taskColumns.Length -ge 3 -and [int]::TryParse($taskColumns[0], [ref]$taskId) -and $taskId -ge 1 -and $taskId -le 1025 -and $taskColumns[1] -in @('4','12')
})
if ($taskRows.Count -lt 1500) { throw 'Upstream name index is incomplete; existing resources were not changed.' }
$taskPayload = "pokemon_species_id,local_language_id,name,genus`n" + ($taskRows -join "`n") + "`n"
function Get-TaskDigest([string]$taskValue) {
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try { return 'sha256:' + ([BitConverter]::ToString($taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskValue)))).Replace('-','').ToLowerInvariant() }
    finally { $taskHasher.Dispose() }
}
$taskMetadata = @{sourceUrl=$taskSource;retrievedAt=[DateTime]::UtcNow.ToString('o');sha256=(Get-TaskDigest $taskPayload);upstreamSha256=(Get-TaskDigest $taskRaw);scope='Chinese names for species 1..1025 only; not stats or competition rules'}
[IO.File]::WriteAllText((Join-Path $taskOutput 'species-names.csv'), $taskPayload, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $taskOutput 'species-names.meta.json'), ($taskMetadata | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "Updated $($taskRows.Count) source-backed Chinese names with checksums."
