import {readFileSync,statSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'
import {evaluateRetrieval} from './retrieval-metrics.mjs'

const hash=value=>createHash('sha256').update(value).digest('hex')
const sha=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value)
const identity=row=>JSON.stringify({query:row.querySha256,labels:[...row.relevantChunkIds].sort(),source:row.labelSource})
export function compareRetrieval(baseline,candidate){
 // Both sides are validated before constructing a comparison; absent evidence never means zero.
 const a=evaluateRetrieval(baseline),b=evaluateRetrieval(candidate)
 if(!sha(baseline.corpusSha256)||!sha(candidate.corpusSha256)||baseline.corpusSha256!==candidate.corpusSha256||a.datasetSha256!==b.datasetSha256||a.k!==b.k)throw Error('DATASET_CORPUS_OR_K_MISMATCH')
 const method=value=>typeof value==='string'&&/^[a-zA-Z0-9_.+-]{1,80}$/.test(value)&&!value.startsWith('sk-')
 if(!method(baseline.method)||!method(candidate.method)||baseline.method===candidate.method)throw Error('DISTINCT_METHOD_IDENTITIES_REQUIRED')
 if(a.cases.length!==b.cases.length)throw Error('PAIRED_CASES_REQUIRED')
 const candidateRows=new Map(candidate.cases.map(row=>[row.caseId,row]))
 for(const row of baseline.cases){
  const other=candidateRows.get(row.caseId)
  if(!other||!sha(row.querySha256)||!sha(other.querySha256)||identity(row)!==identity(other))throw Error('QUERY_OR_LABEL_MISMATCH')
 }
 const metrics=['recallAtK','mrrAtK','ndcgAtK'],difference=(x,y)=>Object.fromEntries(metrics.map(key=>[key,y[key]-x[key]]))
 const paired=a.cases.map(row=>({caseId:row.caseId,baseline:row,candidate:b.cases.find(other=>other.caseId===row.caseId),delta:difference(row,b.cases.find(other=>other.caseId===row.caseId))}))
 return {schemaVersion:'retrieval-comparison-v1',status:'COMPARABLE',scope:'DECLARED_PAIRED_CHUNK_LABELS_NOT_ANSWER_OR_PROVIDER_ATTESTATION',datasetSha256:a.datasetSha256,corpusSha256:baseline.corpusSha256,k:a.k,
  labelsSha256:hash(JSON.stringify(baseline.cases.map(row=>({caseId:row.caseId,identity:identity(row)})).sort((x,y)=>x.caseId.localeCompare(y.caseId)))),
  methods:{baseline:baseline.method,candidate:candidate.method},macro:{baseline:a.macro,candidate:b.macro,delta:difference(a.macro,b.macro)},cases:paired,releaseDecision:'NOT_EVALUATED'}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [baseline,candidate,output]=process.argv.slice(2)
 if(!baseline||!candidate||!output)throw Error('Usage: retrieval-comparison.mjs BASELINE_JSON CANDIDATE_JSON NEW_REPORT_JSON')
 const read=file=>{if(statSync(file).size>2000000)throw Error('INPUT_TOO_LARGE');const bytes=readFileSync(file);if(bytes.length>2000000)throw Error('INPUT_TOO_LARGE');return bytes}
 const a=read(baseline),b=read(candidate),report=compareRetrieval(JSON.parse(a),JSON.parse(b))
 report.inputSha256={baseline:hash(a),candidate:hash(b)}
 writeFileSync(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({status:report.status,cases:report.cases.length,macro:report.macro,releaseDecision:report.releaseDecision}))
}
