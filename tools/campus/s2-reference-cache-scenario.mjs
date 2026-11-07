// Public-cache round trip and one expected reference-failure Run. Never stores credentials or confirms writes.
import fs from 'node:fs'
import path from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { localBase, digest, sameJson } from './evaluation.mjs'
import { inspectCache, validateSnapshot } from '../reference-cache.mjs'
import { attemptsFrom } from './answer-quality.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
const [baseArg,bundleArg,outArg,...extra]=process.argv.slice(2)
if(!baseArg||!bundleArg||!outArg||extra.length)throw Error('Usage: node tools/campus/s2-reference-cache-scenario.mjs LOCAL_API PUBLIC_BUNDLE NEW_OUTPUT')
const base=localBase(baseArg),bundle=path.resolve(bundleArg),out=path.resolve(outArg),manifest=JSON.parse(fs.readFileSync(path.join(bundle,'manifest.json')))
if(fs.existsSync(out))throw Error('Never overwrite evidence');fs.mkdirSync(out,{recursive:true})
if(!inspectCache(bundle,manifest.queries).ready)throw Error('Bundle not ready')
const report={schemaVersion:'reference-cache-runtime-v1',scope:'Cache readability, direct HTTP calculation and one expected failure-recovery answer; not a successful model calculation or semantic accuracy benchmark',startedAt:new Date().toISOString(),manifestSha256:digest(fs.readFileSync(path.join(bundle,'manifest.json'))),checks:{},collectorSha256:digest(fs.readFileSync(fileURLToPath(import.meta.url))),humanAcceptance:'NOT_PERFORMED'},cookies=new Map()
async function api(route,body,method=body===undefined?'GET':'POST',status=200) {
 const response=await fetch(base+route,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of response.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(response.status!==status)throw Error(`HTTP ${response.status}; expected ${status}`)
 const raw=await response.text(),value=raw?JSON.parse(raw):null
 return status<400&&value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
}
let active;const events=[]
try {
 if((await api('/auth/session')).mode!=='local')throw Error('Dedicated local mode required')
 const username='cache_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
 await api('/auth/local/register',{username,password,nickname:'本地资料回归'},'POST',201);await api('/auth/local/login',{username,password})
 report.runtime=await api('/chat/runtime');report.sourceSha256={}
 function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.resolve(dir,e.name);if(e.isDirectory())walk(f);else if(f.endsWith('.java'))report.sourceSha256[path.relative(process.cwd(),f).replaceAll('\\','/')]=digest(fs.readFileSync(f))}}
 walk(path.resolve('src/main/java'));for(const f of ['pom.xml','tools/reference-cache.mjs','tools/warm-pokemon-reference.ps1','tools/campus/s2-reference-cache-scenario.mjs','tools/pokemon-engine/calculate.cjs','tools/pokemon-engine/package-lock.json'])report.sourceSha256[f]=digest(fs.readFileSync(f))
 for(const[name,expected]of Object.entries(report.runtime.implementationSha256)){const f=Object.keys(report.sourceSha256).find(s=>s.endsWith('/'+name+'.java'));if(!f||digest(fs.readFileSync(f.replace('src/main/java/','target/classes/').replace(/\.java$/,'.class')))!==expected)throw Error('Runtime class mismatch')}
 report.facts=[]
 for(const query of ['squirtle','7','杰尼龟','charmander','4']) {
  const result=await api('/pokemon/reference/pokemon/'+encodeURIComponent(query)+'?dataReleaseId=pokeapi-reference-v1'),slug=result.fact.slug
  const p=validateSnapshot(fs.readFileSync(path.join(bundle,`pokemon-${slug}.json`)),`pokemon-${slug}.json`),s=validateSnapshot(fs.readFileSync(path.join(bundle,`pokemon-species-${p.body.species.name}.json`)),`pokemon-species-${p.body.species.name}.json`)
  if(result.fact.checksum!=='sha256:'+digest(p.envelope.sha256+s.envelope.sha256))throw Error('Restored fact checksum mismatch')
  report.facts.push({query,slug,checksum:result.fact.checksum,retrievedAt:result.retrievedAt})
 }
 report.checks.cachedNamesIdsAndChinese=true
 const request={generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle',build:null},defender:{species:'charmander',build:null},move:'surf',field:null}
 report.calculation=await api('/pokemon/calculations/engine-damage',request)
 report.checks.importedCacheCalculation=report.calculation.conditionValidation?.status==='VERIFIED'&&report.calculation.range?.minimum>0
 const missing=await api('/pokemon/reference/pokemon/blastoise?dataReleaseId=pokeapi-reference-v1',undefined,'GET',503)
 report.checks.uncachedReferenceFailsSafely=missing.code===503&&!JSON.stringify(missing).includes('127.0.0.1')
 request.attacker.species='blastoise'
 const prompt='请直接调用calculateBattleDamage，按以下JSON计算；参考资料不可用时说明如何恢复，不猜测伤害，也不要让我重复填写已经提供的参数：\n'+JSON.stringify(request)
 const conversationId='cache-'+randomBytes(12).toString('hex'),run=await api('/chat/runs',{prompt,conversationId,gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',language:'zh-CN',timeoutSeconds:90,idempotencyKey:randomBytes(16).toString('hex')},'POST',202)
 active=run.runId;report.runId=active;const seen=new Set(),deadline=Date.now()+110000;let cursor='0',done=false
 while(!done&&Date.now()<deadline){const page=await api(`/chat/runs/${active}/events?afterEventId=${cursor}`);for(const e of page.events||[]){const id=String(e.eventId);if(seen.has(id))continue;seen.add(id);events.push(e);cursor=id;if(['run.completed','run.failed','run.timed_out','run.cancelled','approval.required'].includes(e.type)){report.terminal=e.type;done=true}}if(!done)await new Promise(r=>setTimeout(r,350))}
 if(!done||report.terminal!=='run.completed')throw Error('Expected recovery answer not completed');active=null
 report.answer=events.filter(e=>e.type==='answer.delta').map(e=>e.payload?.text||'').join('')
 const messages=await api(`/chat/conversations/${conversationId}/messages`),answer=messages.find(m=>m.role==='assistant'&&m.runId===run.runId)
 const attempts=attemptsFrom(events);report.modelCalls=attempts.reduce((n,a)=>n+a.modelCallCount,0)
 report.checks.historyAndUsageMatch=answer?.content===report.answer&&sameJson(attempts.map(normalizeModelUsage),(answer?.metadata?.modelUsageAttempts||[]).map(normalizeModelUsage))
 report.checks.referenceFailureRecovery=report.answer.includes('参考资料暂时无法读取')&&!report.answer.includes('补齐双方')&&!report.answer.includes('| 伤害范围 |')&&events.some(e=>e.type==='tool.finished'&&e.payload?.status==='FAILED'&&['lookupPokemonReference','lookupPokemonResource','lookupPokemonTeamReference','calculateBattleDamage'].includes(e.payload.toolName))
 report.pass=Object.values(report.checks).every(v=>v===true)
}catch(error){report.pass=false;report.error=error.message}
finally {
 if(active)try{await api(`/chat/runs/${active}/cancel`,{})}catch{report.cleanupFailed=true}
 try{await api('/logout',undefined,'POST',204)}catch{report.cleanupFailed=true}
 const raw=JSON.stringify(events,null,2)+'\n';fs.writeFileSync(path.join(out,'events.json'),raw);report.eventsSha256=digest(raw);report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n')
}
console.log(JSON.stringify({pass:report.pass,checks:report.checks,modelCalls:report.modelCalls,error:report.error||null}))
if(!report.pass||report.cleanupFailed)process.exitCode=1
