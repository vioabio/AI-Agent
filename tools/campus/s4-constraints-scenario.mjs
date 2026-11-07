// Dedicated loopback accounts only. Credentials and cookies remain in memory.
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { randomBytes, createHash } from 'node:crypto'
import { localBase } from './evaluation.mjs'
const [baseArg, outputArg, ...extra] = process.argv.slice(2)
if (!baseArg || !outputArg || extra.length) throw Error('Usage: node tools/campus/s4-constraints-scenario.mjs LOCAL_API NEW_OUTPUT')
const base = localBase(baseArg), output = resolve(outputArg)
if (existsSync(output)) throw Error('Never overwrite evidence')
mkdirSync(output, { recursive: true })
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const report = { schemaVersion: 1, startedAt: new Date().toISOString(), plannedRuns: 2, checks: {}, runs: [],
  qualityAcceptance: 'NOT_PERFORMED', scope: 'Explicit preferences, per-Run snapshot and owner isolation; not answer compliance or quality acceptance' }
function client() {
  const cookies = new Map()
  return async (path, body, method = body === undefined ? 'GET' : 'POST', expected = 200) => {
    const response = await fetch(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: 'http://localhost:3000', 'Content-Type': 'application/json', Cookie: [...cookies].map(([n,v]) => `${n}=${v}`).join('; '),
        ...(cookies.get('CSRF-TOKEN') ? { 'X-CSRF-TOKEN': cookies.get('CSRF-TOKEN') } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    for (const line of response.headers.getSetCookie()) { const [n,...v] = line.split(';')[0].split('='); cookies.set(n,v.join('=')) }
    if (response.status !== expected) throw Error(`HTTP ${response.status}, expected ${expected}`)
    const text = await response.text(), value = text ? JSON.parse(text) : null
    return value && Object.hasOwn(value,'code') && Object.hasOwn(value,'data') ? value.data : value
  }
}
async function account(api) {
  if ((await api('/auth/session')).mode !== 'local') throw Error('Requires isolated local account mode')
  const username='prefs_'+randomBytes(6).toString('hex'), password=randomBytes(24).toString('base64url')
  await api('/auth/local/register',{username,password,nickname:'对话偏好验证'},'POST',201)
  await api('/auth/local/login',{username,password})
}
const api=client(), foreign=client(), conversationId='prefs-'+randomBytes(12).toString('hex'), active=new Set()
const settings={revision:0,responseStyle:'CONCISE',trainingBudget:'LOW',avoidLegendary:true,notes:'请用简体中文，面向新手简洁解释。'}
const path=`/chat/conversations/${conversationId}/constraints`
try {
  await account(api);await account(foreign);report.runtime=await api('/chat/runtime')
  if (!report.runtime.features.conversationConstraints) throw Error('Missing preference runtime identity')
  report.sourceSha256={}
  function walk(directory) {
    for(const entry of readdirSync(directory,{withFileTypes:true})) {
      const file=resolve(directory,entry.name)
      if(entry.isDirectory()) walk(file)
      else if(file.endsWith('.java')) report.sourceSha256[relative(process.cwd(),file).replaceAll('\\','/')]=hash(readFileSync(file))
    }
  }
  walk(resolve('src/main/java'))
  for(const file of ['pom.xml','src/main/resources/db/migration/V31__conversation_constraints.sql']) report.sourceSha256[file]=hash(readFileSync(file))
  for(const [name,expected] of Object.entries(report.runtime.implementationSha256)) {
    const source=Object.keys(report.sourceSha256).find(p=>p.endsWith(`/${name}.java`))
    if(!source || hash(readFileSync(source.replace('src/main/java/','target/classes/').replace(/\.java$/,'.class')))!==expected) throw Error(`Unattested class: ${name}`)
  }
  report.checks.runtimeIdentity=true
  const first=await api(path,settings,'PUT');report.checks.initialSave=first.revision===1
  await api(path,settings,'PUT',409);report.checks.staleRejected=true
  await foreign(path,undefined,'GET',404);await foreign(path,settings,'PUT',404);report.checks.foreignDenied=true
  for(let index=0;index<2;index++) {
    const run=await api('/chat/runs',{prompt:'你好，请用一句话说明你能提供什么帮助，不要调用工具。',conversationId,
      gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',
      language:'zh-CN',timeoutSeconds:120,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
    active.add(run.runId)
    if(index===0) {
      const next=await api(path,{...settings,revision:1,notes:'之后的新问题请优先详细解释原因。',responseStyle:'DETAILED'},'PUT')
      report.checks.updatedAfterCreation=next.revision===2
    }
    let cursor='0',terminal;const events=[],seen=new Set(),deadline=Date.now()+140000
    while(!terminal && Date.now()<deadline) {
      const page=await api(`/chat/runs/${run.runId}/events?afterEventId=${cursor}`)
      for(const event of page.events||[]) {
        const id=String(event.eventId);if(seen.has(id))continue;seen.add(id);events.push(event);cursor=id
        if(['run.completed','run.failed','run.cancelled','run.timed_out'].includes(event.type))terminal=event
      }
      if(!terminal)await new Promise(done=>setTimeout(done,350))
    }
    if(!terminal)throw Error('Missing terminal event')
    active.delete(run.runId)
    const history=await api(`/chat/conversations/${conversationId}/messages`)
    const pair=history.filter(m=>m.runId===run.runId),user=pair.find(m=>m.role==='user'),answer=pair.find(m=>m.role==='assistant')
    const snapshot=user?.metadata?.conversationConstraints, same=answer?.metadata?.conversationConstraints
    const check=terminal.type==='run.completed' && snapshot?.revision===index+1 && JSON.stringify(snapshot)===JSON.stringify(same)
      && snapshot?.source==='USER_CONFIRMED' && snapshot.notes===(index===0?settings.notes:'之后的新问题请优先详细解释原因。')
    const eventsFile=`run-${index+1}-events.json`;writeFileSync(resolve(output,eventsFile),JSON.stringify(events,null,2)+'\n')
    report.runs.push({runId:run.runId,status:terminal.type,pass:check,eventsFile,eventsSha256:hash(readFileSync(resolve(output,eventsFile))),
      preferenceSnapshot:snapshot,answer:answer?.content??null,usage:terminal.payload?.modelUsage??null})
    if(!check)throw Error('Frozen preference snapshot mismatch')
  }
  const current=await api(path);report.checks.latestRevision=current.revision===2
  await api(`/chat/conversations/${conversationId}`,undefined,'DELETE',204)
  await api(path,undefined,'GET',404);report.checks.deletedInvisible=true
  report.pass=Object.values(report.checks).every(Boolean) && report.runs.length===report.plannedRuns && report.runs.every(r=>r.pass)
} catch(error) {report.error=error.message;report.pass=false}
finally {
  for(const id of active)try{await api(`/chat/runs/${id}/cancel`,{})}catch{report.cleanupFailed=true}
  for(const client of [api,foreign])try{await client('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}
  report.finishedAt=new Date().toISOString();writeFileSync(resolve(output,'scenario.json'),JSON.stringify(report,null,2)+'\n')
}
console.log(JSON.stringify({pass:report.pass,planned:report.plannedRuns,completed:report.runs.length,checks:report.checks,error:report.error}))
if(!report.pass)process.exitCode=1
