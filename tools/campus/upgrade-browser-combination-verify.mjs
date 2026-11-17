import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {digest} from './evaluation.mjs'
import {verifyBrowserCombination} from './stage6-browser-evidence.mjs'

const [manifestFile,output]=process.argv.slice(2)
if(!manifestFile||!output)throw Error('Usage: upgrade-browser-combination-verify.mjs INPUT_MANIFEST_JSON NEW_OUTPUT_JSON')
const manifestBytes=fs.readFileSync(manifestFile,'utf8'),manifest=JSON.parse(manifestBytes)
const groups={},directories={},sourceSha256={}
for(const mode of ['enabled','disabled']) {
  groups[mode]={};directories[mode]={}
  for(const kind of ['cards','hitl','governance']) {
    const directory=fs.realpathSync(manifest[mode][kind]),within=path.relative(fs.realpathSync('artifacts'),directory)
    assert.ok(within&&!within.startsWith('..')&&!path.isAbsolute(within))
    const file=path.join(directory,'browser-result.json');assert.ok(fs.statSync(file).size<16_777_216)
    const bytes=fs.readFileSync(file,'utf8');groups[mode][kind]=JSON.parse(bytes);directories[mode][kind]=directory
    sourceSha256[mode+'-'+kind]=digest(bytes)
  }
}
const result=verifyBrowserCombination(groups.enabled,groups.disabled,(mode,kind,file)=>{
  assert.match(file,/^[a-zA-Z0-9-]+\.json$/)
  return fs.readFileSync(path.join(directories[mode][kind],file),'utf8')
})
result.sourceSha256=sourceSha256;result.inputManifestSha256=digest(manifestBytes)
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
console.log(JSON.stringify(result))
