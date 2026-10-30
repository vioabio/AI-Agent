import fs from 'node:fs'
import {randomBytes} from 'node:crypto'
import {localBase,sameJson} from './evaluation.mjs'
const [baseArg,output,...extra]=process.argv.slice(2)
if(!baseArg||!output||extra.length||fs.existsSync(output))throw Error('Isolated base and fresh output required')
const base=localBase(baseArg);if(new URL(base).port!=='18259')throw Error('Only isolated reproduction port 18259 allowed')
const cookies=new Map(),report={schemaVersion:'public-reproduction-probe-v1',checks:{},modelCalls:0,referenceMode:'EXPLICIT_IMPORTED_PUBLIC_CACHE',scope:'One dedicated local account, deterministic HTTP facts/team/engine checks. No agent planning, browser, restart or clean-machine acceptance'}
let loggedIn=false
async function api(route,body,expected=200){
 const response=await fetch(base+route,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of response.headers.getSetCookie()){const[key,...value]=line.split(';')[0].split('=');cookies.set(key,value.join('='))}
 if(response.status!==expected)throw Error(`Unexpected HTTP ${response.status} at ${route}`)
 if(expected===204)return null
 const value=await response.json();return value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
}
try{
 if((await api('/auth/session')).mode!=='local')throw Error('Local accounts required');report.checks.localMode=true
 const username='repro_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
 await api('/auth/local/register',{username,password,nickname:'公开构建复现验证'},201);report.checks.registration=true
 await api('/auth/local/login',{username,password});loggedIn=true
 if((await api('/auth/local/profile')).username!==username)throw Error('Wrong account');report.checks.login=true
 const runtime=await api('/chat/runtime');report.runtime=runtime
 if(runtime.toolInputEvidence?.previewPolicy?.projectionVersion!=='bounded-team-preview-input-v1')throw Error('Unexpected runtime version');report.checks.runtime=true
 const release='pokeapi-reference-v1'
 const squirtle=await api('/pokemon/reference/pokemon/7?dataReleaseId='+release),froslass=await api('/pokemon/reference/pokemon/478?dataReleaseId='+release)
 if(squirtle.fact.formId!==7||froslass.fact.formId!==478||squirtle.fact.dataReleaseId!==release||froslass.fact.dataReleaseId!==release)throw Error('Reference identity mismatch');report.checks.referenceFacts=true
 const build={moves:['surf'],ability:'torrent',item:'leftovers',nature:'modest',level:50,evs:{hp:4,atk:0,def:0,spa:252,spd:0,spe:252},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}}
 const team=await api('/teams',{name:'公开构建复现队伍',rulesetId:'casual-v1',dataReleaseId:release,members:[{formId:7,slot:1,build}]},201)
 const stored=await api('/teams/'+team.id)
 if(!sameJson(team,stored)||!sameJson(stored.members[0].build,build))throw Error('Team readback mismatch');report.checks.teamSaveReadback=true
 const damage=await api('/pokemon/calculations/engine-damage',{generation:9,dataReleaseId:release,attacker:{species:'squirtle',build},defender:{species:'froslass',build:{level:50}},move:'surf',field:{gameType:'Singles',weather:null,terrain:null}})
 if(damage.engineVersion!=='0.12.0'||!(damage.range?.minimum>0)||damage.range.maximum<damage.range.minimum)throw Error('Invalid engine result')
 report.engineRange=damage.range;report.engineVersion=damage.engineVersion;report.checks.engineBridge=true
 await api('/logout',{},204);loggedIn=false;await api('/auth/session')
 await api('/auth/local/login',{username,password});loggedIn=true
 if(!sameJson(await api('/teams/'+team.id),team))throw Error('Relogin readback mismatch');report.checks.reloginTeamReadback=true
}catch(error){report.error=error.message}
finally{
 if(loggedIn)try{await api('/logout',{},204);report.checks.logout=true}catch{report.cleanupFailed=true}
 report.pass=!report.error&&!report.cleanupFailed&&Object.keys(report.checks).length===9&&Object.values(report.checks).every(Boolean)
 fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({pass:report.pass,checks:report.checks,error:report.error,modelCalls:0}));if(!report.pass)process.exitCode=1
}
