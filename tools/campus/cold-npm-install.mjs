import fs from 'node:fs'
import path from 'node:path'
import {spawn} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'

const components={frontend:{directory:'vio-ai-agent-frontend',module:'vite'},engine:{directory:'tools/pokemon-engine',module:'@smogon/calc'}}
const present=file=>{try{fs.lstatSync(file);return true}catch(error){if(error.code==='ENOENT')return false;throw error}}
export function publicInstallEnvironment(source=process.env){
 const names=['PATH','Path','SystemRoot','SYSTEMROOT','ComSpec','COMSPEC','PATHEXT','TEMP','TMP','TMPDIR','HOME','USERPROFILE','APPDATA','LOCALAPPDATA','NUMBER_OF_PROCESSORS','PROCESSOR_ARCHITECTURE','LANG','LC_ALL','TZ']
 return Object.fromEntries(names.filter(name=>source[name]!==undefined).map(name=>[name,source[name]]))
}
function npmCli(){
 const bin=path.dirname(process.execPath)
 const candidates=[path.join(bin,'node_modules/npm/bin/npm-cli.js'),path.resolve(bin,'../lib/node_modules/npm/bin/npm-cli.js'),path.resolve(bin,'../node_modules/npm/bin/npm-cli.js')]
 const file=candidates.find(f=>fs.existsSync(f));if(!file)throw Error('NPM_CLI_NOT_FOUND: supply --npm-cli');return file
}
function runNpm(file,args,{cwd,env,logFile}){
 return new Promise(resolve=>{
  const log=fs.openSync(logFile,'wx');let finished=false
  const finish=value=>{if(finished)return;finished=true;fs.closeSync(log);resolve(value)}
  const child=spawn(file,args,{cwd,env,windowsHide:true,stdio:['ignore',log,log]})
  child.once('error',()=>finish({exitCode:-1,errorCode:'NPM_PROCESS_START_FAILED'}))
  child.once('close',(code,signal)=>finish({exitCode:code??-1,signal:signal||null}))
 })
}
export async function coldNpmInstall(workspace,component,{root=process.cwd(),npmCliFile,runner=runNpm,environment=process.env}={}){
 if(!Object.hasOwn(components,component))throw Error('Unknown component')
 const selected=components[component]
 root=fs.realpathSync(root);workspace=fs.realpathSync(workspace)
 const relative=path.relative(path.join(root,'artifacts'),workspace)
 if(!relative||relative.startsWith('..')||path.isAbsolute(relative))throw Error('Public workspace inside artifacts required')
 const manifestFile=path.join(workspace,'smoke-source-manifest.json')
 if(fs.lstatSync(manifestFile).isSymbolicLink())throw Error('Unsafe source manifest')
 const manifest=JSON.parse(fs.readFileSync(manifestFile))
 if(manifest.schemaVersion!=='public-source-smoke-workspace-v1'||manifest.privateConfigCopied!==false||manifest.existingDatabasesCopied!==false||manifest.existingBuildOutputsCopied!==false)throw Error('Filtered public source manifest required')
 const cwd=path.join(workspace,selected.directory),modules=path.join(cwd,'node_modules'),stage=path.join(workspace,'.cold-npm',component)
 const cwdRelative=path.relative(workspace,fs.realpathSync(cwd));if(cwdRelative.startsWith('..')||path.isAbsolute(cwdRelative))throw Error('Component escapes public workspace')
 if(present(modules)||present(stage))throw Error('Fresh node_modules and npm cache required')
 let ancestor=path.dirname(stage);while(!present(ancestor))ancestor=path.dirname(ancestor)
 const stageRelative=path.relative(workspace,fs.realpathSync(ancestor));if(stageRelative.startsWith('..')||path.isAbsolute(stageRelative))throw Error('Cache escapes public workspace')
 const inputs={}
 for(const name of ['package.json','package-lock.json']){
  const relativeFile=selected.directory+'/'+name,file=path.join(cwd,name)
  if(fs.lstatSync(file).isSymbolicLink())throw Error('Unsafe package source')
  const bytes=fs.readFileSync(file);if(digest(bytes)!==manifest.sourceSha256?.[relativeFile])throw Error('Public package source changed')
  inputs[relativeFile]=digest(bytes)
 }
 const lock=JSON.parse(fs.readFileSync(path.join(cwd,'package-lock.json')))
 for(const entry of Object.values(lock.packages||{})){
  if(entry.link)throw Error('Local dependency links not allowed in cold check')
  if(!entry.resolved)continue
  const url=new URL(entry.resolved)
  if(url.protocol!=='https:'||url.hostname!=='registry.npmjs.org'||url.port||url.username||url.password)throw Error('Only public npm registry lock sources allowed')
 }
 const expected=lock.packages?.['node_modules/'+selected.module]?.version
 if(!expected)throw Error('Expected locked dependency missing')
 const cli=npmCliFile||npmCli();if(!fs.statSync(cli).isFile())throw Error('npm CLI file required')
 fs.mkdirSync(stage,{recursive:true})
 const cache=path.join(stage,'cache'),userConfig=path.join(stage,'user.npmrc'),globalConfig=path.join(stage,'global.npmrc')
 fs.writeFileSync(userConfig,'',{flag:'wx'});fs.writeFileSync(globalConfig,'',{flag:'wx'})
 const args=[cli,'ci','--cache',cache,'--userconfig',userConfig,'--globalconfig',globalConfig,'--registry=https://registry.npmjs.org','--fetch-retries=0','--fetch-timeout=15000','--no-audit','--no-fund']
 const report={schemaVersion:'cold-npm-install-v1',component,startedAt:new Date().toISOString(),cacheInitiallyAbsent:!present(cache),nodeModulesInitiallyAbsent:true,
  npmConfigMode:'GENERATED_EMPTY_USER_AND_GLOBAL',registry:'https://registry.npmjs.org',automaticFetchRetries:0,expectedVersion:expected,sourceSha256:inputs,
  modelCalls:0,applicationStarted:false,cleanMachineAcceptance:false,scope:'This component starts without node_modules or npm cache; existing OS, Node/npm binaries and network are reused. Not Maven, build or model acceptance.'}
 fs.writeFileSync(path.join(stage,'plan.json'),JSON.stringify({...report,command:{file:process.execPath,args}},null,2)+'\n',{flag:'wx'})
 let result
 try{result=await runner(process.execPath,args,{cwd,env:publicInstallEnvironment(environment),logFile:path.join(stage,'install.log')})}
 catch{result={exitCode:-1,errorCode:'NPM_RUNNER_FAILED'}}
 if(!result||!Number.isInteger(result.exitCode))result={exitCode:-1,errorCode:'NPM_RUNNER_FAILED'}
 let installed=null;try{installed=JSON.parse(fs.readFileSync(path.join(modules,selected.module,'package.json'))).version}catch{}
 const sourcesUnchanged=Object.entries(inputs).every(([file,sha])=>{try{return digest(fs.readFileSync(path.join(workspace,file)))===sha}catch{return false}})
 Object.assign(report,{finishedAt:new Date().toISOString(),exitCode:result.exitCode,installedVersion:installed,sourcesUnchanged,
  pass:result.exitCode===0&&installed===expected&&sourcesUnchanged})
 if(result.errorCode)report.errorCode=result.errorCode
 fs.writeFileSync(path.join(stage,'report.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 return report
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [workspace,component,...args]=process.argv.slice(2)
 if(!workspace||!component||args.length&&!(args.length===2&&args[0]==='--npm-cli'))throw Error('Public workspace, frontend|engine, optional --npm-cli required')
 const report=await coldNpmInstall(workspace,component,{npmCliFile:args[1]})
 console.log(JSON.stringify({component,pass:report.pass,exitCode:report.exitCode,cacheInitiallyAbsent:report.cacheInitiallyAbsent,modelCalls:0}));if(!report.pass)process.exitCode=1
}
