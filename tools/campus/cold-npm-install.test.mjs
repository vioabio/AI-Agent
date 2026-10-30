import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {coldNpmInstall,publicInstallEnvironment} from './cold-npm-install.mjs'
import {digest} from './evaluation.mjs'
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'cold-npm-check-'))
 t.after(()=>{if(path.dirname(path.resolve(root))!==path.resolve(os.tmpdir())||!path.basename(root).startsWith('cold-npm-check-'))throw Error('Unexpected temporary directory');fs.rmSync(root,{recursive:true,force:true})})
 const workspace=path.join(root,'artifacts/workspace'),cwd=path.join(workspace,'vio-ai-agent-frontend');fs.mkdirSync(cwd,{recursive:true})
 fs.writeFileSync(path.join(cwd,'package.json'),'{"name":"fixture","devDependencies":{"vite":"1.2.3"}}')
 fs.writeFileSync(path.join(cwd,'package-lock.json'),JSON.stringify({packages:{'node_modules/vite':{version:'1.2.3',resolved:'https://registry.npmjs.org/vite/-/vite-1.2.3.tgz'}}}))
 const npmCliFile=path.join(root,'npm-cli.js');fs.writeFileSync(npmCliFile,'// fake transport only')
 const update=()=>fs.writeFileSync(path.join(workspace,'smoke-source-manifest.json'),JSON.stringify({schemaVersion:'public-source-smoke-workspace-v1',privateConfigCopied:false,existingDatabasesCopied:false,existingBuildOutputsCopied:false,sourceSha256:Object.fromEntries(['package.json','package-lock.json'].map(f=>['vio-ai-agent-frontend/'+f,digest(fs.readFileSync(path.join(cwd,f)))]))}))
 update();return {root,workspace,cwd,npmCliFile,update}
}
const installed=options=>{fs.mkdirSync(path.join(options.cwd,'node_modules/vite'),{recursive:true});fs.writeFileSync(path.join(options.cwd,'node_modules/vite/package.json'),'{"version":"1.2.3"}');fs.writeFileSync(options.logFile,'fixture')}
test('cold plan uses empty configs, absent cache, fixed registry and a filtered child environment',async t=>{
 const f=fixture(t);let calls=0
 const report=await coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile,environment:{PATH:'public-path',DASHSCOPE_API_KEY:'secret',NPM_TOKEN:'secret'},runner:async(file,args,options)=>{
  calls++;assert.equal(file,process.execPath);assert.equal(args[1],'ci');assert.equal(fs.existsSync(args[args.indexOf('--cache')+1]),false)
  for(const flag of ['--userconfig','--globalconfig'])assert.equal(fs.readFileSync(args[args.indexOf(flag)+1],'utf8'),'')
  assert.deepEqual(options.env,{PATH:'public-path'});assert.ok(args.includes('--fetch-retries=0'));installed(options);return {exitCode:0}
 }})
 assert.equal(calls,1);assert.equal(report.pass,true);assert.equal(report.cacheInitiallyAbsent,true);assert.equal(report.cleanMachineAcceptance,false);assert.equal(report.modelCalls,0)
})
test('failed install is recorded once and is not silently retried or warmed',async t=>{
 const f=fixture(t);let calls=0
 const r=await coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile,runner:async()=>{calls++;return {exitCode:1}}})
 assert.equal(r.pass,false);assert.equal(calls,1);assert.equal(r.installedVersion,null)
 assert.equal(JSON.parse(fs.readFileSync(path.join(f.workspace,'.cold-npm/frontend/report.json'))).exitCode,1)
})
test('existing node_modules and repeated stages cannot be removed or reused',async t=>{
 const f=fixture(t);fs.mkdirSync(path.join(f.cwd,'node_modules'));fs.writeFileSync(path.join(f.cwd,'node_modules/keep'),'keep')
 await assert.rejects(coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile}),/Fresh/)
 assert.equal(fs.readFileSync(path.join(f.cwd,'node_modules/keep'),'utf8'),'keep')
})
test('changed public package source is rejected before execution',async t=>{
 const f=fixture(t);fs.appendFileSync(path.join(f.cwd,'package-lock.json'),' ')
 await assert.rejects(coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile}),/source changed/)
 await assert.rejects(coldNpmInstall(f.workspace,'__proto__',{root:f.root,npmCliFile:f.npmCliFile}),/Unknown component/)
})
test('credentialed or non-public dependency sources and local links are rejected',async t=>{
 for(const entry of [{version:'1.2.3',resolved:'https://user:password@registry.npmjs.org/package'},
  {version:'1.2.3',resolved:'https://private.example/package'},{version:'1.2.3',link:true}]){
  const f=fixture(t);fs.writeFileSync(path.join(f.cwd,'package-lock.json'),JSON.stringify({packages:{'node_modules/vite':entry}}));f.update()
  await assert.rejects(coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile}),/public npm|Local dependency/)
 }
})
test('cache symlink escape is rejected before outside writes',async t=>{
 const f=fixture(t),outside=path.join(f.root,'outside');fs.mkdirSync(outside);fs.symlinkSync(outside,path.join(f.workspace,'.cold-npm'),'junction')
 await assert.rejects(coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile}),/Cache escapes/)
 assert.deepEqual(fs.readdirSync(outside),[])
})
test('source deletion or changed installed version cannot become a successful result',async t=>{
 for(const mode of ['delete','version']){
  const f=fixture(t)
  const r=await coldNpmInstall(f.workspace,'frontend',{root:f.root,npmCliFile:f.npmCliFile,runner:async(file,args,options)=>{installed(options);if(mode==='delete')fs.unlinkSync(path.join(f.cwd,'package-lock.json'));else fs.writeFileSync(path.join(f.cwd,'node_modules/vite/package.json'),'{"version":"wrong"}');return {exitCode:0}}})
  assert.equal(r.pass,false);if(mode==='delete')assert.equal(r.sourcesUnchanged,false);else assert.equal(r.installedVersion,'wrong')
 }
})
test('HTTP reproduction probe refuses daily ports before issuing requests',t=>{
 const f=fixture(t),out=path.join(f.root,'probe.json')
 const r=spawnSync(process.execPath,['tools/campus/public-reproduction-probe.mjs','http://127.0.0.1:8123/api',out],{encoding:'utf8'})
 assert.notEqual(r.status,0);assert.match(r.stderr,/Only isolated reproduction port/);assert.equal(fs.existsSync(out),false)
})
test('HTTP reproduction probe cannot overwrite an existing report',t=>{
 const f=fixture(t),out=path.join(f.root,'probe.json');fs.writeFileSync(out,'keep')
 const r=spawnSync(process.execPath,['tools/campus/public-reproduction-probe.mjs','http://127.0.0.1:18259/api',out],{encoding:'utf8'})
 assert.notEqual(r.status,0);assert.match(r.stderr,/fresh output/);assert.equal(fs.readFileSync(out,'utf8'),'keep')
})
test('only public process paths are forwarded, without Node or npm credential overrides',()=>{
 assert.deepEqual(publicInstallEnvironment({HOME:'home',Path:'path',NODE_OPTIONS:'--require private',npm_config_userconfig:'private',NODE_AUTH_TOKEN:'secret',DEEPSEEK_API_KEY:'secret'}),{Path:'path',HOME:'home'})
})
