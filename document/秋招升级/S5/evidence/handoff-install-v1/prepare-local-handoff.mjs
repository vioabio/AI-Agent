import fs from 'node:fs'
import path from 'node:path'
import {execFileSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {createSmokeWorkspace} from './local-smoke-workspace.mjs'
import {inspectDemoBundle} from './prepare-demo-reference.mjs'
import {digest} from './evaluation.mjs'
const helpers=['tools/reference-cache.mjs','tools/campus/demo-readiness.mjs','tools/campus/prepare-demo-reference.mjs','tools/campus/evaluation.mjs','tools/campus/download-demo-via-proxy.mjs']
export function prepareLocalHandoff(root,output,bundle,candidates){
 root=fs.realpathSync(root)
 if(!inspectDemoBundle(bundle).ready)throw Error('Verified complete public bundle required')
 const inputs=[]
 for(const file of helpers){
  const source=path.join(root,file),relative=path.relative(root,fs.realpathSync(source))
  if(relative.startsWith('..')||path.isAbsolute(relative)||fs.lstatSync(source).isSymbolicLink())throw Error('Unsafe helper source')
  inputs.push({file,bytes:fs.readFileSync(source)})
 }
 const bundleManifest=JSON.parse(fs.readFileSync(path.join(bundle,'manifest.json')))
 for(const file of ['manifest.json',...bundleManifest.files.map(f=>f.file)])inputs.push({file:'reference-bundle/'+file,bytes:fs.readFileSync(path.join(bundle,file))})
 const sources=createSmokeWorkspace(root,output,candidates),destination=path.resolve(output)
 const extraSha256={}
 const write=(file,bytes)=>{const target=path.join(destination,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,bytes,{flag:'wx'});extraSha256[file]=digest(fs.readFileSync(target))}
 for(const {file,bytes} of inputs)write(file,bytes)
 write('settings/public-user-settings.xml','<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors><mirror><id>public-central-only</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>\n')
 write('settings/public-global-settings.xml','<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"></settings>\n')
 write('HANDOFF.md',`# 本地安装交接\n\n这是公开源码与固定资料快照，不含模型Key、账号数据库、node_modules、JDK或构建产物。打开本目录的PowerShell。\n\n1. 安装JDK21（含javac）、设置JAVA_HOME；准备Node20.x至少20.19或22.12及以上与npm。\n2. 执行 npm --prefix tools/pokemon-engine ci 和 npm --prefix vio-ai-agent-frontend ci。\n3. 执行 node tools/campus/demo-readiness.mjs --bundle reference-bundle --out readiness-new.json。未安装依赖时返回2并提示缺项；报告必须是新文件。\n4. 执行 node tools/reference-cache.mjs import --bundle reference-bundle --out pokeapi-cache。目标必须不存在，资料读取日期保留原值。\n5. 执行 .\\mvnw.cmd -s settings/public-user-settings.xml -gs settings/public-global-settings.xml '-Dmaven.test.skip=true' package。本交接目录不含测试源码，不能声称执行了后端测试。\n6. 本地模型设置请在本机环境中配置，不把Key写入交接目录；公开application-local.example.yml是配置参考。无有效Key时不能验收真实问答。\n7. 无模型基础演练按SMOKE.md使用13059/18259；真实问答须另行配置模型。启动前检查是否已有服务，勿结束不属于自己的进程。\n\n需要重新下载资料时使用 tools/campus/prepare-demo-reference.mjs 的新artifacts目录；有明确本机代理时可用 download-demo-via-proxy.mjs --proxy 参数（需Node支持--use-env-proxy）。不要把附带快照当作以后重新联网下载。\n\n预检通过仅证明公开输入与安装元数据，不代表已构建、已启动、模型回答正确或全新机器验收。\n`)
 write('SMOKE.md',`# 隔离的无模型基础启动\n\n先完成HANDOFF.md的安装、预检、导入及打包。在本目录的PowerShell执行下列命令；18259被占用时先核对归属，不结束别人的进程。此命令使用无效模型Key，只用于登录/缓存图鉴/队伍/引擎基础检查，不用于真实问答。\n\n~~~powershell\n& "$env:JAVA_HOME\\bin\\java.exe" -jar target/vio-ai-agent-0.0.1-SNAPSHOT.jar '--spring.config.location=classpath:/application.yml,classpath:/application-local.example.yml' --spring.profiles.active=local --server.address=127.0.0.1 --server.port=18259 --spring.ai.dashscope.api-key=smoke-placeholder-not-real-key "--vio.pokemon.reference.path=$PWD/pokeapi-cache" --vio.pokemon.reference.proxy=http://127.0.0.1:9 '--vio.http.cors.allowed-origins[0]=http://localhost:3000' '--vio.http.cors.allowed-origins[1]=http://127.0.0.1:13059' --spring.ai.mcp.client.enabled=false --vio.tools.search.enabled=false --vio.tools.high-risk.enabled=false --vio.agent.v1.enabled=false --vio.run.durable-resume=false --vio.run.model-cache.enabled=false\n~~~\n\n另开本目录终端执行 npm --prefix vio-ai-agent-frontend run dev:demo，访问 http://127.0.0.1:13059 。仅验证固定资料和基础操作，缺少的招式/道具等资源会明确失败。停止时在各自前台终端按Ctrl+C。不要在后端运行期间重新编译。\n\n真实问答需要另行在本机配置受支持模型，此交接目录没有私有模型profile或Key。不要把本命令冒充真实模型验收。\n`)
 const report={schemaVersion:'local-handoff-v1',sourceFiles:Object.keys(sources.sourceSha256).length,sourceManifestSha256:digest(fs.readFileSync(path.join(destination,'smoke-source-manifest.json'))),extraSha256,
  privateConfigCopied:false,existingDatabasesCopied:false,dependenciesOrBuildOutputsCopied:false,modelCalls:0,installersExecuted:false,cleanMachineAcceptance:false,
  scope:'Portable public source, explicit helper closure and verified old public snapshots. Preparation only; no installer, build, service or model acceptance.'}
 fs.writeFileSync(path.join(destination,'handoff-manifest.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 return report
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [output,bundle,...extra]=process.argv.slice(2);if(!output||!bundle||extra.length)throw Error('Fresh artifacts directory and verified public bundle required')
 const candidates=execFileSync('git',['ls-files','-c','-o','--exclude-standard','-z'],{encoding:'utf8'}).split('\0').filter(Boolean)
 const report=prepareLocalHandoff(process.cwd(),output,bundle,candidates)
 console.log(JSON.stringify({sourceFiles:report.sourceFiles,extraFiles:Object.keys(report.extraSha256).length,modelCalls:0,cleanMachineAcceptance:false}))
}
