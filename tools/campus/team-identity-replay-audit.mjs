import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {auditIdentityReplay} from './team-identity-replay.mjs'
import {digest} from './evaluation.mjs'

export function auditCapturedIdentity(directory,output){
 if(!directory||!output||fs.existsSync(output))throw Error('Source directory and fresh audit filename required')
 const root=path.resolve(directory),inputs={}
 const rootStat=fs.lstatSync(root);if(!rootStat.isDirectory()||rootStat.isSymbolicLink())throw Error('Unsafe evidence directory')
 const read=file=>{
  const target=path.join(root,file),stat=fs.lstatSync(target)
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>5_000_000)throw Error('Unsafe or oversized evidence')
  const bytes=fs.readFileSync(target);inputs[file]=digest(bytes);return bytes
 }
 const raw=read('observations.json'),record=JSON.parse(raw),binding=JSON.parse(read('history-binding.json'))
 const planBytes=read('replay-plan.json'),plan=JSON.parse(planBytes),preflight=JSON.parse(read('preflight.json'))
 const collector=read('recorded-collector.spec.js')
 // Fixed public plan only; never follow a file path supplied by source material.
 const originalBytes=fs.readFileSync('evaluation/campus/demo-recording-v1/plan.json'),original=JSON.parse(originalBytes)
 if(digest(originalBytes)!==preflight.originalPlanSha256||record.planSha256!==preflight.originalPlanSha256
   ||digest(planBytes)!==preflight.replayPlanSha256||digest(collector)!==preflight.collectorSha256)throw Error('Changed replay plan or recorded collector')
 for(const [name,file] of Object.entries({ToolCallAgent:'target/classes/com/vio/vioaiagent/agent/ToolCallAgent.class',ToolExecutionGateway:'target/classes/com/vio/vioaiagent/tools/governance/ToolExecutionGateway.class'}))
  if(!preflight.compiledClassSha256?.[file]||record.runtime?.implementationSha256?.[name]!==preflight.compiledClassSha256[file])throw Error('Runtime classes differ from startup preflight')
 const report=auditIdentityReplay(record,{...plan,readOnlyPrompt:original.readOnlyPrompt,changePromptTemplate:original.changePromptTemplate},
  {historyBinding:binding,observationSha256:digest(raw)})
 const result={...report,inputSha256:inputs,originalPlanSha256:digest(originalBytes),newModelCalls:0}
 fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});return result
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [directory,output,...extra]=process.argv.slice(2);if(extra.length)throw Error('Source directory and fresh audit filename required')
 const report=auditCapturedIdentity(directory,output)
 console.log(JSON.stringify({output,pass:report.pass,capturedModelCalls:report.actualModelCalls,newModelCalls:0,strictPreviewInputCertified:report.strictPreviewInputCertified}))
}
