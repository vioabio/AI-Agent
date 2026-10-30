import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {validateSnapshot,inspectCache} from '../reference-cache.mjs'
import {digest} from './evaluation.mjs'

const members=[{id:7,name:'squirtle'},{id:478,name:'froslass'}]
const maxBytes=2_000_000
export function inspectDemoBundle(directory){
 try{
  const root=path.resolve(directory),manifestPath=path.join(root,'manifest.json')
  if(!fs.statSync(root).isDirectory()||fs.lstatSync(root).isSymbolicLink()||fs.lstatSync(manifestPath).isSymbolicLink()||fs.statSync(manifestPath).size>65536)throw Error('UNSAFE_BUNDLE')
  const raw=fs.readFileSync(manifestPath),m=JSON.parse(raw)
  const required=members.flatMap(p=>[`pokemon-${p.id}.json`,`pokemon-${p.name}.json`,`pokemon-species-${p.name}.json`])
  if(m.schemaVersion!=='pokemon-reference-bundle-v1'||m.scope!=='PUBLIC_LOCAL_REFERENCE_NOT_FRESH_NETWORK_OR_SIGNATURE'||JSON.stringify(m.queries)!=='["7","478"]'
   ||!Array.isArray(m.files)||m.files.length!==6||new Set(m.files.map(e=>e.file)).size!==6||m.files.some(e=>!required.includes(e.file)))throw Error('UNEXPECTED_DEMO_BUNDLE')
  for(const entry of m.files){
   const file=path.join(root,entry.file),stat=fs.lstatSync(file)
   if(!stat.isFile()||stat.isSymbolicLink()||stat.size>4100000)throw Error('UNSAFE_SNAPSHOT')
   const bytes=fs.readFileSync(file),snapshot=validateSnapshot(bytes,entry.file)
   if(entry.fileSha256!==digest(bytes)||entry.sourceUrl!==snapshot.envelope.url||entry.retrievedAt!==snapshot.envelope.retrievedAt||entry.payloadSha256!==snapshot.envelope.sha256)throw Error('MANIFEST_MISMATCH')
  }
  const report=inspectCache(root,['7','478']);if(!report.ready)throw Error('DEMO_CACHE_INCOMPLETE')
  return {ready:true,files:6,manifestSha256:digest(raw),queries:report.queries,scope:report.scope}
 }catch(error){return {ready:false,code:/^[A-Z_]+$/.test(error.message)?error.message:'DEMO_BUNDLE_UNAVAILABLE'}}
}
function destination(root,output){
 root=fs.realpathSync(root);output=path.resolve(output)
 const rel=path.relative(path.join(root,'artifacts'),output)
 if(!rel||rel.startsWith('..')||path.isAbsolute(rel)||fs.existsSync(output))throw Error('Fresh destination inside workspace artifacts required')
 let parent=path.dirname(output);while(!fs.existsSync(parent))parent=path.dirname(parent)
 const resolved=path.relative(root,fs.realpathSync(parent));if(resolved.startsWith('..')||path.isAbsolute(resolved))throw Error('Destination escapes workspace')
 return output
}
async function download(kind,member,fetcher){
 const key=kind==='pokemon'?String(member.id):member.name
 const url=`https://pokeapi.co/api/v2/${kind}/${key}/`
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000)
 try{
  const response=await fetcher(url,{redirect:'error',signal:controller.signal,headers:{Accept:'application/json'}})
  if(response.status!==200||response.redirected||!/^application\/json\b/i.test(response.headers.get('content-type')||''))throw Error('PUBLIC_RESOURCE_RESPONSE_REJECTED')
  const declared=response.headers.get('content-length');if(declared&&(!/^\d+$/.test(declared)||Number(declared)>maxBytes))throw Error('PUBLIC_RESOURCE_TOO_LARGE')
  if(!response.body)throw Error('PUBLIC_RESOURCE_BODY_MISSING')
  const reader=response.body.getReader(),parts=[];let length=0
  try{for(;;){const {done,value}=await reader.read();if(done)break;length+=value.byteLength;if(length>maxBytes){await reader.cancel();throw Error('PUBLIC_RESOURCE_TOO_LARGE')}parts.push(Buffer.from(value))}}
  finally{reader.releaseLock()}
  const payload=new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(parts)),body=JSON.parse(payload)
  if(body.id!==member.id||body.name!==member.name||kind==='pokemon'&&body.species?.name!==member.name)throw Error('PUBLIC_RESOURCE_IDENTITY_MISMATCH')
  if(kind==='pokemon'&&body.species.url&&!new Set([`https://pokeapi.co/api/v2/pokemon-species/${member.id}/`,`https://pokeapi.co/api/v2/pokemon-species/${member.name}/`]).has(body.species.url))throw Error('PUBLIC_RESOURCE_IDENTITY_MISMATCH')
  const envelope={schemaVersion:1,url,retrievedAt:new Date().toISOString(),sha256:'sha256:'+digest(payload),payload}
  const raw=Buffer.from(JSON.stringify(envelope,null,2)+'\n'),files=kind==='pokemon'?[`pokemon-${member.id}.json`,`pokemon-${member.name}.json`]:[`pokemon-species-${member.name}.json`]
  return files.map(file=>validateSnapshot(raw,file))
 }catch(error){throw Error(/^[A-Z_]+$/.test(error.message)?error.message:'PUBLIC_RESOURCE_DOWNLOAD_FAILED')}
 finally{clearTimeout(timer)}
}

export async function prepareDemoReference(output,{root=process.cwd(),fetcher=globalThis.fetch}={}){
 const target=destination(root,output)
 // Fixed routes, bounded bodies, one request each. Validate every response before writing.
 const results=await Promise.allSettled(members.flatMap(member=>['pokemon','pokemon-species'].map(kind=>download(kind,member,fetcher))))
 const failures=results.filter(r=>r.status==='rejected').map(r=>r.reason.message)
 if(failures.length)throw Error('DEMO_REFERENCE_NOT_READY: '+[...new Set(failures)].join(','))
 const entries=results.flatMap(r=>r.value).sort((a,b)=>a.file.localeCompare(b.file))
 const manifest={schemaVersion:'pokemon-reference-bundle-v1',createdAt:new Date().toISOString(),scope:'PUBLIC_LOCAL_REFERENCE_NOT_FRESH_NETWORK_OR_SIGNATURE',queries:['7','478'],files:entries.map(e=>({file:e.file,fileSha256:e.fileSha256,sourceUrl:e.envelope.url,retrievedAt:e.envelope.retrievedAt,payloadSha256:e.envelope.sha256}))}
 fs.mkdirSync(path.dirname(target),{recursive:true});fs.mkdirSync(target)
 for(const entry of entries)fs.writeFileSync(path.join(target,entry.file),entry.raw,{flag:'wx'})
 fs.writeFileSync(path.join(target,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'})
 return {schemaVersion:'demo-reference-preparation-v1',ready:true,requests:4,automaticRetries:0,publicFiles:entries.length,queries:manifest.queries,manifestSha256:digest(fs.readFileSync(path.join(target,'manifest.json'))),scope:'Fixed public reference snapshots only; no model, credentials, rankings or legality certification'}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [output,...extra]=process.argv.slice(2)
 if(!output||extra.length)throw Error('One fresh artifacts destination required')
 try{console.log(JSON.stringify(await prepareDemoReference(output),null,2))}
 catch(error){console.error(error.message);process.exitCode=1}
}
