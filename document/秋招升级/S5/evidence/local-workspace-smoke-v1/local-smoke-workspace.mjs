import fs from 'node:fs'
import path from 'node:path'
import {execFileSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'
export function smokeSourceAllowed(file){
 if(typeof file!=='string'||file.includes('\\')||file.split('/').some(s=>s==='..'||s==='.')||path.isAbsolute(file))return false
 if(/(^|\/)(?:node_modules|target|dist|artifacts|\.git|\.tools|\.local-data|test-results|logs)(\/|$)/i.test(file)||/(^|\/)(?:\.env(?:\.|$)|\.npmrc$|\.netrc$)|\.(?:sqlite(?:-wal|-shm)?|db|key|pem|pfx|log|jsonl)$/i.test(file)||/(^|\/)mcp-servers\.json(?:\.|$)/i.test(file))return false
 if(/(^|\/)application[^/]*\.(?:yml|yaml|properties)$/i.test(file)&&!['src/main/resources/application.yml','src/main/resources/application-local.example.yml'].includes(file))return false
 if(['pom.xml','mvnw','mvnw.cmd'].includes(file))return true
 return ['src/main/','.mvn/wrapper/','evaluation/v1.1/','tools/pokemon-engine/','vio-ai-agent-frontend/src/','vio-ai-agent-frontend/public/'].some(p=>file.startsWith(p))
  ||/^vio-ai-agent-frontend\/(?:package(?:-lock)?\.json|index\.html|vite\.config\.js|tsconfig[^/]*\.json)$/.test(file)
}
export function createSmokeWorkspace(root,destination,candidates){
 root=fs.realpathSync(root);destination=path.resolve(destination)
 const relative=path.relative(path.join(root,'artifacts'),destination)
 if(!relative||relative.startsWith('..')||path.isAbsolute(relative)||fs.existsSync(destination))throw Error('Fresh destination inside workspace artifacts required')
 // Check existing ancestors to prevent a symlinked parent escaping the workspace.
 let parent=path.dirname(destination);while(!fs.existsSync(parent))parent=path.dirname(parent)
 const realParent=fs.realpathSync(parent),parentRelative=path.relative(root,realParent)
 if(parentRelative.startsWith('..')||path.isAbsolute(parentRelative))throw Error('Destination escapes workspace')
 const selected=[...new Set(candidates)].filter(smokeSourceAllowed),files=[]
 for(const file of selected){const source=path.join(root,file),real=fs.realpathSync(source),rel=path.relative(root,real)
  if(rel.startsWith('..')||path.isAbsolute(rel)||fs.lstatSync(source).isSymbolicLink()||!fs.statSync(source).isFile())throw Error('Invalid public source file')
  files.push({file,source,sha256:digest(fs.readFileSync(source))})
 }
 for(const required of ['pom.xml','mvnw.cmd','src/main/resources/application.yml','src/main/resources/application-local.example.yml','vio-ai-agent-frontend/package.json','vio-ai-agent-frontend/package-lock.json','tools/pokemon-engine/package.json','tools/pokemon-engine/package-lock.json'])if(!files.some(f=>f.file===required))throw Error('Missing required public source '+required)
 fs.mkdirSync(destination,{recursive:true})
 for(const item of files){const target=path.join(destination,item.file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(item.source,target);if(digest(fs.readFileSync(target))!==item.sha256)throw Error('Copied source changed')}
 const manifest={schemaVersion:'public-source-smoke-workspace-v1',scope:'Fresh source workspace only. External JDK and Maven/npm caches may be reused; not an empty-machine installation.',privateConfigCopied:false,existingDatabasesCopied:false,existingBuildOutputsCopied:false,sourceSha256:Object.fromEntries(files.map(f=>[f.file,f.sha256]))}
 fs.writeFileSync(path.join(destination,'smoke-source-manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});return manifest
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [destination,...extra]=process.argv.slice(2);if(!destination||extra.length)throw Error('Fresh artifacts workspace required')
 const candidates=execFileSync('git',['ls-files','-c','-o','--exclude-standard','-z'],{encoding:'utf8'}).split('\0').filter(Boolean)
 const manifest=createSmokeWorkspace(process.cwd(),destination,candidates);console.log(JSON.stringify({destination,publicFiles:Object.keys(manifest.sourceSha256).length,privateConfigCopied:false}))
}
