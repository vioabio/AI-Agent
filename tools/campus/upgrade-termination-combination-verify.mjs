import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {digest} from './evaluation.mjs'
import {verifyTerminationCombination} from './stage6-termination-evidence.mjs'

const [enabledDirectory,disabledDirectory,output]=process.argv.slice(2)
if(!enabledDirectory||!disabledDirectory||!output)throw Error('Usage: upgrade-termination-combination-verify.mjs ENABLED_DIRECTORY DISABLED_DIRECTORY NEW_OUTPUT_JSON')
const directories={},reports={},sources={}
for(const [mode,input] of [['enabled',enabledDirectory],['disabled',disabledDirectory]]) {
  const directory=fs.realpathSync(input),relative=path.relative(fs.realpathSync('artifacts'),directory)
  assert.ok(relative&&!relative.startsWith('..')&&!path.isAbsolute(relative))
  const file=path.join(directory,'browser-result.json');assert.ok(fs.statSync(file).size<16_777_216)
  const bytes=fs.readFileSync(file,'utf8');reports[mode]=JSON.parse(bytes);directories[mode]=directory;sources[mode]=digest(bytes)
}
const report=verifyTerminationCombination(reports.enabled,reports.disabled,(mode,file)=>{
  assert.match(file,/^[a-zA-Z0-9_-]+\.json$/)
  return fs.readFileSync(path.join(directories[mode],file),'utf8')
})
report.sources=sources
fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
console.log(JSON.stringify(report))
