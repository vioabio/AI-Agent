import fs from 'node:fs'
import path from 'node:path'
import {digest} from './evaluation.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
const [directory,output,...extra]=process.argv.slice(2)
if(!directory||!output||extra.length||fs.existsSync(output))throw Error('Supply preserved observations and a fresh output file')
const source=path.join(directory,'observations.json'),report=JSON.parse(fs.readFileSync(source)),rows=[]
for(const row of report.records){
 if(path.basename(row.eventsFile)!==row.eventsFile)throw Error('Invalid event filename')
 const bytes=fs.readFileSync(path.join(directory,row.eventsFile));if(digest(bytes)!==row.eventsSha256)throw Error('Changed event evidence')
 const events=JSON.parse(bytes)
 const mode=row.executionMode||'MODEL_TOOL_SELECTION'
 rows.push({caseId:row.caseId,...auditTeamPlanning(events,{request:row.request,reportSha256:row.httpBefore.reportSha256,mode,...(row.caseId==='read-saved-team-then-check'?{teamId:report.teamBefore.id}:{})})})
}
fs.writeFileSync(output,JSON.stringify({schemaVersion:'team-planning-reaudit-v1',sourceSha256:digest(fs.readFileSync(source)),auditorSha256:digest(fs.readFileSync('tools/campus/team-planning.mjs')),rows,byExecutionMode:Object.fromEntries(['MODEL_TOOL_SELECTION','PROGRAM_SUPPLIED_TEAM_REQUEST'].map(mode=>[mode,{passed:rows.filter(r=>r.mode===mode&&r.pass).length,total:rows.filter(r=>r.mode===mode).length}])),humanReviewed:0,formalAdmissions:0,scope:'Historical structured requests only; not a new natural-language experiment or model quality score'},null,2)+'\n',{flag:'wx'})
console.log(JSON.stringify({rows:rows.length,passed:rows.filter(r=>r.pass).length,output}))
