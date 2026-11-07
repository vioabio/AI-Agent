import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import {prepareColdMaven} from './cold-maven-plan.mjs'
import {digest} from './evaluation.mjs'
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'cold-maven-plan-'))
 t.after(()=>{if(path.dirname(path.resolve(root))!==path.resolve(os.tmpdir())||!path.basename(root).startsWith('cold-maven-plan-'))throw Error('Unexpected temporary path');fs.rmSync(root,{recursive:true,force:true})})
 const workspace=path.join(root,'artifacts/work');fs.mkdirSync(path.join(workspace,'.mvn/wrapper'),{recursive:true})
 const hashes={};for(const file of ['pom.xml','mvnw','mvnw.cmd','.mvn/wrapper/maven-wrapper.properties']){fs.writeFileSync(path.join(workspace,file),'public '+file);hashes[file]=digest(fs.readFileSync(path.join(workspace,file)))}
 fs.writeFileSync(path.join(workspace,'smoke-source-manifest.json'),JSON.stringify({schemaVersion:'public-source-smoke-workspace-v1',privateConfigCopied:false,existingDatabasesCopied:false,existingBuildOutputsCopied:false,sourceSha256:hashes}))
 return {root,workspace}
}
test('fresh preparation creates a genuinely empty repository and explicit public settings',t=>{
 const f=fixture(t),p=prepareColdMaven(f.workspace,{root:f.root})
 assert.equal(p.initialRepositoryEntries,0);assert.equal(p.commandExecuted,false);assert.equal(p.modelCalls,0);assert.equal(p.cleanMachineAcceptance,false)
 const settings=p.command.args[p.command.args.indexOf('-s')+1],global=p.command.args[p.command.args.indexOf('-gs')+1]
 assert.match(fs.readFileSync(settings,'utf8'),/https:\/\/repo.maven.apache.org\/maven2/);assert.doesNotMatch(fs.readFileSync(settings,'utf8'),/<servers>|<password>/)
 assert.match(fs.readFileSync(global,'utf8'),/^<settings[^>]*><\/settings>/)
 assert.ok(p.command.args.some(a=>a.startsWith('-Dmaven.repo.local=')));assert.ok(p.command.args.includes('-Dmaven.test.skip=true'))
})
test('repeat preparation preserves prior plan instead of clearing dependency state',t=>{
 const f=fixture(t);prepareColdMaven(f.workspace,{root:f.root});const file=path.join(f.workspace,'.cold-maven/plan.json'),before=fs.readFileSync(file)
 assert.throws(()=>prepareColdMaven(f.workspace,{root:f.root}),/Fresh/);assert.deepEqual(fs.readFileSync(file),before)
})
test('existing build output cannot masquerade as a new build',t=>{
 const f=fixture(t);fs.mkdirSync(path.join(f.workspace,'target'));fs.writeFileSync(path.join(f.workspace,'target/keep'),'keep')
 assert.throws(()=>prepareColdMaven(f.workspace,{root:f.root}),/Fresh/);assert.equal(fs.readFileSync(path.join(f.workspace,'target/keep'),'utf8'),'keep')
})
test('changed pom or private-state manifest is rejected before staging',t=>{
 const f=fixture(t);fs.appendFileSync(path.join(f.workspace,'pom.xml'),'changed');assert.throws(()=>prepareColdMaven(f.workspace,{root:f.root}),/source changed/)
 const other=fixture(t),file=path.join(other.workspace,'smoke-source-manifest.json'),manifest=JSON.parse(fs.readFileSync(file));manifest.privateConfigCopied=true;fs.writeFileSync(file,JSON.stringify(manifest))
 assert.throws(()=>prepareColdMaven(other.workspace,{root:other.root}),/Filtered public/)
})
test('outside workspace and source symlinks are rejected before file writes',t=>{
 const f=fixture(t);assert.throws(()=>prepareColdMaven(f.root,{root:f.root}),/inside artifacts/)
 fs.unlinkSync(path.join(f.workspace,'.mvn/wrapper/maven-wrapper.properties'));fs.rmdirSync(path.join(f.workspace,'.mvn/wrapper'));fs.rmdirSync(path.join(f.workspace,'.mvn'))
 const outside=path.join(f.root,'external-mvn');fs.mkdirSync(path.join(outside,'wrapper'),{recursive:true});fs.writeFileSync(path.join(outside,'wrapper/maven-wrapper.properties'),'outside');fs.symlinkSync(outside,path.join(f.workspace,'.mvn'),'junction')
 assert.throws(()=>prepareColdMaven(f.workspace,{root:f.root}),/Unsafe Maven input/);assert.equal(fs.existsSync(path.join(f.workspace,'.cold-maven')),false)
})
