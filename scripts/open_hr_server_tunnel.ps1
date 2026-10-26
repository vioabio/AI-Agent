$ErrorActionPreference = "Stop"
Write-Host "Keep this terminal open. Visit https://localhost:8443 after the tunnel connects."
ssh.exe -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L 127.0.0.1:8443:127.0.0.1:18443 root@47.98.197.126
