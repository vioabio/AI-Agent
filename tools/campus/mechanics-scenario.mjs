// Read-only public projections via isolated local accounts; never records passwords or session cookies.
import fs from 'node:fs'
import path from 'node:path'
import {randomBytes} from 'node:crypto'
import {fileURLToPath} from 'node:url'
import {localBase,digest,sameJson,loadSuite,frozenFileDigest} from './evaluation.mjs'
import {auditMechanicObservation,eventFileName} from './mechanic-observations.mjs'
import {resolveRuntimeClasses} from './reference-case.mjs'
export async function runScenario(baseArg,output,modelFile) {
 const base=localBase(baseArg);if(fs.existsSync(output))throw Error('Use a new output directory')
 const catalog=JSON.parse(fs.readFileSync('src/main/resources/pokemon-mechanics/references-v1.json','utf8')),cookies=new Map()
 const report={schemaVersion:'mechanics-http-and-observation-v1',startedAt:new Date().toISOString(),catalogSha256:digest(fs.readFileSync('src/main/resources/pokemon-mechanics/references-v1.json','utf8').replaceAll('\r\n','\n')),
  scope:'Fixed public resources and bound development observations, not full mechanics or human acceptance',rows:catalog.records.map(r=>({kind:r.kind,slug:r.slug,status:'NOT_RUN',matches:false})),modelObservations:[]}
 fs.mkdirSync(output,{recursive:true})
 const api=async(route,body,status=200)=>{
  const r=await fetch(base+route,{method:body?'POST':'GET',redirect:'error',signal:AbortSignal.timeout(30000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body?{body:JSON.stringify(body)}:{})})
  for(const line of r.headers.getSetCookie()){const [k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
  if(r.status!==status)throw Error(`HTTP ${r.status} at ${route}`)
  const value=await r.json();return value&&Object.hasOwn(value,'code')&&Object.hasOwn(value,'data')?value.data:value
 }
 try {
  if((await api('/auth/session')).mode!=='local')throw Error('Requires local accounts')
  const username='mechanics_'+randomBytes(6).toString('hex'),password=randomBytes(24).toString('base64url')
  await api('/auth/local/register',{username,password,nickname:'机制资料验证'},201);await api('/auth/local/login',{username,password})
  report.runtime=await api('/chat/runtime')
  if(report.runtime.evaluationPolicy.mode!=='READ_ONLY_TOOLS'||report.runtime.resourceReferencePolicy?.schemaVersion!=='pokemon-resource-facts-v1')throw Error('Wrong runtime policy')
  for(let i=0;i<catalog.records.length;i++){
   const expected=catalog.records[i]
   try{const actual=await api(`/pokemon/reference/resources/${expected.kind}/${expected.slug}?dataReleaseId=pokeapi-reference-v1`);report.rows[i]={kind:expected.kind,slug:expected.slug,status:'READ',matches:sameJson(actual,expected),actual}}
   catch(error){report.rows[i]={...report.rows[i],status:'FAILED',error:error.message}}
  }
  if(modelFile){const model=JSON.parse(fs.readFileSync(modelFile,'utf8'));report.modelReportSha256=digest(fs.readFileSync(modelFile));
   if(!model.sourcesUnchanged||!model.suiteUnchanged||!sameJson(model.runtime.implementationSha256,report.runtime.implementationSha256)
     ||model.sourceSha256['src/main/resources/pokemon-mechanics/references-v1.json']!==digest(fs.readFileSync('src/main/resources/pokemon-mechanics/references-v1.json')))throw Error('Changed model runtime or source identity')
   for(const cls of resolveRuntimeClasses(model.sourceSha256,report.runtime.implementationSha256))if(digest(fs.readFileSync(cls.file))!==cls.sha256)throw Error('Stale runtime '+cls.name)
   if(!/^evaluation\/campus\/[a-z0-9-]+$/.test(model.suitePath))throw Error('Unregistered source directory')
   const suite=loadSuite(model.suitePath);if(frozenFileDigest(path.join(model.suitePath,'manifest.json'))!==model.datasetSha256)throw Error('Changed suite')
   for(const record of model.records){const raw=fs.readFileSync(path.join(path.dirname(modelFile),eventFileName(record.eventsFile)));if(digest(raw)!==record.eventsSha256)throw Error('Changed event file');
    const item=suite.cases.find(c=>c.id===record.caseId);if(!item)throw Error('Unknown case')
    const reference=suite.fixtures.references?.[item.expected.referenceFixture]
    report.modelObservations.push(auditMechanicObservation(record,JSON.parse(raw),catalog.records,{requireRead:/(?:请|先|需要|帮我)(?:再|实际)?(?:查询|查找|读取|核对).{0,35}(?:特性|招式|道具)/.test(item.prompt),
     requiredKinds:item.expected.resourceKinds||[],allowedByKind:item.expected.resourceKinds?{ability:reference?.availableAbilitySlugs||[],move:reference?.availableMoveSlugs||[]}:null,memberName:item.expected.resourceKinds?reference?.fact.speciesNameZh:null}));}
  }
 }catch(error){report.setupError=error.message;process.exitCode=1}
 report.summary={resources:catalog.records.length,matched:report.rows.filter(r=>r.matches).length,modelRecords:report.modelObservations.length,
  knownSuccessfulReads:report.modelObservations.reduce((s,r)=>s+r.successfulKnown,0),displayedReads:report.modelObservations.reduce((s,r)=>s+r.displayed,0),unmatchedReads:report.modelObservations.reduce((s,r)=>s+r.unmatched,0),missingRequiredReads:report.modelObservations.filter(r=>!r.requiredReadPresent).length,
  requiredCategoryChecks:report.modelObservations.reduce((s,r)=>s+r.requiredKinds.length,0),missingCategories:report.modelObservations.reduce((s,r)=>s+r.missingKinds.length,0),humanReviewed:0}
 report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(output,'observations.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
 if(report.summary.matched!==report.summary.resources||report.summary.unmatchedReads||report.summary.missingRequiredReads)process.exitCode=1
 console.log(JSON.stringify({...report.summary,setupError:report.setupError}));return report
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const [base,out,model,...extra]=process.argv.slice(2);if(!base||!out||extra.length)throw Error('Usage: node tools/campus/mechanics-scenario.mjs LOCAL_API_BASE NEW_OUTPUT_DIRECTORY [MODEL_OBSERVATIONS_JSON]');await runScenario(base,out,model)}
