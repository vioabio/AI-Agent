import {readFileSync, mkdirSync, writeFileSync, existsSync} from 'node:fs'
import {resolve, relative, isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {execFileSync} from 'node:child_process'
import {digest, frozenFileDigest} from './evaluation.mjs'

export const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
export const suitePath = resolve(root, 'evaluation/upgrade-v1.1/u0')
export function loadFrozenSuite(directory = suitePath) {
  const json = file => JSON.parse(readFileSync(resolve(directory,file),'utf8'))
  const manifest=json('manifest.json')
  if(manifest.schemaVersion!=='upgrade-dataset-v1') throw Error('Unsupported manifest')
  for(const file of ['cases.json','rubric.json']) if(frozenFileDigest(resolve(directory,file))!==manifest.files[file]) throw Error('Frozen file mismatch: '+file)
  const cases=json('cases.json'),rubric=json('rubric.json')
  if(cases.length!==40||manifest.caseCount!==40||new Set(cases.map(c=>c.id)).size!==40)throw Error('Expected 40 unique cases')
  for(const category of ['facts','retrieval','conversation','calculation','authorization']) {
    const rows=cases.filter(c=>c.category===category)
    if(rows.length!==8||rows.filter(c=>c.split==='dev').length!==4||rows.filter(c=>c.split==='heldout').length!==4)throw Error('Unbalanced split')
  }
  const corpus=resolve(root,manifest.corpus.path)
  if(!corpus.startsWith(root+'/')&&!corpus.startsWith(root+'\\'))throw Error('Corpus outside workspace')
  if(frozenFileDigest(corpus)!==manifest.corpus.sha256)throw Error('Corpus changed; baseline needs a new manifest')
  const ids=new Set(JSON.parse(readFileSync(corpus,'utf8')).documents.map(d=>d.id))
  for(const c of cases) {
    if(!['dev','heldout'].includes(c.split)||!c.turns?.length||c.turns.some(t=>typeof t!=='string'||!t.trim())||!c.expected?.rubric)throw Error('Invalid case')
    if(c.expected.relevantDocumentIds.some(id=>!ids.has(id)))throw Error('Unknown source label')
  }
  return {manifest,cases,rubric,datasetSha256:frozenFileDigest(resolve(directory,'manifest.json'))}
}

// Never hashes arbitrary process environment or private application configuration.
export function publicFingerprint(input) {
  const allowed=['profiles','configuredProvider','configuredModelId','promptVersion','features','upgradeFeatures','agentCollaboration','contextBudgetPolicy','taskBudgetPolicy','domainTracingPolicy']
  const allowedFeature=['answerStreaming','conversationConstraints','taskBudget','contextBudget']
  const result={}
  for(const key of allowed) {
    const value=input[key]
    if(key==='features'&&value&&typeof value==='object')result.features=Object.fromEntries(allowedFeature.filter(k=>typeof value[k]==='boolean').map(k=>[k,value[k]]))
    else if(key==='upgradeFeatures'&&value&&typeof value==='object')result.upgradeFeatures=Object.fromEntries(['answerBlocks','skills','contextAssembly','rerank','domainTracing'].filter(k=>typeof value[k]==='boolean').map(k=>[k,value[k]]))
    else if(key==='domainTracingPolicy'&&value&&typeof value==='object') {
      result.domainTracingPolicy={}
      if(typeof value.sampleRate==='number'&&Number.isFinite(value.sampleRate)&&value.sampleRate>=0&&value.sampleRate<=1)result.domainTracingPolicy.sampleRate=value.sampleRate
      if(typeof value.automaticTracing==='boolean')result.domainTracingPolicy.automaticTracing=value.automaticTracing
    }
    else if(['profiles','configuredProvider','configuredModelId','promptVersion'].includes(key)) {
      if(key==='profiles'&&Array.isArray(value))result[key]=value.filter(v=>typeof v==='string'&&/^[a-z0-9,-]{1,60}$/i.test(v))
      else if(typeof value==='string'&&/^[a-z0-9._/-]{1,100}$/i.test(value)&&!/^sk-/i.test(value))result[key]=value
    } else if(value&&typeof value==='object') {
      // Only documented scalar budget/switch fields, never pass through nested objects.
      const keys=['specialistsEnabled','maxSpecialistModelCalls','maxSpecialistToolCalls','maxParallelAgents','contextTokens','historyTokens','historyTurns','safetyPercent','outputReserve','maxModelCalls','tokenLimit']
      result[key]=Object.fromEntries(keys.filter(k=>typeof value[k]==='boolean'||(typeof value[k]==='number'&&Number.isFinite(value[k]))).map(k=>[k,value[k]]))
    }
  }
  return {configuration:result,sha256:digest(result)}
}

export function collectionFailure(error) {
  if(error?.message==='RUNTIME_COMPONENTS_UNAVAILABLE')return 'RUNTIME_COMPONENTS_UNAVAILABLE'
  if(error?.message==='CANDIDATE_CORPUS_MISMATCH')return 'CANDIDATE_CORPUS_MISMATCH'
  if(/^HTTP_[1-5][0-9]{2}$/.test(error?.message||''))return error.message
  if(['ECONNREFUSED','ETIMEDOUT','ENOTFOUND'].includes(error?.cause?.code))return 'TRANSPORT_'+error.cause.code
  if(error?.name==='TimeoutError')return 'TRANSPORT_TIMEOUT'
  return 'COLLECTION_UNAVAILABLE'
}
export function prepare(output,split='dev') {
  if(!['dev','heldout'].includes(split))throw Error('Explicit dev or heldout split required')
  const suite=loadFrozenSuite(),destination=resolve(output)
  const within=relative(resolve(root,'artifacts'),destination)
  if(!within||within==='..'||within.startsWith('..\\')||within.startsWith('../')||isAbsolute(within))throw Error('Output must be a new directory under artifacts')
  if(existsSync(destination))throw Error('Refuse to overwrite evidence')
  // Evidence archives can make a staged diff exceed Node's default 1 MiB buffer.
  // Keep an explicit bound and avoid exposing command output on failure.
  const git=(...args)=>{
    try {
      return execFileSync('git',args,{cwd:root,encoding:'utf8',maxBuffer:64*1024*1024,timeout:30000,windowsHide:true}).trim()
    } catch {
      throw Error('SOURCE_STATE_UNAVAILABLE')
    }
  }
  const report={schemaVersion:'upgrade-baseline-v1',createdAt:new Date().toISOString(),candidateCommit:git('rev-parse','HEAD'),baselineCommit:git('rev-parse','v1.0.0^{}'),
    sourceStateSha256:digest(git('diff','--binary','HEAD')),datasetSha256:suite.datasetSha256,corpusSha256:suite.manifest.corpus.sha256,
    runtime:null,mode:'PREPARED_NOT_EXECUTED',split,records:suite.cases.filter(c=>c.split===split).map(c=>({caseId:c.id,verdict:'NOT_RUN',runId:null,ttftMs:null,totalMs:null,providerUsage:null,estimatedUsage:null,review:null}))}
  mkdirSync(destination,{recursive:true});writeFileSync(resolve(destination,'baseline.json'),JSON.stringify(report,null,2)+'\n')
  return report
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [command,...args]=process.argv.slice(2)
  if(command==='validate'&&!args.length){const s=loadFrozenSuite();console.log(JSON.stringify({valid:true,count:s.cases.length,datasetSha256:s.datasetSha256}))}
  else if(command==='prepare'&&args.length===2)console.log(JSON.stringify({mode:prepare(args[0],args[1]).mode}))
  else throw Error('Usage: upgrade-baseline.mjs validate | prepare artifacts/NEW_DIRECTORY dev|heldout')
}
