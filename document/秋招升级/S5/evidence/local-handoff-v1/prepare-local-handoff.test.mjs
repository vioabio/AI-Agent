import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import {spawnSync} from 'node:child_process'
import {prepareLocalHandoff} from './prepare-local-handoff.mjs'
import {digest} from './evaluation.mjs'
const bundle=path.resolve('document/秋招升级/S5/evidence/network-proxy-download-v1/public-reference-bundle')
const helpers=['tools/reference-cache.mjs','tools/campus/demo-readiness.mjs','tools/campus/prepare-demo-reference.mjs','tools/campus/evaluation.mjs','tools/campus/download-demo-via-proxy.mjs']
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'local-handoff-'))
 t.after(()=>{if(path.dirname(path.resolve(root))!==path.resolve(os.tmpdir())||!path.basename(root).startsWith('local-handoff-'))throw Error('Unsafe temporary root');fs.rmSync(root,{recursive:true,force:true})})
 const candidates=['pom.xml','mvnw.cmd','src/main/resources/application.yml','src/main/resources/application-local.example.yml','.mvn/wrapper/maven-wrapper.properties','vio-ai-agent-frontend/package.json','vio-ai-agent-frontend/package-lock.json','tools/pokemon-engine/package.json','tools/pokemon-engine/package-lock.json']
 for(const file of [...candidates,...helpers]){const target=path.join(root,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(file,target)}
 return {root,candidates,out:path.join(root,'artifacts/handoff')}
}
test('portable helper closure runs from its own directory without installed dependencies',t=>{
 const f=fixture(t),report=prepareLocalHandoff(f.root,f.out,bundle,f.candidates)
 assert.equal(report.cleanMachineAcceptance,false);assert.equal(report.installersExecuted,false)
 for(const [file,hash] of Object.entries(report.extraSha256))assert.equal(digest(fs.readFileSync(path.join(f.out,file))),hash)
 const child=spawnSync(process.execPath,['tools/campus/demo-readiness.mjs','--bundle','reference-bundle','--java','missing-java','--out','readiness.json'],{cwd:f.out,encoding:'utf8',timeout:10000,windowsHide:true})
 assert.equal(child.status,2,child.stderr);const result=JSON.parse(fs.readFileSync(path.join(f.out,'readiness.json')))
 assert.equal(result.checks.references,true);assert.equal(result.checks.node,true);assert.equal(result.checks.frontend,false)
 assert.equal(fs.existsSync(path.join(f.out,'node_modules')),false)
})
test('private sources and prior runtime state never enter the delivery manifest',t=>{
 const f=fixture(t)
 for(const file of ['src/main/resources/application-local.yml','.env','.local-data/accounts.sqlite','target/keep']){const full=path.join(f.root,file);fs.mkdirSync(path.dirname(full),{recursive:true});fs.writeFileSync(full,'private sentinel');f.candidates.push(file)}
 prepareLocalHandoff(f.root,f.out,bundle,f.candidates)
 for(const file of ['src/main/resources/application-local.yml','.env','.local-data/accounts.sqlite','target/keep'])assert.equal(fs.existsSync(path.join(f.out,file)),false)
 assert.doesNotMatch(fs.readFileSync(path.join(f.out,'handoff-manifest.json'),'utf8'),/private sentinel/)
})
test('existing output is preserved and invalid bundle is rejected before creating a destination',t=>{
 const f=fixture(t);prepareLocalHandoff(f.root,f.out,bundle,f.candidates)
 const before=fs.readFileSync(path.join(f.out,'handoff-manifest.json'))
 assert.throws(()=>prepareLocalHandoff(f.root,f.out,bundle,f.candidates),/Fresh/)
 assert.deepEqual(fs.readFileSync(path.join(f.out,'handoff-manifest.json')),before)
 const other=path.join(f.root,'artifacts/invalid');assert.throws(()=>prepareLocalHandoff(f.root,other,f.root,f.candidates),/complete public bundle/)
 assert.equal(fs.existsSync(other),false)
})
