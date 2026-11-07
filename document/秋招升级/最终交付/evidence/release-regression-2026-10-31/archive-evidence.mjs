import{readdirSync,statSync,lstatSync,readFileSync,writeFileSync,mkdirSync,copyFileSync,existsSync}from'node:fs';
import{resolve,relative,dirname,extname}from'node:path';
import{digest}from'../../../tools/campus/evaluation.mjs';
const source=resolve('artifacts/campus/release-regression-2026-10-31');
const target=resolve('document/秋招升级/最终交付/evidence/release-regression-2026-10-31');
if(existsSync(target))throw Error('Evidence archive already exists');
const files=[];
function walk(dir){for(const e of readdirSync(dir,{withFileTypes:true})){
 const path=resolve(dir,e.name);if(lstatSync(path).isSymbolicLink())throw Error('No symlink permitted');
 if(e.isDirectory())walk(path);else if(['.json','.md','.log','.mjs','.ps1','.java','.dump','.dumpstream'].includes(extname(e.name)))files.push(path);
}}
for(const e of readdirSync(source,{withFileTypes:true})){
 const path=resolve(source,e.name);
 if(e.isDirectory()&&/^(?:fresh-|known-|confirmation-|final-new-|publish-new-|v[678]-|initial-sources$|collectors$)/.test(e.name))walk(path);
 else if(e.isFile()&&['.json','.md','.log','.mjs','.ps1'].includes(extname(e.name)))files.push(path);
}
mkdirSync(target,{recursive:true});const hashes={};
for(const path of files.sort()){
 const rel=relative(source,path).replaceAll('\\','/');
 if(/(?:\.sqlite|\.jar|node_modules|\.run\/|application-local\.yml)/.test(rel))throw Error('Excluded file in archive selection');
 const out=resolve(target,rel);mkdirSync(dirname(out),{recursive:true});copyFileSync(path,out);
 hashes[rel]={sha256:digest(readFileSync(out)),bytes:statSync(out).size};
}
writeFileSync(resolve(target,'manifest.json'),JSON.stringify({schemaVersion:'release-regression-evidence-v1',createdAt:new Date().toISOString(),sourceRoot:'artifacts/campus/release-regression-2026-10-31',fileCount:files.length,totalBytes:Object.values(hashes).reduce((n,r)=>n+r.bytes,0),humanReviewed:0,privateConfigCopied:false,modelKeyCopied:false,databasesCopied:false,jarsCopied:false,dependenciesCopied:false,note:'Original first attempts and setup failures retained. Execution harnesses and current review-writing helpers are source references; helpers are not an automatic judge and review JSON (including amended opinions) is authoritative. Artifact-relative paths require the original workspace layout. Captured collectors preserve exact versions. See parent report for new-input versus regression scope.',files:hashes},null,2)+'\n');
console.log(JSON.stringify({fileCount:files.length,totalBytes:Object.values(hashes).reduce((n,r)=>n+r.bytes,0)}));
