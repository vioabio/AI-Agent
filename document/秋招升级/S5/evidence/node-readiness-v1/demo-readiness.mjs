import fs from 'node:fs'
import path from 'node:path'
import {spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {inspectDemoBundle} from './prepare-demo-reference.mjs'
// Deliberately bounded engine grammar. Unknown ranges require review instead of guessing.
export function matchesNodeEngine(version,range){
 const parsed=/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(version??'')
 if(!parsed||typeof range!=='string'||range.length>256)return null
 const current=parsed.slice(1).map(Number),clauses=range.trim().split(/\s*\|\|\s*/)
 if(current.some(v=>!Number.isSafeInteger(v))||clauses.length>4)return null
 const conditions=clauses.map(c=>/^(>=|\^)\s*(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(c))
 if(conditions.some(c=>!c||c.slice(2).some(v=>!Number.isSafeInteger(Number(v)))))return null
 return conditions.some(c=>{
  const minimum=c.slice(2).map(Number),comparison=current[0]-minimum[0]||current[1]-minimum[1]||current[2]-minimum[2]
  if(comparison<0)return false
  if(c[1]==='>=')return true
  return current[0]===minimum[0]&&(minimum[0]>0||current[1]===minimum[1]&&(minimum[1]>0||current[2]===minimum[2]))
 })
}
export function inspectNodeRequirement(root,version){
 try{
  const project=JSON.parse(fs.readFileSync(path.join(root,'vio-ai-agent-frontend/package.json')))
  const lock=JSON.parse(fs.readFileSync(path.join(root,'vio-ai-agent-frontend/package-lock.json')))
  const declared=project.engines?.node,lockedProject=lock.packages?.['']?.engines?.node,lockedVite=lock.packages?.['node_modules/vite']?.engines?.node
  if(![declared,lockedProject,lockedVite].every(v=>typeof v==='string'&&v.trim()))return {ready:false,code:'NODE_ENGINE_METADATA_MISSING'}
  if(declared.trim()!==lockedProject.trim())return {ready:false,code:'PROJECT_LOCK_ENGINE_MISMATCH'}
  const decisions=[declared,lockedVite].map(range=>matchesNodeEngine(version,range))
  const ready=decisions.every(v=>v===true)
  return {ready,code:ready?'SUPPORTED':decisions.includes(null)?'NODE_ENGINE_RANGE_OR_VERSION_UNSUPPORTED':'NODE_VERSION_INCOMPATIBLE',declared,lockedVite}
 }catch{return {ready:false,code:'NODE_ENGINE_METADATA_UNAVAILABLE'}}
}
export function inspectDemoReadiness({root=process.cwd(),bundle,javaExecutable,probe,nodeVersion=process.versions.node}={}){
 const selected=javaExecutable||(process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java')
 // Use spawnSync to retain stderr for java -version, never shell expansion.
 const run=probe||probeJava
 let javaMajor=null,javacMajor=null
 try{javaMajor=Number(/version\s+"(\d+)/.exec(run(selected,['-version']))?.[1])||null}catch{}
 const javac=selected==='java'?'javac':path.join(path.dirname(selected),process.platform==='win32'?'javac.exe':'javac')
 try{javacMajor=Number(/javac\s+(\d+)/.exec(run(javac,['-version']))?.[1])||null}catch{}
 const packageCheck=(base,name)=>{try{const lock=JSON.parse(fs.readFileSync(path.join(root,base,'package-lock.json'))),installed=JSON.parse(fs.readFileSync(path.join(root,base,'node_modules',name,'package.json'))),entry=lock.packages['node_modules/'+name],expected=entry?.version;
  const engineMatches=!entry?.engines?.node||installed.engines?.node===entry.engines.node
  return {ready:!!expected&&installed.version===expected&&engineMatches,expected:expected||'unknown',installed:installed.version,...(!engineMatches?{code:'INSTALLED_ENGINE_METADATA_MISMATCH'}:{})}}catch{return {ready:false,code:'DEPENDENCY_NOT_INSTALLED'}}}
 const frontend=packageCheck('vio-ai-agent-frontend','vite'),engine=packageCheck('tools/pokemon-engine','@smogon/calc')
 const references=inspectDemoBundle(bundle||'')
 const nodeCompatibility=inspectNodeRequirement(root,nodeVersion)
 const checks={node:nodeCompatibility.ready,jdk21:javaMajor===21&&javacMajor===21,
  mavenWrapper:['mvnw.cmd','.mvn/wrapper/maven-wrapper.properties','pom.xml'].every(f=>fs.existsSync(path.join(root,f))),frontend:frontend.ready,engine:engine.ready,references:references.ready}
 const remedies={node:['CHECK_NODE_REQUIREMENTS','按项目与锁定Vite的engines要求选择Node；缺失或不支持的范围需先核对元数据。'],jdk21:['JDK21_REQUIRED','指定同时包含java和javac的JDK21，设置JAVA_HOME或传入--java。'],mavenWrapper:['PUBLIC_PROJECT_INPUTS_REQUIRED','进入项目根目录，或用--root指定包含pom与Maven Wrapper的公开工作区。'],frontend:['FRONTEND_INSTALL_REQUIRED','在所选工作区执行npm --prefix vio-ai-agent-frontend ci，再检查版本与锁文件。'],engine:['ENGINE_INSTALL_REQUIRED','在所选工作区执行npm --prefix tools/pokemon-engine ci。'],references:['REFERENCE_BUNDLE_REQUIRED','准备并校验完整公开资料包，再用--bundle指定；不要使用不完整目录。']}
 const issues=Object.entries(checks).filter(([,ready])=>!ready).map(([check])=>({check,code:remedies[check][0],action:remedies[check][1]}))
 return {schemaVersion:'demo-readiness-v2',ready:Object.values(checks).every(Boolean),checks,nodeVersion,nodeCompatibility,issues,javaMajor,javacMajor,frontend,engine,references,
  modelConfiguration:'NOT_READ_OR_PROBED',modelCalls:0,buildExecuted:false,applicationStarted:false,fullS5Acceptance:false,
  scope:'Public inputs and installed version metadata only. Not a build, model connectivity, clean-machine installation or answer-quality acceptance.'}
}
function probeJava(file,args){const r=spawnSync(file,args,{encoding:'utf8',timeout:5000,maxBuffer:16384,windowsHide:true});if(r.error||r.status!==0)throw Error('JDK_PROBE_FAILED');return String(r.stdout||'')+String(r.stderr||'')}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const options={},args=process.argv.slice(2)
 for(let i=0;i<args.length;i+=2){if(!['--bundle','--java','--out','--root'].includes(args[i])||!args[i+1]||Object.hasOwn(options,args[i]))throw Error('Expected --bundle, optional --root/--java and fresh --out');options[args[i]]=args[i+1]}
 if(!options['--bundle']||!options['--out']||fs.existsSync(options['--out']))throw Error('Bundle and fresh report filename required')
 const report=inspectDemoReadiness({root:options['--root'],bundle:options['--bundle'],javaExecutable:options['--java']})
 fs.writeFileSync(options['--out'],JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({ready:report.ready,checks:report.checks,issues:report.issues,modelCalls:0}));if(!report.ready)process.exitCode=2
}
