param(
    [string]$ProjectRoot = "D:\lx\jianlizhuanqu"
)

$ErrorActionPreference = "Stop"
$PythonExe = Join-Path $ProjectRoot ".venv\Scripts\python.exe"
$LogDir = Join-Path $ProjectRoot "logs"
$LogPath = Join-Path $LogDir "daily_report_task.log"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Location $ProjectRoot

$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
"[$timestamp] start daily report" | Out-File -FilePath $LogPath -Append -Encoding utf8

& $PythonExe "scripts\send_db_daily_report.py" --use-cache 2>&1 |
    Out-File -FilePath $LogPath -Append -Encoding utf8

$exitCode = $LASTEXITCODE
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
"[$timestamp] finish daily report exit_code=$exitCode" | Out-File -FilePath $LogPath -Append -Encoding utf8

exit $exitCode
