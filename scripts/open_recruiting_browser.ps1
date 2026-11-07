param(
    [Parameter(Mandatory=$true)][string]$BrowserPath,
    [Parameter(Mandatory=$true)][string]$ProfileDirectory,
    [Parameter(Mandatory=$true)][ValidateRange(1024,65535)][int]$DebugPort,
    [Parameter(Mandatory=$true)][ValidateSet('https://lpt.liepin.com/','https://www.zhipin.com/')][string]$StartUrl
)
$ErrorActionPreference = 'Stop'
$resolvedProfile = [IO.Path]::GetFullPath($ProfileDirectory)
$resolvedBrowser = (Resolve-Path -LiteralPath $BrowserPath).Path
$ownedBrowsers = @(Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -eq $resolvedBrowser -and
    $_.CommandLine -and $_.CommandLine.IndexOf($resolvedProfile, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
    $_.CommandLine.Contains("--remote-debugging-port=$DebugPort")
})
$portOpen = $false
$probeClient = [Net.Sockets.TcpClient]::new()
try { $probeClient.Connect('127.0.0.1', $DebugPort); $portOpen = $true } catch {} finally { $probeClient.Dispose() }
if ($portOpen) {
    if ($ownedBrowsers.Count -eq 0) { throw 'Browser port belongs to an unverified process' }
    # An existing debug port does not mean its window is visible. An explicit
    # login request opens a new foreground window in the same isolated profile.
    $reopenArguments = @(('--user-data-dir="' + $resolvedProfile + '"'), "--remote-debugging-port=$DebugPort", '--new-window', $StartUrl)
    Start-Process -FilePath $resolvedBrowser -ArgumentList $reopenArguments -WindowStyle Normal | Out-Null
    @{ state='reopened_owned_browser'; pid=$ownedBrowsers[0].ProcessId } | ConvertTo-Json -Compress
    exit 0
}
if ($ownedBrowsers.Count -gt 0) { throw 'Owned browser is running without its expected debug port; inspect manually' }
$browserArguments = @(
    ('--user-data-dir="' + $resolvedProfile + '"'),
    "--remote-debugging-port=$DebugPort",
    '--remote-debugging-address=127.0.0.1',
    '--no-first-run', '--no-default-browser-check', '--new-window', $StartUrl
)
# The operator must see and control this login window.
$browserProcess = Start-Process -FilePath $resolvedBrowser -ArgumentList $browserArguments -WindowStyle Normal -PassThru
@{ state='started_visible_browser'; pid=$browserProcess.Id } | ConvertTo-Json -Compress
