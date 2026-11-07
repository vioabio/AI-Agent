import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {matchesNodeEngine,inspectNodeRequirement,inspectDemoReadiness} from './demo-readiness.mjs'
const range='^20.19.0 || >=22.12.0'
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'demo-node-requirements-'))
 t.after(()=>{if(path.dirname(path.resolve(root))!==path.resolve(os.tmpdir())||!path.basename(root).startsWith('demo-node-requirements-'))throw Error('Unsafe temporary root');fs.rmSync(root,{recursive:true,force:true})})
 const frontend=path.join(root,'vio-ai-agent-frontend');fs.mkdirSync(frontend,{recursive:true})
 const write=(file,value)=>fs.writeFileSync(path.join(frontend,file),JSON.stringify(value))
 const project={engines:{node:range}},lock={packages:{'':{engines:{node:range}},'node_modules/vite':{version:'8.3.5',engines:{node:range}}}}
 write('package.json',project);write('package-lock.json',lock)
 return {root,frontend,write,project,lock}
}
test('actual supported major/minor boundaries reject early Node22 and accept supported Node20',()=>{
 for(const version of ['20.19.0','20.20.1','22.12.0','22.13.0','24.19.0'])assert.equal(matchesNodeEngine(version,range),true,version)
 for(const version of ['18.20.0','20.18.9','21.9.0','22.0.0','22.11.9'])assert.equal(matchesNodeEngine(version,range),false,version)
})
test('unknown grammar malformed and prerelease versions remain unknown rather than being widened',()=>{
 for(const value of ['22','22.12','22.12.0-rc.1','v22.12.0','022.12.0','999999999999999999999.0.0'])assert.equal(matchesNodeEngine(value,range),null)
 for(const required of ['*','>=22','>=22.12.0 <23','^20.19.0 || *','>=22.12.0 ||','x'.repeat(257)])assert.equal(matchesNodeEngine('24.19.0',required),null)
 assert.equal(matchesNodeEngine('0.2.1','^0.2.0'),true);assert.equal(matchesNodeEngine('0.3.0','^0.2.0'),false)
 assert.equal(matchesNodeEngine('0.0.2','^0.0.1'),false)
})
test('project and locked Vite requirements are both enforced',t=>{
 const f=fixture(t);assert.equal(inspectNodeRequirement(f.root,'20.19.0').ready,true)
 f.project.engines.node='>=24.0.0';f.lock.packages[''].engines.node='>=24.0.0'
 f.write('package.json',f.project);f.write('package-lock.json',f.lock)
 assert.equal(inspectNodeRequirement(f.root,'22.12.0').ready,false)
 assert.equal(inspectNodeRequirement(f.root,'24.19.0').ready,true)
})
test('mismatched absent and unsupported metadata cannot pass first-install readiness',t=>{
 const f=fixture(t);f.project.engines.node='>=24.0.0';f.write('package.json',f.project)
 assert.equal(inspectNodeRequirement(f.root,'24.19.0').code,'PROJECT_LOCK_ENGINE_MISMATCH')
 f.write('package.json',{engines:{node:range}});delete f.lock.packages['node_modules/vite'].engines;f.write('package-lock.json',f.lock)
 assert.equal(inspectNodeRequirement(f.root,'24.19.0').code,'NODE_ENGINE_METADATA_MISSING')
 f.lock.packages['node_modules/vite'].engines={node:'*'};f.write('package-lock.json',f.lock)
 assert.equal(inspectNodeRequirement(f.root,'24.19.0').code,'NODE_ENGINE_RANGE_OR_VERSION_UNSUPPORTED')
})
test('installed Vite engine mismatch is reported even when its version matches the lock',t=>{
 const f=fixture(t);fs.mkdirSync(path.join(f.frontend,'node_modules/vite'),{recursive:true})
 f.write('node_modules/vite/package.json',{version:'8.3.5',engines:{node:'>=99.0.0'}})
 const report=inspectDemoReadiness({root:f.root,nodeVersion:'24.19.0',probe:()=>{throw Error('private')}})
 assert.equal(report.checks.node,true);assert.equal(report.checks.frontend,false)
 assert.equal(report.frontend.code,'INSTALLED_ENGINE_METADATA_MISMATCH')
})
test('missing first-install inputs produce actionable issues without running an installer or model',t=>{
 const f=fixture(t),commands=[]
 const report=inspectDemoReadiness({root:f.root,nodeVersion:'22.0.0',javaExecutable:'fixture/java',probe:(...args)=>{commands.push(args);throw Error('private failure')}})
 assert.equal(report.schemaVersion,'demo-readiness-v2');assert.equal(report.ready,false)
 assert.equal(report.issues.length,6);assert.ok(report.issues.every(i=>typeof i.action==='string'&&i.action.length>0))
 assert.deepEqual(commands.map(c=>c[1]),[['-version'],['-version']]);assert.equal(report.modelCalls,0)
 assert.equal(report.applicationStarted,false);assert.equal(report.buildExecuted,false);assert.doesNotMatch(JSON.stringify(report),/private failure/)
})
