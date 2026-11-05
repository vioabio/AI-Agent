$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location (Join-Path $projectRoot "web\hr-review")
& node.exe node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort
