[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ProcessFile,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$artifacts = (Resolve-Path (Join-Path $root 'artifacts')).Path
$source = (Resolve-Path (Join-Path $root $ProcessFile)).Path
$output = [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
foreach ($path in @($source,$output)) {
    if (-not $path.StartsWith($artifacts + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Restart only isolated artifact instances.' }
}
if (Test-Path -LiteralPath $output) { throw 'Choose a new output directory.' }
$meta = [IO.File]::ReadAllText($source) | ConvertFrom-Json
if ($meta.status -ne 'READY' -or $meta.port -eq 8123 -or $meta.testFault -ne 'None' -or $meta.semanticFixture -or $meta.isolatedGuideCorpus) { throw 'Use a ready default isolated backend.' }
if (-not $meta.dataDirectory -or -not $meta.dataDirectory.StartsWith($artifacts + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Isolated persistent data identity is required.' }
$build = (Resolve-Path -LiteralPath $meta.buildDirectory).Path
if (-not $build.StartsWith($artifacts + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Isolated build identity is required.' }
$process = Get-Process -Id $meta.pid
if ($meta.startedAt -is [DateTime]) { $started = [DateTimeOffset]$meta.startedAt }
else { $started = [DateTimeOffset]::Parse($meta.startedAt,[Globalization.CultureInfo]::InvariantCulture) }
if ($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne $started.UtcDateTime.Ticks) { throw 'Process identity changed.' }
$command = (Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)").CommandLine
if (-not $command.Contains($build) -or -not $command.Contains($meta.dataDirectory)) { throw 'Process is not the named isolated backend.' }
Stop-Process -InputObject $process
$process.WaitForExit(10000) | Out-Null
$options = @{
    BuildDirectory=$build.Substring($root.Length + 1); OutputDirectory=$OutputDirectory; Port=[int]$meta.port
    PersistentDataDirectory=$meta.dataDirectory.Substring($root.Length + 1); FrontendPort=[int]$meta.frontendPort
    Skills=[bool]$meta.skills; ContextAssembly=[bool]$meta.contextAssembly; AnswerBlocks=[bool]$meta.answerBlocks
    SemanticSearch=[bool]$meta.semanticSearch; DisableDomainTracing=(-not [bool]$meta.domainTracing)
    TracingSampleRate=[double]$meta.tracingSampleRate
    Rerank=[bool]$meta.rerank; RerankFixture=[bool]$meta.rerankFixture
}
& (Join-Path $PSScriptRoot 'start-ablation-backend.ps1') @options
