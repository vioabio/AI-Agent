import {readFileSync,writeFileSync,statSync} from 'node:fs'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'

// Binary relevance, macro average; not answer correctness or evidence of causality.
export function rankingMetrics(relevant,ranked,k){
 if(!Number.isInteger(k)||k<1||k>50)throw Error('K_MUST_BE_1_TO_50')
 for(const ids of [relevant,ranked])if(!Array.isArray(ids)||ids.length>1000||new Set(ids).size!==ids.length||ids.some(id=>typeof id!=='string'||!id.trim()||id.length>200))throw Error('UNIQUE_CHUNK_IDS_REQUIRED')
 if(!relevant.length)throw Error('POSITIVE_RELEVANCE_LABELS_REQUIRED')
 const truth=new Set(relevant),top=ranked.slice(0,k),matches=top.map(id=>truth.has(id))
 const hitCount=matches.filter(Boolean).length,first=matches.indexOf(true)
 const dcg=matches.reduce((sum,hit,index)=>sum+(hit?1/Math.log2(index+2):0),0)
 const ideal=Array.from({length:Math.min(k,relevant.length)},(_,i)=>1/Math.log2(i+2)).reduce((a,b)=>a+b,0)
 return {recallAtK:hitCount/relevant.length,mrrAtK:first<0?0:1/(first+1),ndcgAtK:dcg/ideal}
}
export function evaluateRetrieval(input){
 if(input?.schemaVersion!=='retrieval-labels-v1'||!Array.isArray(input.cases)||!input.cases.length||input.cases.length>1000||typeof input.datasetSha256!=='string'||!/^[a-f0-9]{64}$/.test(input.datasetSha256))throw Error('FROZEN_LABELED_DATASET_REQUIRED')
 const ids=new Set(),rows=[]
 for(const item of input.cases){
  if(typeof item.caseId!=='string'||!item.caseId.trim()||ids.has(item.caseId)||typeof item.labelSource!=='string'||!item.labelSource.trim())throw Error('UNIQUE_CASE_AND_LABEL_SOURCE_REQUIRED')
  ids.add(item.caseId);rows.push({caseId:item.caseId,...rankingMetrics(item.relevantChunkIds,item.rankedChunkIds,input.k)})
 }
 const mean=key=>rows.reduce((sum,row)=>sum+row[key],0)/rows.length
 return {schemaVersion:'retrieval-metrics-v1',scope:'BINARY_CHUNK_RELEVANCE_NOT_ANSWER_QUALITY',datasetSha256:input.datasetSha256,k:input.k,cases:rows,macro:{recallAtK:mean('recallAtK'),mrrAtK:mean('mrrAtK'),ndcgAtK:mean('ndcgAtK')},releaseDecision:'NOT_EVALUATED'}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [input,output]=process.argv.slice(2)
 if(!input||!output)throw Error('Usage: retrieval-metrics.mjs LABELS_JSON NEW_REPORT_JSON')
 if(statSync(input).size>2000000)throw Error('INPUT_TOO_LARGE')
 const bytes=readFileSync(input);if(bytes.length>2000000)throw Error('INPUT_TOO_LARGE')
 const report=evaluateRetrieval(JSON.parse(bytes));report.inputSha256=createHash('sha256').update(bytes).digest('hex')
 writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({cases:report.cases.length,macro:report.macro,scope:report.scope}))
}
