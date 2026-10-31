import{execFileSync}from'node:child_process';import{readFileSync}from'node:fs';import{resolve}from'node:path';
import{digest}from'../../../tools/campus/evaluation.mjs';
const key=process.env.PUBLICATION_KEY_SCAN;if(!key)throw Error('Missing private in-memory scan value');
const staged=execFileSync('git',['diff','--cached','--name-only','-z','--diff-filter=ACMR']).toString('utf8').split('\0').filter(Boolean);
if(!staged.length)throw Error('No staged publication');
for(const file of staged)if(/[\r\n]/.test(file)||/(?:^|\/)(?:\.run|\.idea|node_modules)\/|application-local\.yml$|\.(?:sqlite(?:-wal|-shm)?|jar|db)$/.test(file))throw Error('Excluded staged path: '+file);
const plan=JSON.parse(readFileSync('artifacts/campus/release-regression-2026-10-31/plan-v8.json'));
const requested=[...new Set([...staged,...Object.keys(plan.sources)])];
const bytes=execFileSync('git',['cat-file','--batch'],{input:requested.map(f=>':'+f).join('\n')+'\n',maxBuffer:256*1024*1024});
let offset=0,matches=0;const hashes=new Map(),normalized=new Map();
for(const file of requested){const end=bytes.indexOf(10,offset),header=bytes.subarray(offset,end).toString('ascii'),m=/^[0-9a-f]+ blob (\d+)$/.exec(header);if(!m)throw Error('Missing staged source/evidence: '+file);const size=Number(m[1]),value=bytes.subarray(end+1,end+1+size);offset=end+size+2;
 if(staged.includes(file)&&(value.includes(Buffer.from(key))||/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/.test(value.toString('utf8'))))matches++;
 hashes.set(file,digest(value));if(Object.hasOwn(plan.sources,file))normalized.set(file,value.toString('utf8').replaceAll('\r\n','\n'));
}
if(matches)throw Error('Publication secret scan failed; values suppressed');
let manifestFiles=0;
for(const dir of ['final-acceptance-v1','acceptance-gap-fix-v1','release-regression-2026-10-31']){
 const prefix='document/秋招升级/最终交付/evidence/'+dir+'/',manifest=JSON.parse(readFileSync(prefix+'manifest.json'));
 const rows=Array.isArray(manifest.files)?manifest.files:Object.entries(manifest.files).map(([file,value])=>({file,...value}));
 for(const r of rows){if(hashes.get(prefix+r.file)!==r.sha256)throw Error('Staged evidence digest mismatch: '+prefix+r.file);manifestFiles++;}
}
for(const[file,sha]of Object.entries(plan.sources)){
 const value=readFileSync(resolve(file));if(digest(value)!==sha||normalized.get(file)!==value.toString('utf8').replaceAll('\r\n','\n'))throw Error('Frozen or staged source changed: '+file);
}
console.log(JSON.stringify({stagedFiles:staged.length,stagedSecretMatches:matches,evidenceFilesVerified:manifestFiles,frozenSourcesVerified:Object.keys(plan.sources).length,sourceComparison:'raw worktree freeze plus newline-normalized Git index equality',privateConfigStaged:false},null,2));
