param(
    [string]$ProjectRoot = "",
    [ValidatePattern('^\d{8}$')]
    [string]$RunDate = (Get-Date -Format "yyyyMMdd"),
    [int]$ExpectedKeywords = 40,
    [int]$MaxResumeAttempts = 3,
    [switch]$ValidateOnly
)

$runner = Join-Path $PSScriptRoot "run_single_ai_job_search.ps1"
& $runner `
    -ProjectRoot $ProjectRoot `
    -JobKey "fde" `
    -RunDate $RunDate `
    -ExpectedKeywords $ExpectedKeywords `
    -MaxResumeAttempts $MaxResumeAttempts `
    -ValidateOnly:$ValidateOnly
exit $LASTEXITCODE
