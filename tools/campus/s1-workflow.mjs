// Real local model/HTTP verification on dedicated accounts and teams only.
// Never reads private configuration or persists credentials/cookies.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomBytes } from 'node:crypto'
const option=(key,fallback)=>process.argv.includes(key)?process.argv[process.argv.indexOf(key)+1]:fallback
const base=new URL(option('--base-url','http://127.0.0.1:18126/api'))
if(base.protocol!=='http:'||!['localhost','127.0.0.1'].includes(base.hostname)||base.pathname!=='/api'||base.username||base.password||base.search||base.hash)throw new Error('Use a local loopback /api endpoint')
const output=resolve(option('--output','artifacts/campus/s1/live'))
if(existsSync(resolve(output,'report.json')))throw new Error('Choose a new output; evidence is never overwritten')
mkdirSync(output,{recursive:true})
const cases=JSON.parse(readFileSync('document/秋招升级/S01/cases.json','utf8'))
const report={schemaVersion:1,startedAt:new Date().toISOString(),mode:'real-model-and-http',approvalActor:'automated dedicated test account; not a claim of human approval',checks:[],cases:[]}
const save=()=>writeFileSync(resolve(output,'report.json'),JSON.stringify(report,null,2)+'\n')
const unwrap=value=>value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
function client(){
  const cookies=new Map()
  return async(path,body,method=body===undefined?'GET':'POST',expected=200)=>{
    const response=await fetch(`${base}${path}`,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',
      Cookie:[...cookies].map(([name,value])=>`${name}=${value}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
    for(const line of response.headers.getSetCookie()){const [name,...parts]=line.split(';')[0].split('=');cookies.set(name,parts.join('='))}
    report.checks.push({method,path,status:response.status})
    const value=response.status===204?null:await response.json()
    if(response.status!==expected)throw new Error(`${method} ${path}: HTTP ${response.status}, expected ${expected}`)
    return unwrap(value)
  }
}
async function account(api){
  if((await api('/auth/session')).mode!=='local')throw new Error('Requires isolated local account mode')
  const username=`s1_${randomBytes(6).toString('hex')}`,password=randomBytes(24).toString('base64url')
  await api('/auth/local/register',{username,password,nickname:'S1 闭环验证'},'POST',201)
  await api('/auth/local/login',{username,password})
  await api('/auth/session')
}
async function wait(api,record,waiting){
  const until=Date.now()+135000
  let cursor='0',events=[]
  while(Date.now()<until){
    const page=await api(`/chat/runs/${record.runId}/events?afterEventId=${cursor}`)
    for(const event of page.events||[]){events.push(event);cursor=String(event.eventId??event.id??cursor)}
    const run=await api(`/chat/runs/${record.runId}`)
    const approval=events.find(event=>String(event.eventType||event.type).toLowerCase().replaceAll('_','.')==='approval.required')
    if((waiting&&run.status==='WAITING_APPROVAL'&&approval)||(!waiting&&['COMPLETED','FAILED','TIMED_OUT','CANCELLED'].includes(run.status))){
      record.status=run.status
      record.events=events
      if(approval)record.approval=approval.payload||approval.data
      return run
    }
    if(waiting&&['COMPLETED','FAILED','TIMED_OUT','CANCELLED'].includes(run.status))throw new Error(`Expected preview, got ${run.status}`)
    await new Promise(resolve=>setTimeout(resolve,400))
  }
  throw new Error('Run verification deadline exceeded')
}
const a=client(),b=client()
try{
  await account(a);await account(b)
  report.runtime=await a('/chat/runtime')
  if(!['readCurrentTeam','previewTeamChanges','listCurrentTeams'].every(name=>report.runtime.tools.includes(name)))throw new Error('S1 tools not registered')
  for(const name of ['squirtle','charmander'])await a(`/pokemon/reference/pokemon/${name}?dataReleaseId=pokeapi-reference-v1`)
  const team=await a('/teams',cases.team,'POST',201)
  report.teamId=team.id
  const run=await a('/chat/runs',{...cases.context,conversationId:`s1-${randomBytes(8).toString('hex')}`,timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex'),
    prompt:`请先 readCurrentTeam 读取当前账号保存的队伍编号 ${team.id}。按队伍原有配置，单独调用 calculateBattleDamage 计算第九世代杰尼龟用 surf 攻击小火龙的伤害；双方满HP，单打无天气、地形、强化、状态、墙或太晶。计算成功后，只把第1位杰尼龟等级从50改为51，其他字段全部保留。用 previewTeamChanges 生成预览并等我点确认，不能先保存。请分别调用工具，不要把计算和预览放在同一批调用。计算证据只用于原50级条件，不宣称51级最优或胜率。`},'POST',202)
  const first={name:'read-calculate-preview-confirm-readback',runId:run.runId,startedAt:new Date().toISOString()};report.cases.push(first);save()
  await wait(a,first,true)
  const before=await a(`/teams/${team.id}`)
  if(before.members[0].build.level!==50)throw new Error('Write occurred before confirmation')
  const preview=await a(`/chat/runs/${run.runId}/team-change`)
  first.preview=preview.preview
  if(!preview.preview.calculationEvidence.includes('164～194'))throw new Error('Missing actual expected calculation evidence')
  await b(`/teams/${team.id}`,undefined,'GET',404)
  await b(`/chat/runs/${run.runId}/team-change`,undefined,'GET',404)
  const approval=first.approval.approvalId
  await b(`/chat/runs/${run.runId}/team-change/decision`,{approvalId:approval,approved:true},'POST',404)
  await a(`/hitl/approvals/${approval}?runId=${run.runId}&approved=true`,{},'POST',403)
  await a(`/chat/runs/${run.runId}/team-change/decision`,{approvalId:approval,approved:true})
  await wait(a,first,false)
  first.outcome=await a(`/chat/runs/${run.runId}/team-change`)
  const after=await a(`/teams/${team.id}`)
  if(first.status!=='COMPLETED'||first.outcome.status!=='APPLIED'||after.members[0].build.level!==51)throw new Error('Confirmed change not applied')
  const restored=structuredClone(after);restored.members[0].build.level=50
  if(JSON.stringify(restored)!==JSON.stringify(before))throw new Error('Unconfirmed fields changed')
  await a(`/chat/runs/${run.runId}/team-change/decision`,{approvalId:approval,approved:true},'POST',409)
  const history=await a(`/chat/conversations/${run.conversationId}/messages`)
  first.historyReadBackMatches=history.some(message=>message.role==='assistant'&&message.content===first.outcome.message)
  if(!first.historyReadBackMatches)throw new Error('Saved receipt/history mismatch')
  first.finishedAt=new Date().toISOString();save();console.log('S1 normal: confirmed save, read-back and account isolation passed')
  const conflictRun=await a('/chat/runs',{...cases.context,conversationId:`s1-${randomBytes(8).toString('hex')}`,timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex'),
    prompt:`请 readCurrentTeam 读取队伍编号 ${team.id}，只把第一位等级改为52，其他配置不动，不推断伤害；请 previewTeamChanges 生成预览并等我确认。`},'POST',202)
  const conflict={name:'concurrent-edit-rejects-old-preview',runId:conflictRun.runId};report.cases.push(conflict);save()
  await wait(a,conflict,true)
  const edit={name:'验证中已另行编辑',rulesetId:after.rulesetId,dataReleaseId:after.dataReleaseId,members:after.members}
  await a(`/teams/${team.id}`,edit,'PATCH')
  await a(`/chat/runs/${conflictRun.runId}/team-change/decision`,{approvalId:conflict.approval.approvalId,approved:true})
  await wait(a,conflict,false)
  conflict.outcome=await a(`/chat/runs/${conflictRun.runId}/team-change`)
  const current=await a(`/teams/${team.id}`)
  if(conflict.outcome.status!=='CONFLICT'||current.members[0].build.level!==51||current.name!==edit.name)throw new Error('Stale preview was not rejected')
  console.log('S1 conflict: old preview rejected, concurrent edit preserved')
  const incomplete=await a('/teams',{name:'待补条件的练习队',rulesetId:cases.team.rulesetId,dataReleaseId:cases.team.dataReleaseId,
    members:[{formId:7,slot:1}]},'POST',201)
  const questionRun=await a('/chat/runs',{...cases.context,conversationId:`s1-${randomBytes(8).toString('hex')}`,timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex'),
    prompt:`请 readCurrentTeam 读取已保存队伍编号 ${incomplete.id}。我还没有确定对手、使用招式和希望修改的项目，想先了解需要补充什么条件。请先向我提问，不要计算伤害、生成修改预览或保存。`},'POST',202)
  const clarification={name:'missing-conditions-clarify-without-write',runId:questionRun.runId};report.cases.push(clarification);save()
  await wait(a,clarification,false)
  clarification.actualTools=clarification.events.filter(event=>String(event.eventType||event.type).toLowerCase().replaceAll('_','.')==='tool.started').map(event=>(event.payload||event.data).toolName)
  if(clarification.status!=='COMPLETED'||!clarification.actualTools.includes('readCurrentTeam')||clarification.actualTools.some(name=>['previewTeamChanges','calculateBattleDamage'].includes(name)))throw new Error('Missing-condition task did not clarify safely')
  const questionHistory=await a(`/chat/conversations/${questionRun.conversationId}/messages`)
  clarification.answer=questionHistory.filter(message=>message.role==='assistant').at(-1)?.content||''
  if(!/(对手|招式|目标)/.test(clarification.answer))throw new Error('Expected clarification topics missing')
  await a(`/chat/runs/${questionRun.runId}/team-change`,undefined,'GET',404)
  if(JSON.stringify(await a(`/teams/${incomplete.id}`))!==JSON.stringify(incomplete))throw new Error('Clarification task changed a team')
  console.log('S1 clarification: missing conditions asked, no calculation/preview/write')
  report.status='PASSED';report.qualityNote='Workflow verification only; does not establish strategy accuracy.'
}catch(error){report.status='FAILED';report.error=error.message;process.exitCode=1}
finally{for(const api of [a,b])try{await api('/logout',undefined,'POST',204)}catch{report.cleanupSessionWarning=true}report.finishedAt=new Date().toISOString();save();console.log(`S1 report: ${resolve(output,'report.json')}`)}
