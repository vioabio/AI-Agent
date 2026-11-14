import {readFileSync,mkdirSync,existsSync,writeFileSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomBytes,createHash} from 'node:crypto'
import {isDeepStrictEqual} from 'node:util'
import {localBase} from './evaluation.mjs'
import {publicFingerprint} from './upgrade-baseline.mjs'
import {LocalMcpClient} from './mcp-local-client.mjs'

const release='pokeapi-reference-v1',game='pokemon-scarlet-violet'
const facts=query=>({tool:'lookupPokemonReference',args:{query,dataReleaseId:release},path:'/pokemon/reference/pokemon/'+encodeURIComponent(query)+'?dataReleaseId='+release})
const build=request=>({tool:'checkPokemonBuild',args:{request},path:'/pokemon/reference/build-check',body:request})
const damage=request=>({tool:'calculateBattleDamage',args:{request},path:'/pokemon/calculations/engine-damage',body:request})
const request=(move='tackle',field={gameType:'Singles'})=>({generation:9,dataReleaseId:release,attacker:{species:'squirtle'},defender:{species:'squirtle'},move,field})
export const matrix=[
 ...['7','squirtle','杰尼龟'].map((query,i)=>({id:'FACT-'+(i+1),...facts(query),kind:'fact'})),
 {id:'BUILD-MISSING',...build({pokemonId:7,dataReleaseId:release,gameReleaseId:game}),kind:'incomplete'},
 {id:'BUILD-PARTIAL',...build({pokemonId:7,dataReleaseId:release,gameReleaseId:game,build:{ability:'torrent',nature:'modest',level:25,moves:[]}}),kind:'incomplete'},
 {id:'DAMAGE-NORMAL',...damage(request()),kind:'positive'},
 {id:'DAMAGE-RAIN',...damage(request('water-gun',{gameType:'Singles',weather:'Rain'})),kind:'positive'},
 {id:'DAMAGE-ZERO',...damage(request('water-gun',{gameType:'Singles',weather:'Harsh Sunshine'})),kind:'zero'},
 {id:'DAMAGE-DOUBLES',...damage(request('surf',{gameType:'Doubles'})),kind:'positive'},
 {id:'REJECT-REFERENCE-VERSION',...facts('7'),args:{query:'7',dataReleaseId:'wrong'},path:'/pokemon/reference/pokemon/7?dataReleaseId=wrong',kind:'reject'},
 {id:'REJECT-BUILD-GAME',...build({pokemonId:7,dataReleaseId:release,gameReleaseId:'wrong'}),kind:'reject'},
 {id:'REJECT-GENERATION',...damage({...request(),generation:8}),kind:'reject'},
 {id:'REJECT-WEATHER',...damage(request('tackle',{gameType:'Singles',weather:'invalid'})),kind:'reject'}
]
export function assessPair(item,http,mcp){
 if(item.kind==='reject')return {passed:http.status===400&&Boolean(mcp.error||mcp.result?.isError),check:'BOTH_REJECT_INVALID_INPUT'}
 if(http.status!==200||!mcp.result||mcp.result.isError)return {passed:false,check:'SUCCESS_REQUIRED'}
 let actual
 try{actual=JSON.parse(mcp.result.content.map(item=>item.text||'').join(''))}catch{return {passed:false,check:'MCP_JSON_REQUIRED'}}
 if(!isDeepStrictEqual(http.body,actual))return {passed:false,check:'EXACT_DOMAIN_JSON_MISMATCH'}
 const value=http.body
 const valid=item.kind==='fact'?value.fact?.formId===7&&value.fact?.hp===44&&value.fact?.speed===43&&value.fact?.dataReleaseId===release:
  item.kind==='incomplete'?value.status==='INCOMPLETE'&&value.request?.pokemonId===7:
  value.conditionValidation?.status==='VERIFIED'&&Number.isFinite(value.range?.minimum)&&Number.isFinite(value.range?.maximum)&&
   (item.kind==='zero'?value.range.minimum===0&&value.range.maximum===0:value.range.minimum>0&&value.range.maximum>=value.range.minimum)
 return {passed:valid,check:valid?'EXACT_DOMAIN_JSON_AND_INVARIANT':'DOMAIN_INVARIANT_FAILED'}
}
export function resultStatus(rows,cleanup){
 if(rows.some(item=>item.status==='FAIL')||cleanup.some(item=>item==='MCP_FORCED_TERMINATION'||item==='MCP_PROTOCOL_FAILURE'))return 'FAIL'
 if(rows.length!==matrix.length||rows.some(item=>item.status==='BLOCKED')||cleanup.length)return 'BLOCKED'
 return 'PASS'
}
async function run(){
 const [url,output,configPath='.local-data/mcp-client-config.json']=process.argv.slice(2)
 const base=localBase(url),root=resolve(fileURLToPath(new URL('../..',import.meta.url))),destination=resolve(output||'')
 const within=relative(resolve(root,'artifacts'),destination)
 if(!within||within.startsWith('..')||isAbsolute(within)||existsSync(destination))throw Error('NEW_ARTIFACT_DIRECTORY_REQUIRED')
 mkdirSync(destination,{recursive:true})
 const report={schemaVersion:'http-mcp-equivalence-v1',createdAt:new Date().toISOString(),status:'RUNNING',cases:[],cleanup:[],scope:'REAL_HTTP_AND_PRODUCTION_STDIO_PUBLIC_TOOLS_NOT_MODEL_QUALITY'}
 report.producerSha256=Object.fromEntries(['mcp-http-equivalence.mjs','mcp-local-client.mjs'].map(name=>[name,createHash('sha256').update(readFileSync(new URL(name,import.meta.url))).digest('hex')]))
 report.matrixSha256=createHash('sha256').update(JSON.stringify(matrix)).digest('hex')
 const cookies=new Map();let loggedIn=false,mcp
 const save=()=>writeFileSync(resolve(destination,'report.json'),JSON.stringify(report,null,2)+'\n')
 async function http(path,body){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(20000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),...(cookies.has('CSRF-TOKEN')?{'X-CSRF-TOKEN':decodeURIComponent(cookies.get('CSRF-TOKEN'))}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
  for(const line of response.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
  const text=await response.text();if(Buffer.byteLength(text)>1048576)throw Error('HTTP_RESPONSE_LIMIT')
  let data=text?JSON.parse(text):null
  if(data&&Object.hasOwn(data,'code')&&Object.hasOwn(data,'data')&&response.status===200)data=data.data
  return {status:response.status,body:data}
 }
 try{
  const session=await http('/auth/session');if(session.status!==200||session.body?.mode!=='local')throw Error('LOCAL_ACCOUNT_REQUIRED')
  const credentials={username:'hm_'+randomBytes(8).toString('hex'),password:randomBytes(24).toString('base64url'),nickname:'入口验证'}
  if((await http('/auth/local/register',credentials)).status!==201)throw Error('LOCAL_REGISTER_FAILED')
  if((await http('/auth/local/login',credentials)).status!==200)throw Error('LOCAL_LOGIN_FAILED');loggedIn=true
  const runtime=await http('/chat/runtime');if(runtime.status!==200)throw Error('RUNTIME_UNAVAILABLE')
  report.runtime=publicFingerprint(runtime.body)
  report.implementationSha256=Object.fromEntries(['PokemonReferenceController','PokemonBuildCheckController','LocalBuildCheckService','VerifiedLocalDamageEngine'].filter(k=>/^[a-f0-9]{64}$/.test(runtime.body.implementationSha256?.[k]||'')).map(k=>[k,runtime.body.implementationSha256[k]]))
  const config=JSON.parse(readFileSync(resolve(root,configPath),'utf8'));mcp=new LocalMcpClient(config.mcpServers?.['vio-pokemon'])
  report.tools=await mcp.initialize()
  for(const item of matrix){
   const row={id:item.id,input:item.args,status:'BLOCKED'}
   try{
    let started=Date.now();row.http=await http(item.path,item.body);row.httpMs=Date.now()-started
    started=Date.now();row.mcp=await mcp.call(item.tool,item.args);row.mcpMs=Date.now()-started
    const check=assessPair(item,row.http,row.mcp);row.check=check.check
    row.status=check.passed?'PASS':row.http.status>=500?'BLOCKED':'FAIL'
   }catch(error){row.reason=/^(MCP_[A-Z_]+|HTTP_RESPONSE_LIMIT)$/.test(error.message)?error.message:'TRANSPORT_OR_COLLECTION_FAILURE'}
   row.sha256=createHash('sha256').update(JSON.stringify({http:row.http,mcp:row.mcp})).digest('hex');report.cases.push(row);save()
  }
 }catch(error){report.reason=/^[A-Z_]+$/.test(error.message)?error.message:'SETUP_UNAVAILABLE'}
 finally{
  if(mcp){report.transport=await mcp.close();if(report.transport.forcedTermination)report.cleanup.push('MCP_FORCED_TERMINATION');if(report.transport.invalidFrames||report.transport.duplicateResponses)report.cleanup.push('MCP_PROTOCOL_FAILURE')}
  if(loggedIn)try{if((await http('/logout',{})).status!==204)report.cleanup.push('LOGOUT_UNVERIFIED')}catch{report.cleanup.push('LOGOUT_UNVERIFIED')}
  report.status=resultStatus(report.cases,report.cleanup);report.finishedAt=new Date().toISOString();report.counts=Object.fromEntries(['PASS','FAIL','BLOCKED'].map(status=>[status,report.cases.filter(item=>item.status===status).length]));save()
 }
 console.log(JSON.stringify({status:report.status,counts:report.counts,cleanup:report.cleanup,reason:report.reason,report:relative(root,resolve(destination,'report.json'))}));if(report.status!=='PASS')process.exitCode=1
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))await run()
