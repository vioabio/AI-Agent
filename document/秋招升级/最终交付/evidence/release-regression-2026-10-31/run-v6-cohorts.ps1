$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
Set-Location -LiteralPath $root
$base=$PSScriptRoot
$jar=Join-Path $base 'candidate-v6.jar'
$java=Join-Path $root '.tools/jdk21/jdk-21.0.12.1+1/bin/java.exe'
$node='C:/Program Files/nodejs/node.exe'
[xml]$localRun=[IO.File]::ReadAllText((Join-Path $root '.run/VioAiAgentApplication.run.xml'))
$keyNode=$localRun.SelectSingleNode('//env[@name="DEEPSEEK_API_KEY"]')
if($null -eq $keyNode -or [string]::IsNullOrWhiteSpace($keyNode.GetAttribute('value'))){throw 'Missing local configured model key'}
$env:DEEPSEEK_API_KEY=$keyNode.GetAttribute('value')
$env:Path="C:/Program Files/nodejs;$env:Path"
$cohorts=@(
 @{name='v6-publish-regression';suite='evaluation/campus/release-publish-2026-10-31';split='heldout'},
 @{name='v6-final-regression';suite='evaluation/campus/release-final-2026-10-31';split='heldout'},
 @{name='v6-confirmation-regression';suite='evaluation/campus/release-confirmation-2026-10-31';split='heldout'},
 @{name='v6-fresh-regression';suite='evaluation/campus/release-2026-10-31';split='heldout'},
 @{name='v6-known-dev';suite='evaluation/campus/s2';split='dev'},
 @{name='v6-known-heldout';suite='evaluation/campus/s2';split='heldout'}
)
try {
 foreach($cohort in $cohorts) {
  $runtime=Join-Path $base ('runtime/'+$cohort.name)
  New-Item -ItemType Directory -Path $runtime -Force|Out-Null
  $startup=@(Get-Content (Join-Path $base 'startup-arguments.json') -Raw|ConvertFrom-Json)|Where-Object {$_ -notmatch '^--vio\.damage-engine\.script='}|ForEach-Object {
   if($_ -match '^--vio\.local-(accounts|history|teams|guides)\.path='){'--vio.local-'+$Matches[1]+'.path='+$runtime+'/'+$Matches[1]+'.sqlite'}else{$_}
  }
  $startup|ConvertTo-Json|Set-Content (Join-Path $base ($cohort.name+'-startup.json'))
  $arguments=@('-Dfile.encoding=UTF-8','-jar',$jar)+$startup
  foreach($argument in $arguments){if($argument.Contains('"') -or $argument.EndsWith('\')){throw 'Unsupported quoted argument'}}
  $quoted=@($arguments|ForEach-Object {'"'+$_+'"'})
  $child=Start-Process -FilePath $java -ArgumentList $quoted -WindowStyle Hidden -RedirectStandardOutput (Join-Path $base ($cohort.name+'-server.log')) -RedirectStandardError (Join-Path $base ($cohort.name+'-server-error.log')) -PassThru
  try {
   $ready=$false
   for($i=0;$i -lt 60;$i++){
    if($child.HasExited){throw 'Isolated backend exited before readiness'}
    try{$response=Invoke-WebRequest http://127.0.0.1:18259/api/auth/session -TimeoutSec 2;if($response.StatusCode -eq 200){$ready=$true;break}}catch{}
    Start-Sleep -Milliseconds 500
   }
   if(!$ready){throw 'Isolated backend readiness timeout'}
   & $node artifacts/campus/trace-replay/workspace-v1/tools/campus/s2-evaluate.mjs run --suite $cohort.suite --split $cohort.split --repetitions 1 --base-url http://127.0.0.1:18259/api --output (Join-Path $base $cohort.name) *> (Join-Path $base ($cohort.name+'-collector.log'))
   @{exitCode=$LASTEXITCODE;finishedAt=(Get-Date).ToString('o');isolatedProcessPerCohort=$true;rateLimitsUnchanged=$true}|ConvertTo-Json|Set-Content (Join-Path $base ($cohort.name+'-result.json'))
  } finally {
   $child.Refresh()
   if(!$child.HasExited){
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($child.Id)"
    if($null -ne $process -and $process.CommandLine -like "*$jar*"){Stop-Process -Id $child.Id;Wait-Process -Id $child.Id -ErrorAction SilentlyContinue}
    else{throw 'Refusing to stop an unexpected process'}
   }
  }
 }
} finally {Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue}
