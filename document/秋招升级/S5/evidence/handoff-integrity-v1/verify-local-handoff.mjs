import fs from 'node:fs'
import path from 'node:path'
import {createHash} from 'node:crypto'
import {fileURLToPath} from 'node:url'
const helpers=['tools/reference-cache.mjs','tools/campus/demo-readiness.mjs','tools/campus/prepare-demo-reference.mjs','tools/campus/evaluation.mjs','tools/campus/download-demo-via-proxy.mjs','tools/campus/verify-local-handoff.mjs']
const extraNames=new Set([...helpers,'settings/public-user-settings.xml','settings/public-global-settings.xml','HANDOFF.md','SMOKE.md','VERIFY.md','reference-bundle/manifest.json',...['7','squirtle','478','froslass'].map(k=>'reference-bundle/pokemon-'+k+'.json'),...['squirtle','froslass'].map(k=>'reference-bundle/pokemon-species-'+k+'.json')])
const safe=file=>typeof file==='string'&&file.length<500&&!file.includes('\\')&&!file.includes(':')&&!path.isAbsolute(file)&&file.split('/').every(p=>p&&p!=='.'&&p!=='..')
const privatePath=file=>/(^|\/)(node_modules|target|dist|\.local-data|\.git|logs)(\/|$)|(^|\/)(\.env(?:\.|$)|\.npmrc$|\.netrc$)|\.(sqlite(?:-wal|-shm)?|db|pem|key|pfx|log|jsonl)$/i.test(file)||/(^|\/)application[^/]*\.(yml|yaml|properties)$/i.test(file)&&!['src/main/resources/application.yml','src/main/resources/application-local.example.yml'].includes(file)
const publicSource=file=>['pom.xml','mvnw','mvnw.cmd'].includes(file)||['src/main/','.mvn/wrapper/','evaluation/v1.1/','tools/pokemon-engine/','vio-ai-agent-frontend/src/','vio-ai-agent-frontend/public/'].some(p=>file.startsWith(p))||/^vio-ai-agent-frontend\/(package(?:-lock)?\.json|index\.html|vite(?:\.demo)?\.config\.js|tsconfig[^/]*\.json)$/.test(file)
const sha=bytes=>createHash('sha256').update(bytes).digest('hex')
export function verifyLocalHandoff(directory){
 const result={schemaVersion:'local-handoff-verification-v1',cleanPackage:false,checkedFiles:0,failures:[],modelCalls:0,filesModified:false,authenticityVerified:false,installationVerified:false,scope:'Read-only clean input/hash check against unsigned local manifests; not authenticity, installation or model acceptance'}
 const fail=(code,file)=>{result.failures.push({code,...(file?{file}:{})})}
 try{
  const root=path.resolve(directory)
  if(fs.lstatSync(root).isSymbolicLink()||!fs.statSync(root).isDirectory())throw Error('UNSAFE_ROOT')
  const load=file=>{const stat=fs.lstatSync(path.join(root,file));if(!stat.isFile()||stat.isSymbolicLink()||stat.size>1000000)throw Error('UNSAFE_MANIFEST');return fs.readFileSync(path.join(root,file))}
  const raw=load('smoke-source-manifest.json'),source=JSON.parse(raw),handoff=JSON.parse(load('handoff-manifest.json'))
  if(source.schemaVersion!=='public-source-smoke-workspace-v1'||handoff.schemaVersion!=='local-handoff-v2'||source.privateConfigCopied!==false||source.existingDatabasesCopied!==false||source.existingBuildOutputsCopied!==false||handoff.privateConfigCopied!==false||handoff.existingDatabasesCopied!==false||handoff.dependenciesOrBuildOutputsCopied!==false)throw Error('MANIFEST_SCOPE_REJECTED')
  if(sha(raw)!==handoff.sourceManifestSha256)throw Error('SOURCE_MANIFEST_CHANGED')
  const entries=new Map()
  for(const [kind,map] of [['source',source.sourceSha256],['extra',handoff.extraSha256]]){
   if(!map||typeof map!=='object'||Array.isArray(map)||Object.keys(map).length>10000)throw Error('INVALID_FILE_MAP')
   for(const [file,hash] of Object.entries(map)){
    if(!safe(file)||privatePath(file)||(kind==='source'?!publicSource(file):!extraNames.has(file))||entries.has(file)||!/^[a-f0-9]{64}$/.test(hash))throw Error('UNSAFE_DECLARED_INPUT')
    entries.set(file,hash)
   }
  }
  if(handoff.sourceFiles!==Object.keys(source.sourceSha256).length||extraNames.size!==Object.keys(handoff.extraSha256).length||[...extraNames].some(file=>!entries.has(file)))throw Error('INCOMPLETE_DECLARED_INPUTS')
  for(const file of ['pom.xml','mvnw.cmd','src/main/resources/application.yml','src/main/resources/application-local.example.yml','vio-ai-agent-frontend/package.json','tools/pokemon-engine/package.json'])if(!entries.has(file))throw Error('INCOMPLETE_SOURCE_INPUTS')
  const discovered=new Set();let count=0
  function walk(folder,prefix=''){
   for(const item of fs.readdirSync(folder,{withFileTypes:true})){
    if(++count>15000)throw Error('TOO_MANY_ENTRIES')
    const file=prefix+item.name,full=path.join(folder,item.name)
    if(!safe(file)||privatePath(file)){fail('PRIVATE_OR_RUNTIME_STATE_PRESENT');continue}
    if(item.isSymbolicLink()){fail('SYMLINK_PRESENT');continue}
    if(item.isDirectory()){walk(full,file+'/');continue}
    if(!item.isFile()){fail('UNSUPPORTED_ENTRY');continue}
    if(['smoke-source-manifest.json','handoff-manifest.json'].includes(file))continue
    if(!entries.has(file)){fail('UNDECLARED_FILE_PRESENT');continue}
    discovered.add(file)
   }
  }
  walk(root)
  // Never read arbitrary undeclared files or follow a link to inspect its contents.
  for(const [file,hash] of entries){
   if(!discovered.has(file)){fail('DECLARED_FILE_MISSING',file);continue}
   if(fs.statSync(path.join(root,file)).size>10000000){fail('DECLARED_FILE_TOO_LARGE',file);continue}
   result.checkedFiles++
   if(sha(fs.readFileSync(path.join(root,file)))!==hash)fail('FILE_HASH_MISMATCH',file)
  }
  result.cleanPackage=result.failures.length===0
 }catch(error){fail(new Set(['UNSAFE_ROOT','UNSAFE_MANIFEST','MANIFEST_SCOPE_REJECTED','SOURCE_MANIFEST_CHANGED','INVALID_FILE_MAP','UNSAFE_DECLARED_INPUT','INCOMPLETE_DECLARED_INPUTS','INCOMPLETE_SOURCE_INPUTS','TOO_MANY_ENTRIES']).has(error.message)?error.message:'PACKAGE_INSPECTION_FAILED')}
 return result
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [directory=process.cwd(),...extra]=process.argv.slice(2);if(extra.length)throw Error('One handoff directory required')
 const report=verifyLocalHandoff(directory);console.log(JSON.stringify(report,null,2));if(!report.cleanPackage)process.exitCode=2
}
