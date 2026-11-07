// Cache-only tooling: fixed public snapshot format, explicit queries, no network or account files.
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
const slug=/^[a-z0-9][a-z0-9-]{0,79}$/
const filename=/^pokemon-(?:species-)?[a-z0-9][a-z0-9-]{0,79}\.json$/
const sha=value=>createHash('sha256').update(value).digest('hex')
const fail=(code,file)=>{const error=Error(code);if(file)error.file=file;throw error}
function timestamp(value) {
 const m=typeof value==='string'?/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/.exec(value):null
 if(!m||!Number.isFinite(Date.parse(value)))return false
 const[y,month,day,hour,minute,second]=m.slice(1,7).map(Number),days=[31,y%4===0&&(y%100!==0||y%400===0)?29:28,31,30,31,30,31,31,30,31,30,31]
 return month>=1&&month<=12&&day>=1&&day<=days[month-1]&&hour<=23&&minute<=59&&second<=59
}
function queries(value) {
 const list=Array.isArray(value)?value:String(value||'').split(',')
 if(!list.length||list.length>6||list.some(v=>typeof v!=='string'||!slug.test(v))||new Set(list).size!==list.length)fail('INVALID_QUERY_LIST')
 return list
}
function directory(value) {
 const root=path.resolve(value)
 if(!fs.existsSync(root))fail('CACHE_DIRECTORY_MISSING')
 const stat=fs.lstatSync(root);if(!stat.isDirectory()||stat.isSymbolicLink())fail('UNSAFE_DIRECTORY')
 return root
}
function read(root,file,maximum=4100000) {
 if(!filename.test(file)&&file!=='manifest.json')fail('UNSAFE_FILE_NAME')
 const target=path.join(root,file)
 if(!fs.existsSync(target))fail('SNAPSHOT_MISSING',file)
 const stat=fs.lstatSync(target);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>maximum)fail('UNSAFE_OR_OVERSIZED_FILE')
 return fs.readFileSync(target)
}
function identity(key,body){return /^\d+$/.test(key)?Number(key)===body.id:key===body.name}
export function validateSnapshot(raw,file) {
 if(!filename.test(file))fail('UNSAFE_FILE_NAME')
 if(raw.length>4100000)fail('SNAPSHOT_TOO_LARGE')
 let envelope,body
 try{envelope=JSON.parse(raw);body=JSON.parse(envelope.payload)}catch{fail('INVALID_SNAPSHOT_JSON')}
 if(!envelope||Array.isArray(envelope)||Object.keys(envelope).sort().join(',')!=='payload,retrievedAt,schemaVersion,sha256,url'||envelope.schemaVersion!==1
   ||typeof envelope.payload!=='string'||Buffer.byteLength(envelope.payload)>2000000||envelope.sha256!=='sha256:'+sha(envelope.payload)
   ||!timestamp(envelope.retrievedAt))fail('SNAPSHOT_INTEGRITY_FAILED')
 if(!body||!Number.isSafeInteger(body.id)||body.id<1||body.id>2147483647||!slug.test(body.name||''))fail('INVALID_RESOURCE_IDENTITY')
 const kind=file.startsWith('pokemon-species-')?'pokemon-species':'pokemon',key=file.slice(kind.length+1,-5)
 const url=typeof envelope.url==='string'?/^https:\/\/pokeapi\.co\/api\/v2\/(pokemon|pokemon-species)\/([a-z0-9-]+)\/$/.exec(envelope.url):null
 if(!url||url[1]!==kind||!identity(key,body)||!identity(url[2],body)||kind==='pokemon-species'&&url[2]!==key)fail('SOURCE_IDENTITY_MISMATCH')
 if(kind==='pokemon') {
  const stats=new Set(['hp','attack','defense','special-attack','special-defense','speed'])
  const types=new Set(['normal','fire','water','electric','grass','ice','fighting','poison','ground','flying','psychic','bug','rock','ghost','dragon','dark','steel','fairy'])
  if(!slug.test(body.species?.name||'')||!Array.isArray(body.stats)||body.stats.length!==6
    ||new Set(body.stats.map(s=>s.stat?.name)).size!==6||body.stats.some(s=>!stats.has(s.stat?.name)||!Number.isInteger(s.base_stat)||s.base_stat<1||s.base_stat>10000)
    ||!Array.isArray(body.types)||body.types.length<1||body.types.length>2||body.types.some(t=>!types.has(t.type?.name))
    ||!Array.isArray(body.abilities)||!body.abilities.length||body.abilities.some(a=>!slug.test(a.ability?.name||'')))fail('INCOMPLETE_POKEMON_PAYLOAD')
 }else if(!/^generation-(?:i|ii|iii|iv|v|vi|vii|viii|ix)$/.test(body.generation?.name||''))fail('UNSUPPORTED_SPECIES_GENERATION')
 return {raw,envelope,body,file,kind,fileSha256:sha(raw)}
}
function load(root,file){try{return validateSnapshot(read(root,file),file)}catch(error){error.file=file;throw error}}
function pair(root,query) {
 const pokemon=load(root,`pokemon-${query}.json`),species=load(root,`pokemon-species-${pokemon.body.species.name}.json`)
 if(pokemon.body.species.name!==species.body.name)fail('SPECIES_LINK_MISMATCH')
 if(pokemon.body.species.url&&!new Set([`https://pokeapi.co/api/v2/pokemon-species/${species.body.id}/`,`https://pokeapi.co/api/v2/pokemon-species/${species.body.name}/`]).has(pokemon.body.species.url))fail('SPECIES_LINK_MISMATCH')
 const aliases=[...new Set([query,String(pokemon.body.id),pokemon.body.name])],missing=[]
 for(const alias of aliases) {
  const file=`pokemon-${alias}.json`
  if(!fs.existsSync(path.join(root,file))){missing.push(file);continue}
  const existing=load(root,file);if(existing.envelope.sha256!==pokemon.envelope.sha256)fail('CONFLICTING_ALIAS_SNAPSHOT')
 }
 return {pokemon,species,aliases,missing}
}
export function inspectCache(cache,selection) {
 const selected=queries(selection),rows=[];let root
 try{root=directory(cache)}catch(error){return {schemaVersion:'reference-cache-report-v1',mode:'CACHE_ONLY_NO_NETWORK',ready:false,queries:selected.map(query=>({query,status:error.message==='CACHE_DIRECTORY_MISSING'?'MISSING':'INVALID',code:/^[A-Z_]+$/.test(error.message)?error.message:'CACHE_READ_FAILED'}))}}
 for(const query of selected)try {
  const p=pair(root,query)
  rows.push({query,status:p.missing.length?'ALIAS_MISSING':'READY',id:p.pokemon.body.id,name:p.pokemon.body.name,species:p.species.body.name,
   sourceUrl:p.pokemon.envelope.url,retrievedAt:p.pokemon.envelope.retrievedAt,payloadSha256:p.pokemon.envelope.sha256,missingAliases:p.missing})
 }catch(error){rows.push({query,status:error.message==='SNAPSHOT_MISSING'?'MISSING':'INVALID',code:/^[A-Z_]+$/.test(error.message)?error.message:'CACHE_READ_FAILED',...(filename.test(error.file)?{files:[error.file]}:{})})}
 return {schemaVersion:'reference-cache-report-v1',mode:'CACHE_ONLY_NO_NETWORK',ready:rows.every(r=>r.status==='READY'),queries:rows,
  scope:'Selected Pokemon and species snapshots only; not all move/item resources, freshness, legality or an offline language model'}
}
function newDestination(value) {
 const target=path.resolve(value);if(fs.existsSync(target))fail('DESTINATION_EXISTS')
 directory(path.dirname(target));return target
}
function selectedFiles(root,selection) {
 const files=new Map()
 for(const query of selection){const p=pair(root,query)
  for(const alias of p.aliases){const name=`pokemon-${alias}.json`,entry=fs.existsSync(path.join(root,name))?load(root,name):validateSnapshot(p.pokemon.raw,name)
   if(files.has(name)&&files.get(name).envelope.sha256!==entry.envelope.sha256)fail('CONFLICTING_ALIAS_SNAPSHOT');files.set(name,entry)}
  files.set(p.species.file,p.species)
 }
 return files
}
const metadata=entry=>({file:entry.file,fileSha256:entry.fileSha256,sourceUrl:entry.envelope.url,retrievedAt:entry.envelope.retrievedAt,payloadSha256:entry.envelope.sha256})
export function exportBundle(cache,selection,output) {
 const selected=queries(selection),root=directory(cache),target=newDestination(output),files=selectedFiles(root,selected)
 const entries=[...files.values()].sort((a,b)=>a.file.localeCompare(b.file)),manifest={schemaVersion:'pokemon-reference-bundle-v1',createdAt:new Date().toISOString(),
  scope:'PUBLIC_LOCAL_REFERENCE_NOT_FRESH_NETWORK_OR_SIGNATURE',queries:selected,files:entries.map(metadata)}
 // All selected snapshots validate before any destination is created; never overwrite or delete.
 fs.mkdirSync(target);for(const entry of entries)fs.writeFileSync(path.join(target,entry.file),entry.raw,{flag:'wx'})
 fs.writeFileSync(path.join(target,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'})
 return {schemaVersion:manifest.schemaVersion,queries:selected,files:entries.length,manifestSha256:sha(fs.readFileSync(path.join(target,'manifest.json')))}
}
export function importBundle(bundle,output) {
 const root=directory(bundle),target=newDestination(output),manifestRaw=read(root,'manifest.json',65536);let manifest
 try{manifest=JSON.parse(manifestRaw)}catch{fail('INVALID_BUNDLE_MANIFEST')}
 if(!manifest||Object.keys(manifest).sort().join(',')!=='createdAt,files,queries,schemaVersion,scope'||!timestamp(manifest.createdAt)
   ||manifest.schemaVersion!=='pokemon-reference-bundle-v1'||manifest.scope!=='PUBLIC_LOCAL_REFERENCE_NOT_FRESH_NETWORK_OR_SIGNATURE'
   ||!Array.isArray(manifest.files)||!manifest.files.length||manifest.files.length>24||new Set(manifest.files.map(f=>f.file)).size!==manifest.files.length)fail('INVALID_BUNDLE_MANIFEST')
 const selected=queries(manifest.queries),entries=[]
 for(const file of manifest.files){const entry=load(root,file.file),expected=metadata(entry);if(Object.keys(file).length!==5||Object.keys(expected).some(key=>expected[key]!==file[key]))fail('BUNDLE_FILE_MISMATCH');entries.push(entry)}
 const required=selectedFiles(root,selected)
 if(required.size!==entries.length||entries.some(e=>!required.has(e.file)))fail('UNEXPECTED_BUNDLE_FILE')
 // Bundle closure must contain both canonical aliases; generation of missing aliases is export-only.
 if(!inspectCache(root,selected).ready)fail('INCOMPLETE_BUNDLE')
 fs.mkdirSync(target);for(const entry of entries)fs.writeFileSync(path.join(target,entry.file),entry.raw,{flag:'wx'})
 return {...inspectCache(target,selected),importedFiles:entries.length,bundleManifestSha256:sha(manifestRaw)}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 try {
  const[command,...args]=process.argv.slice(2),options={}
  for(let i=0;i<args.length;i+=2){const key=args[i];if(!['--cache','--pokemon','--out','--bundle','--output'].includes(key)||!args[i+1]||Object.hasOwn(options,key))fail('INVALID_ARGUMENTS');options[key]=args[i+1]}
  const allowed={inspect:['--cache','--pokemon','--output'],export:['--cache','--pokemon','--out','--output'],import:['--bundle','--out','--output']}[command]
  if(!allowed||Object.keys(options).some(key=>!allowed.includes(key)))fail('INVALID_ARGUMENTS')
  let report
  if(command==='inspect'&&options['--cache']&&options['--pokemon'])report=inspectCache(options['--cache'],options['--pokemon'])
  else if(command==='export'&&options['--cache']&&options['--pokemon']&&options['--out'])report=exportBundle(options['--cache'],options['--pokemon'],options['--out'])
  else if(command==='import'&&options['--bundle']&&options['--out'])report=importBundle(options['--bundle'],options['--out'])
  else fail('INVALID_ARGUMENTS')
  const text=JSON.stringify(report,null,2)+'\n';if(options['--output'])fs.writeFileSync(path.resolve(options['--output']),text,{flag:'wx'});process.stdout.write(text)
  if(command==='inspect'&&!report.ready)process.exitCode=2
 }catch(error){process.stderr.write(`Reference cache operation failed: ${/^[A-Z_]+$/.test(error.message)?error.message:'FILE_OPERATION_FAILED'}\n`);process.exitCode=1}
}
