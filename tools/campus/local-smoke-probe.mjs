// Infrastructure smoke only: never invokes a model, public reference fetch or write tool.
import fs from 'node:fs'
import {randomBytes} from 'node:crypto'
import {localBase} from './evaluation.mjs'
const [baseArg,output,...extra]=process.argv.slice(2)
if(!baseArg||!output||extra.length||fs.existsSync(output))throw Error('Dedicated base and fresh output required')
const base=localBase(baseArg);if(new URL(base).port!=='18259')throw Error('Only isolated smoke port 18259 allowed')
const cookies=new Map(),report={schemaVersion:'public-workspace-smoke-probe-v1',checks:{},modelRequestsIssued:0,referenceNetworkRequestsIssued:0,scope:'Basic local startup/auth/runtime only; no model quality, restart persistence, frontend browser or clean-machine acceptance'}
let authenticated=false
async function api(route,body,expected=200){
 const r=await fetch(base+route,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(5000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of r.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(r.status!==expected)throw Error(`Unexpected HTTP ${r.status} at ${route}`)
 if(expected===204)return null
 const value=await r.json();return value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
}
try{
 const session=await api('/auth/session');if(session.mode!=='local')throw Error('Local account mode required');report.checks.localMode=true
 const username='smoke_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
 await api('/auth/local/register',{username,password,nickname:'公开源码启动验证'},201);report.checks.registration=true
 await api('/auth/local/login',{username,password});authenticated=true
 const profile=await api('/auth/local/profile');if(profile.username!==username)throw Error('Wrong local profile');report.checks.loginProfile=true
 const runtime=await api('/chat/runtime');if(!runtime.tools?.includes('checkPokemonTeamBuild'))throw Error('Missing local tool catalogue');report.runtimeVersion=runtime.teamBuildCheckPolicy?.answerVersion;report.checks.runtimeAvailable=true
 await api('/teams');report.checks.teamListAvailable=true
 await api('/logout',{},204);authenticated=false
 await api('/auth/session');report.csrfRefreshedAfterLogout=true
 await api('/auth/local/login',{username,password});authenticated=true
 if((await api('/auth/local/profile')).username!==username)throw Error('Relogin profile changed');report.checks.relogin=true
}catch(error){report.error=error.message}
finally{
 if(authenticated)try{await api('/logout',{},204);report.checks.logout=true}catch{report.cleanupFailed=true}
 report.pass=!report.error&&!report.cleanupFailed&&Object.keys(report.checks).length===7&&Object.values(report.checks).every(Boolean)
 fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({pass:report.pass,checks:report.checks,error:report.error,modelRequestsIssued:0}));if(!report.pass)process.exitCode=1
}
