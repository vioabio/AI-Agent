param(
    [string]$PythonExe = "",
    [switch]$SkipDevDependencies
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$venvPath = Join-Path $projectRoot ".venv"

function Resolve-Python {
    param([string]$Preferred)

    if ($Preferred) {
        return $Preferred
    }

    $candidates = @(
        "C:\Users\MCsir\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe",
        "py -3.12",
        "python"
    )

    foreach ($candidate in $candidates) {
        try {
            if ($candidate.Contains(" ")) {
                & cmd /c $candidate "--version" *> $null
            } else {
                & $candidate --version *> $null
            }
            return $candidate
        } catch {
            continue
        }
    }

    throw "No usable Python 3.12 interpreter found. Pass -PythonExe explicitly."
}

$resolvedPython = Resolve-Python -Preferred $PythonExe

Write-Host "Project root: $projectRoot"
Write-Host "Python exe:   $resolvedPython"
Write-Host "Rebuilding:   $venvPath"

Push-Location $projectRoot
try {
    if ($resolvedPython.Contains(" ")) {
        & cmd /c $resolvedPython "-m" "venv" "--clear" ".venv"
    } else {
        & $resolvedPython -m venv --clear .venv
    }

    & .\.venv\Scripts\python.exe -m pip install --upgrade pip

    if ($SkipDevDependencies) {
        & .\.venv\Scripts\python.exe -m pip install -e .
    } else {
        & .\.venv\Scripts\python.exe -m pip install -e .[dev]
    }

    & .\.venv\Scripts\python.exe --version
    & .\.venv\Scripts\python.exe -m pip -V
} finally {
    Pop-Location
}
