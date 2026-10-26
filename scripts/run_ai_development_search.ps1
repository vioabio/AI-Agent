param(
    [string]$ProjectRoot = "",
    [ValidatePattern('^\d{8}$')]
    [string]$RunDate = (Get-Date -Format "yyyyMMdd"),
    [int]$ExpectedKeywords = 32,
    [int]$MaxResumeAttempts = 3,
    [switch]$ValidateOnly
)

$runner = Join-Path $PSScriptRoot "run_single_ai_job_search.ps1"
& $runner `
    -ProjectRoot $ProjectRoot `
    -JobKey "ai_development" `
    -RunDate $RunDate `
    -ExpectedKeywords $ExpectedKeywords `
    -MaxResumeAttempts $MaxResumeAttempts `
    -ValidateOnly:$ValidateOnly
exit $LASTEXITCODE
