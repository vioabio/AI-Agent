$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $projectRoot
& (Join-Path $projectRoot ".venv\Scripts\python.exe") -m uvicorn apps.hr_review_api.main:create_app --factory --host 127.0.0.1 --port 8000
