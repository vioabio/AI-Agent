$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $projectRoot
& (Join-Path $projectRoot ".venv\Scripts\python.exe") -m scripts.run_hr_demo
