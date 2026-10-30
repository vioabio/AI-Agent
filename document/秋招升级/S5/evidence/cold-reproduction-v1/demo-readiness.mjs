import fs from 'node:fs'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {inspectDemoBundle} from './prepare-demo-reference.mjs'
export function inspectDemoReadiness({root=process.cwd(),bundle,javaExecutable,probe,nodeVersion=process.versions.node}={}){
 const selected=javaExecutable||(process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java')
 // Use spawnSync to retain stderr for java -version, never shell expansion.
 const run=probe||probeJava
 let javaMajor=null,javacMajor=null
 try{javaMajor=Number(/version\s+"(\d+)/.exec(run(selected,['-version']))?.[1])||null}catch{}
 const javac=selected==='java'?'javac':path.join(path.dirname(selected),process.platform==='win32'?'javac.exe':'javac')
 try{javacMajor=Number(/javac\s+(\d+)/.exec(run(javac,['-version']))?.[1])||null}catch{}
 const packageCheck=(base,name)=>{try{const lock=JSON.parse(fs.readFileSync(path.join(root,base,'package-lock.json'))),installed=JSON.parse(fs.readFileSync(path.join(root,base,'node_modules',name,'package.json'))),expected=lock.packages['node_modules/'+name]?.version;return {ready:!!expected&&installed.version===expected,expected:expected||'unknown',installed:installed.version}}catch{return {ready:false,code:'DEPENDENCY_NOT_INSTALLED'}}}
 const frontend=packageCheck('vio-ai-agent-frontend','vite'),engine=packageCheck('tools/pokemon-engine','@smogon/calc')
 const references=inspectDemoBundle(bundle||'')
 const checks={node:Number(nodeVersion.split('.')[0])>=22,jdk21:javaMajor===21&&javacMajor===21,
  mavenWrapper:['mvnw.cmd','.mvn/wrapper/maven-wrapper.properties','pom.xml'].every(f=>fs.existsSync(path.join(root,f))),frontend:frontend.ready,engine:engine.ready,references:references.ready}
 return {schemaVersion:'demo-readiness-v1',ready:Object.values(checks).every(Boolean),checks,nodeVersion,javaMajor,javacMajor,frontend,engine,references,
  modelConfiguration:'NOT_READ_OR_PROBED',modelCalls:0,buildExecuted:false,applicationStarted:false,fullS5Acceptance:false,
  scope:'Public inputs and installed version metadata only. Not a build, model connectivity, clean-machine installation or answer-quality acceptance.'}
}
function probeJava(file,args){const r=spawnSync(file,args,{encoding:'utf8',timeout:5000,maxBuffer:16384,windowsHide:true});if(r.error||r.status!==0)throw Error('JDK_PROBE_FAILED');return String(r.stdout||'')+String(r.stderr||'')}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const options={},args=process.argv.slice(2)
 for(let i=0;i<args.length;i+=2){if(!['--bundle','--java','--out','--root'].includes(args[i])||!args[i+1]||Object.hasOwn(options,args[i]))throw Error('Expected --bundle, optional --root/--java and fresh --out');options[args[i]]=args[i+1]}
 if(!options['--bundle']||!options['--out']||fs.existsSync(options['--out']))throw Error('Bundle and fresh report filename required')
 const report=inspectDemoReadiness({root:options['--root'],bundle:options['--bundle'],javaExecutable:options['--java']})
 fs.writeFileSync(options['--out'],JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({ready:report.ready,checks:report.checks,modelCalls:0}));if(!report.ready)process.exitCode=2
}
