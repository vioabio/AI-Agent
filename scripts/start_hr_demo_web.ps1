$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location (Join-Path $projectRoot "web\hr-review")
$env:HR_API_PROXY_TARGET = "http://127.0.0.1:8001"
$env:VITE_HR_DEMO = "true"
try {
    & node.exe node_modules/vite/bin/vite.js --host localhost --port 5174 --strictPort
} finally {
    Remove-Item Env:HR_API_PROXY_TARGET -ErrorAction SilentlyContinue
    Remove-Item Env:VITE_HR_DEMO -ErrorAction SilentlyContinue
}
