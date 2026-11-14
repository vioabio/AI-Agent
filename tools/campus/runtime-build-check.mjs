import {randomBytes,createHash} from 'node:crypto'
import {readFileSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {localBase} from './evaluation.mjs'
import {coreComponents,additionalComponents,componentManifestVersion,componentManifestSha256} from './runtime-component-manifest.mjs'
const [url,classes,output]=process.argv.slice(2)
if(!classes||!output)throw Error('Usage: runtime-build-check.mjs http://127.0.0.1:8123/api EXPECTED_CLASSES OUTPUT_JSON')
const base=localBase(url),cookies=new Map();let authenticated=false
const report={schemaVersion:'runtime-artifact-comparison-v3',componentManifestVersion,componentManifestSha256,status:'UNVERIFIED',classes:[],note:'Resource hashes compare artifacts; they do not attest JVM redefinition or hot reload.'}
async function api(path,body,expected=200){
 const r=await fetch(base+path,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),...(cookies.has('CSRF-TOKEN')?{'X-CSRF-TOKEN':decodeURIComponent(cookies.get('CSRF-TOKEN'))}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of r.headers.getSetCookie()){const [k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(r.status!==expected)throw Error('HTTP_'+r.status)
 if(r.status===204)return null
 const j=await r.json();return j&&Object.hasOwn(j,'data')&&Object.hasOwn(j,'code')?j.data:j
}
try{
 if((await api('/auth/session')).mode!=='local')throw Error('LOCAL_ACCOUNT_REQUIRED')
 const credentials={username:'bc_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'构建验证'}
 await api('/auth/local/register',credentials,201);await api('/auth/local/login',credentials);authenticated=true
 const runtime=await api('/chat/runtime')
 for(const [name,folder] of coreComponents){
  const expected=createHash('sha256').update(readFileSync(resolve(classes,'com/vio/vioaiagent',folder,name+'.class'))).digest('hex')
  const actual=runtime.implementationSha256?.[name]
  report.classes.push({name,expected,actual:typeof actual==='string'&&/^[a-f0-9]{64}$/.test(actual)?actual:null,matches:expected===actual})
 }
 report.additionalComponents=[]
 for(const [name,folder] of additionalComponents){
  const expected=createHash('sha256').update(readFileSync(resolve(classes,'com/vio/vioaiagent',folder,name+'.class'))).digest('hex')
  const actual=runtime.implementationSha256?.[name]
  report.additionalComponents.push({name,expected,actual:typeof actual==='string'&&/^[a-f0-9]{64}$/.test(actual)?actual:null,matches:expected===actual})
 }
 report.status=[...report.classes,...report.additionalComponents].every(x=>x.matches)?'ARTIFACTS_MATCH':'STALE_OR_DIFFERENT_ARTIFACTS'
}catch(error){report.status='CHECK_BLOCKED';report.reason=/^(HTTP_[0-9]{3}|LOCAL_ACCOUNT_REQUIRED)$/.test(error.message)?error.message:'BUILD_CHECK_FAILED'}
finally{if(authenticated)try{await api('/logout',{},204);report.cleanup='LOGGED_OUT'}catch{report.cleanup='LOGOUT_UNVERIFIED';report.status='CHECK_BLOCKED'}writeFileSync(resolve(output),JSON.stringify(report,null,2)+'\n')}
console.log(JSON.stringify({status:report.status,classes:[...report.classes,...(report.additionalComponents||[])].map(x=>({name:x.name,matches:x.matches}))}))
process.exitCode=report.status==='ARTIFACTS_MATCH'?0:report.status==='STALE_OR_DIFFERENT_ARTIFACTS'?1:2
