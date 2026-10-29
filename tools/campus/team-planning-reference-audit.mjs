import fs from 'node:fs'
import path from 'node:path'
import {digest} from './evaluation.mjs'
import {auditTeamPlanning} from './team-planning.mjs'
import {auditTeamPlanningWithReferences} from './team-planning-reference.mjs'
const [directory,output,...extra]=process.argv.slice(2)
if(!directory||!output||extra.length||fs.existsSync(output))throw Error('Supply preserved observations and a fresh output file')
const protocolPath='evaluation/campus/team-planning-reference-v2/protocol.json'
const protocol=JSON.parse(fs.readFileSync(protocolPath)),source=path.join(directory,'observations.json'),report=JSON.parse(fs.readFileSync(source)),rows=[]
if(protocol.protocol!=='SCOPED_REFERENCE_READS_V2'||protocol.partialEvidencePasses!==false)throw Error('Unexpected protocol')
for(const row of report.records){
 if(typeof row.eventsFile!=='string'||path.basename(row.eventsFile)!==row.eventsFile)throw Error('Invalid event filename')
 const bytes=fs.readFileSync(path.join(directory,row.eventsFile));if(digest(bytes)!==row.eventsSha256)throw Error('Changed event evidence')
 const events=JSON.parse(bytes),expected={request:row.request,reportSha256:row.httpBefore.reportSha256,mode:row.executionMode||'MODEL_TOOL_SELECTION',...(row.caseId==='read-saved-team-then-check'?{teamId:report.teamBefore.id}:{})}
 rows.push({caseId:row.caseId,originalRecordedPass:row.pass,v1:auditTeamPlanning(events,expected),v2:auditTeamPlanningWithReferences(events,expected,protocol.queriesByPokemonId)})
}
const result={schemaVersion:'team-planning-reference-reaudit-v2',scope:protocol.scope,observationsSha256:digest(fs.readFileSync(source)),protocolSha256:digest(fs.readFileSync(protocolPath)),implementationSha256:Object.fromEntries(['tools/campus/team-planning.mjs','tools/campus/team-planning-reference.mjs','tools/campus/team-planning-reference-audit.mjs'].map(p=>[p,digest(fs.readFileSync(p))])),rows,v1Passed:rows.filter(r=>r.v1.pass).length,v2Passed:rows.filter(r=>r.v2.pass).length,planned:rows.length,newModelCalls:0,humanReviewed:0,formalAdmissions:0}
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
console.log(JSON.stringify({v1Passed:result.v1Passed,v2Passed:result.v2Passed,planned:result.planned,newModelCalls:0}))
