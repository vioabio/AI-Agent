// Explicitly select the expanded source identity without altering the S2 frozen baseline checker.
import {readFileSync,writeFileSync,mkdirSync,existsSync}from'node:fs'
import {resolve,dirname}from'node:path'
import {fileURLToPath}from'node:url'
import {checkReport}from'./s2-quality-regression.mjs'
import {digest,frozenFileDigest}from'./evaluation.mjs'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
export function checkExpandedReport(report,corpus,sha256) {
  if(report.guideCatalog?.corpusSha256!==sha256 || report.guideCatalog.catalogId!==corpus.catalogId)
    throw Error('Model run did not attest the selected expanded corpus')
  return {...checkReport(report,corpus.documents),corpusSha256:sha256,catalogId:corpus.catalogId,
    scope:'Three fixed S2 development regressions with explicitly selected expanded sources. Not heldout ablation or independent human acceptance.'}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [inputArg,outputArg,...extra]=process.argv.slice(2)
  if(!inputArg || !outputArg || extra.length)throw Error('Usage: node tools/campus/s3-source-regression.mjs OBSERVATIONS_JSON NEW_OUTPUT_DIRECTORY')
  const input=resolve(inputArg),output=resolve(outputArg)
  if(existsSync(output))throw Error('Use a new output directory')
  const file=resolve(root,'src/main/resources/pokemon-guides/expanded-v2.json'),corpus=JSON.parse(readFileSync(file,'utf8'))
  const report=JSON.parse(readFileSync(input,'utf8')),checked=checkExpandedReport(report,corpus,frozenFileDigest(file))
  checked.streaming=report.records.map(record=>{
    if(!record.eventsFile || /[/\\]/.test(record.eventsFile))throw Error('Invalid captured event file')
    const bytes=readFileSync(resolve(dirname(input),record.eventsFile));if(digest(bytes)!==record.eventsSha256)throw Error('Captured events identity mismatch')
    const events=JSON.parse(bytes.toString('utf8')),deltas=events.filter(e=>e.type==='answer.delta').map(e=>e.payload.text)
    const matches=record.status!=='COMPLETED' || deltas.length>1 && deltas.join('')===record.answer && record.checks.historyMatches===true
    if(!matches)throw Error('Streaming/final/history mismatch')
    return{caseId:record.caseId,deltaCount:deltas.length,concatenationMatches:record.status==='COMPLETED'?true:null}
  })
  checked.originalObservationSha256=digest(readFileSync(input))
  mkdirSync(output,{recursive:true});writeFileSync(resolve(output,'source-regression.json'),JSON.stringify(checked,null,2)+'\n')
  console.log(`Expanded-source fixed checks: ${checked.pass}/${checked.planned}; human acceptance not performed`)
  if(checked.failed)process.exitCode=1
}
