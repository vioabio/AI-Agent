import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'

const exists=file=>{try{fs.lstatSync(file);return true}catch(e){if(e.code==='ENOENT')return false;throw e}}
export function prepareColdMaven(workspace,{root=process.cwd()}={}){
 root=fs.realpathSync(root);workspace=fs.realpathSync(workspace)
 const relative=path.relative(path.join(root,'artifacts'),workspace)
 if(!relative||relative.startsWith('..')||path.isAbsolute(relative))throw Error('Public workspace inside artifacts required')
 const manifestPath=path.join(workspace,'smoke-source-manifest.json')
 if(fs.lstatSync(manifestPath).isSymbolicLink())throw Error('Unsafe source manifest')
 const manifest=JSON.parse(fs.readFileSync(manifestPath))
 if(manifest.schemaVersion!=='public-source-smoke-workspace-v1'||manifest.privateConfigCopied!==false||manifest.existingDatabasesCopied!==false||manifest.existingBuildOutputsCopied!==false)throw Error('Filtered public manifest required')
 const stage=path.join(workspace,'.cold-maven')
 if(exists(stage)||exists(path.join(workspace,'target')))throw Error('Fresh build output and Maven stage required')
 const sources={}
 const wrapperName=process.platform==='win32'?'mvnw.cmd':'mvnw'
 for(const file of ['pom.xml',wrapperName,'.mvn/wrapper/maven-wrapper.properties']){
  const full=path.join(workspace,file),rel=path.relative(workspace,fs.realpathSync(full))
  if(rel.startsWith('..')||path.isAbsolute(rel)||fs.lstatSync(full).isSymbolicLink())throw Error('Unsafe Maven input')
  const bytes=fs.readFileSync(full);if(digest(bytes)!==manifest.sourceSha256?.[file])throw Error('Public Maven source changed');sources[file]=digest(bytes)
 }
 fs.mkdirSync(stage)
 const repository=path.join(stage,'repository'),settings=path.join(stage,'public-user-settings.xml'),globalSettings=path.join(stage,'public-global-settings.xml')
 fs.mkdirSync(repository)
 fs.writeFileSync(settings,'<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors><mirror><id>public-central-only</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>\n',{flag:'wx'})
 fs.writeFileSync(globalSettings,'<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"></settings>\n',{flag:'wx'})
 const plan={schemaVersion:'cold-maven-plan-v1',recordedAt:new Date().toISOString(),initialRepositoryEntries:fs.readdirSync(repository).length,
  sourceSha256:sources,settingsSha256:{user:digest(fs.readFileSync(settings)),global:digest(fs.readFileSync(globalSettings))},
  command:{cwd:workspace,file:path.join(workspace,wrapperName),args:['-B','-ntp','-s',settings,'-gs',globalSettings,'-Dmaven.repo.local='+repository,'-Dmaven.test.skip=true','package']},
  dependencyCacheReusePlanned:false,externalJdkAndWrapperNotReset:true,testsSkipped:true,commandExecuted:false,modelCalls:0,cleanMachineAcceptance:false,
  scope:'Preparation only: explicit empty dependency repository and public Central-only settings. No build, wrapper/JDK installation, model or machine acceptance.'}
 fs.writeFileSync(path.join(stage,'plan.json'),JSON.stringify(plan,null,2)+'\n',{flag:'wx'});return plan
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [workspace,...extra]=process.argv.slice(2);if(!workspace||extra.length)throw Error('One public workspace required')
 const plan=prepareColdMaven(workspace)
 console.log(JSON.stringify({plan:path.join(workspace,'.cold-maven/plan.json'),initialRepositoryEntries:plan.initialRepositoryEntries,commandExecuted:false,modelCalls:0}))
}
