import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {prepareDemoReference,inspectDemoBundle} from './prepare-demo-reference.mjs'
import {inspectDemoReadiness} from './demo-readiness.mjs'
import {importBundle,inspectCache} from '../reference-cache.mjs'
import {smokeSourceAllowed} from './local-smoke-workspace.mjs'
function workspace(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'demo-delivery-'))
 t.after(()=>{if(path.dirname(path.resolve(root))!==path.resolve(os.tmpdir())||!path.basename(root).startsWith('demo-delivery-'))throw Error('Unexpected temporary root');fs.rmSync(root,{recursive:true,force:true})})
 return root
}
function resource(url){
 const id=url.includes('/7/')||url.includes('/squirtle/')?7:478,name=id===7?'squirtle':'froslass'
 if(url.includes('/pokemon-species/'))return {id,name,generation:{name:'generation-i'}}
 return {id,name,species:{name,url:`https://pokeapi.co/api/v2/pokemon-species/${id}/`},types:[{type:{name:'water'}}],abilities:[{ability:{name:'torrent'}}],stats:['hp','attack','defense','special-attack','special-defense','speed'].map(name=>({stat:{name},base_stat:40}))}
}
const transport=async url=>new Response(JSON.stringify(resource(url)),{headers:{'content-type':'application/json'}})
const target=root=>path.join(root,'artifacts','bundle')
async function bundle(t){const root=workspace(t),out=target(root);await prepareDemoReference(out,{root,fetcher:transport});return {root,out}}
function dependencies(root){
 for(const file of ['pom.xml','mvnw.cmd','.mvn/wrapper/maven-wrapper.properties']){fs.mkdirSync(path.dirname(path.join(root,file)),{recursive:true});fs.writeFileSync(path.join(root,file),'public fixture')}
 for(const [base,name]of [['vio-ai-agent-frontend','vite'],['tools/pokemon-engine','@smogon/calc']]){
  fs.mkdirSync(path.join(root,base,'node_modules',name),{recursive:true})
  fs.writeFileSync(path.join(root,base,'package-lock.json'),JSON.stringify({packages:{['node_modules/'+name]:{version:'1.2.3'}}}))
  fs.writeFileSync(path.join(root,base,'node_modules',name,'package.json'),JSON.stringify({version:'1.2.3'}))
 }
}
const probe=(file)=>file.includes('javac')?'javac 21.0.1':'openjdk version "21.0.1"'
test('fixed public download creates a six-file bundle accepted by the existing importer',async t=>{
 const root=workspace(t),seen=[]
 const report=await prepareDemoReference(target(root),{root,fetcher:async(url,options)=>{seen.push(url);assert.equal(options.redirect,'error');assert.deepEqual(options.headers,{Accept:'application/json'});return transport(url)}})
 assert.equal(report.requests,4);assert.equal(report.publicFiles,6);assert.equal(new Set(seen).size,4);assert.equal(inspectDemoBundle(target(root)).ready,true)
 const imported=path.join(root,'cache');assert.equal(importBundle(target(root),imported).ready,true);assert.equal(inspectCache(imported,['7','478']).ready,true)
})
test('wrong resource identity fails before creating the destination',async t=>{
 const root=workspace(t)
 await assert.rejects(prepareDemoReference(target(root),{root,fetcher:async url=>new Response(JSON.stringify({...resource(url),id:872}),{headers:{'content-type':'application/json'}})}),/IDENTITY_MISMATCH/)
 assert.equal(fs.existsSync(target(root)),false)
})
test('redirects and non-JSON responses never create usable reference data',async t=>{
 for(const response of [()=>new Response('',{status:302,headers:{location:'http://127.0.0.1'}}),()=>new Response('<html>private</html>',{headers:{'content-type':'text/html'}})]){
  const root=workspace(t);await assert.rejects(prepareDemoReference(target(root),{root,fetcher:async()=>response()}),/RESPONSE_REJECTED/);assert.equal(fs.existsSync(target(root)),false)
 }
})
test('declared and streamed oversized bodies are bounded without writing partial bundles',async t=>{
 for(const response of [()=>new Response('{}',{headers:{'content-type':'application/json','content-length':'2000001'}}),()=>new Response('x'.repeat(2000001),{headers:{'content-type':'application/json'}})]){
  const root=workspace(t);await assert.rejects(prepareDemoReference(target(root),{root,fetcher:async()=>response()}),/TOO_LARGE/);assert.equal(fs.existsSync(target(root)),false)
 }
})
test('network failures retain a sanitized error and have no automatic retries',async t=>{
 const root=workspace(t);let calls=0
 await assert.rejects(prepareDemoReference(target(root),{root,fetcher:async()=>{calls++;throw Error('private-transport-secret')}}),error=>!error.message.includes('private-transport-secret')&&error.message.includes('DOWNLOAD_FAILED'))
 assert.equal(calls,4);assert.equal(fs.existsSync(target(root)),false)
})
test('existing and outside destinations are rejected before fetching',async t=>{
 const root=workspace(t);fs.mkdirSync(target(root),{recursive:true});fs.writeFileSync(path.join(target(root),'keep.txt'),'keep');let calls=0
 for(const out of [target(root),path.join(root,'outside')])await assert.rejects(prepareDemoReference(out,{root,fetcher:async()=>{calls++;return transport('')}}),/Fresh destination/)
 assert.equal(calls,0);assert.equal(fs.readFileSync(path.join(target(root),'keep.txt'),'utf8'),'keep')
})
test('manifest traversal and changed snapshot bytes cannot pass readiness',async t=>{
 const {out}=await bundle(t),manifest=JSON.parse(fs.readFileSync(path.join(out,'manifest.json')))
 fs.appendFileSync(path.join(out,manifest.files[0].file),' ');assert.equal(inspectDemoBundle(out).ready,false)
 manifest.files[0].file='../private';fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify(manifest));assert.equal(inspectDemoBundle(out).code,'UNEXPECTED_DEMO_BUNDLE')
})
test('symlinked output ancestors cannot escape the public workspace',async t=>{
 const root=workspace(t),other=workspace(t);fs.symlinkSync(other,path.join(root,'artifacts'),'junction')
 await assert.rejects(prepareDemoReference(target(root),{root,fetcher:transport}),/escapes workspace/);assert.equal(fs.existsSync(path.join(other,'bundle')),false)
})
test('readiness inspects public inputs without claiming build or model success',async t=>{
 const {root,out}=await bundle(t);dependencies(root)
 const r=inspectDemoReadiness({root,bundle:out,javaExecutable:'fixture/java',probe,nodeVersion:'22.0.0'})
 assert.equal(r.ready,true);assert.equal(r.modelCalls,0);assert.equal(r.modelConfiguration,'NOT_READ_OR_PROBED');assert.equal(r.buildExecuted,false);assert.equal(r.applicationStarted,false);assert.equal(r.fullS5Acceptance,false)
})
test('wrong Node or JDK versions and changed dependency versions fail readiness',async t=>{
 const {root,out}=await bundle(t);dependencies(root)
 assert.equal(inspectDemoReadiness({root,bundle:out,probe,nodeVersion:'20.0.0'}).ready,false)
 assert.equal(inspectDemoReadiness({root,bundle:out,probe:()=> 'openjdk version "17.0.1"',nodeVersion:'22.0.0'}).checks.jdk21,false)
 fs.writeFileSync(path.join(root,'vio-ai-agent-frontend/node_modules/vite/package.json'),'{"version":"wrong"}')
 assert.equal(inspectDemoReadiness({root,bundle:out,probe,nodeVersion:'22.0.0'}).checks.frontend,false)
})
test('missing packages, wrappers or reference bundles are reported without executing installers',t=>{
 const root=workspace(t),r=inspectDemoReadiness({root,bundle:path.join(root,'missing'),probe:()=>{throw Error('private-error')},nodeVersion:'22.0.0'})
 assert.equal(r.ready,false);assert.equal(r.checks.mavenWrapper,false);assert.equal(r.frontend.ready,false);assert.equal(r.references.ready,false);assert.equal(JSON.stringify(r).includes('private-error'),false)
})
test('private model configuration is never selected for readiness reads',async t=>{
 const {root,out}=await bundle(t);dependencies(root);const original=fs.readFileSync,reads=[]
 t.mock.method(fs,'readFileSync',function(file,...args){reads.push(String(file));if(String(file).includes('application-local.yml'))throw Error('Unexpected private config read');return original.call(this,file,...args)})
 const r=inspectDemoReadiness({root,bundle:out,probe,nodeVersion:'22.0.0',javaExecutable:'fixture/java'})
 assert.equal(r.ready,true);assert.equal(reads.some(f=>f.endsWith('application-local.yml')),false)
})
test('CLI refuses an existing report before any environment inspection',t=>{
 const root=workspace(t),output=path.join(root,'keep.json');fs.writeFileSync(output,'keep')
 const r=spawnSync(process.execPath,['tools/campus/demo-readiness.mjs','--bundle','missing','--out',output],{encoding:'utf8'})
 assert.notEqual(r.status,0);assert.match(r.stderr,/fresh report/);assert.equal(fs.readFileSync(output,'utf8'),'keep')
})
test('public build source selection includes only the named public demo config',()=>{
 assert.equal(smokeSourceAllowed('vio-ai-agent-frontend/vite.demo.config.js'),true)
 assert.equal(smokeSourceAllowed('vio-ai-agent-frontend/vite.private.config.js'),false)
 assert.equal(smokeSourceAllowed('src/main/resources/application-local.yml'),false)
})
