import {spawn} from 'node:child_process'
import {createInterface} from 'node:readline'
import assert from 'node:assert/strict'
import {access} from 'node:fs/promises'
import {join} from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {McpResponseWindow} from './mcp-response-window.mjs'
const [java,classpath,main,cache,mode]=process.argv.slice(2)
if(!java||!classpath||!main||!cache)throw Error('MCP_PROBE_ARGUMENTS_REQUIRED')
const child=spawn(java,['-cp',classpath,main,cache],{stdio:['pipe','pipe','pipe'],windowsHide:true})
const pending=new Map();let next=0,invalidStdout=false,stderrBytes=0,parseErrors=0,duplicateReplies=0,privatePayloadLogged=false
let enduranceReport
const received=new McpResponseWindow()
child.stderr.on('data',chunk=>{stderrBytes+=chunk.length;if(chunk.toString().includes('malformed-private-payload'))privatePayloadLogged=true})
const exited=new Promise(resolve=>child.once('exit',code=>{for(const p of pending.values()){clearTimeout(p.timer);p.reject(Error('MCP_PROCESS_EXITED'))}pending.clear();resolve(code)}))
child.on('error',()=>{for(const p of pending.values())p.reject(Error('MCP_PROCESS_FAILED'))})
const lines=createInterface({input:child.stdout})
lines.on('line',line=>{
 let msg;try{msg=JSON.parse(line)}catch{invalidStdout=true;return}
 if(msg.id===null&&msg.error?.code===-32700){parseErrors++;return}
 if(!Object.hasOwn(msg,'id'))return
 try{received.observe(msg.id,next)}catch(error){if(error.message==='MCP_DUPLICATE_RESPONSE')duplicateReplies++;else invalidStdout=true;return}
 const p=pending.get(msg.id);if(!p)return;pending.delete(msg.id);clearTimeout(p.timer);p.resolve(msg)
})
function request(method,params){const id=++next;return new Promise((resolve,reject)=>{
 const timer=setTimeout(()=>{pending.delete(id);reject(Error(`MCP_PROBE_TIMEOUT method=${method} id=${id}`))},12000)
 pending.set(id,{resolve,reject,timer});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n')
})}
const text=result=>result.content.map(x=>x.text||'').join('')
const call=(name,args)=>request('tools/call',{name,arguments:args})
try{
 if(mode==='oversize'){
  // Measure oversized-frame shutdown after readiness, not JVM cold startup.
  assert.deepEqual((await request('ping',{})).result,{})
  child.stdin.write('x'.repeat(24577))
  let timer;try{await Promise.race([exited,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('MCP_DISCONNECT_TIMEOUT')),6000)})])}finally{clearTimeout(timer)}
  assert.equal(invalidStdout,false);console.log(JSON.stringify({status:'PASS',mode}));
 }else{
 const initialPing=await request('ping',{});assert.deepEqual(initialPing.result,{})
 const early=await call('lookupPokemonReference',{query:'7',dataReleaseId:'pokeapi-reference-v1'})
 assert.equal(early.error.message,'MCP_NOT_INITIALIZED')
 child.stdin.write('{malformed-private-payload\n'+ '{"jsonrpc":"2.0","id":5,"id":6,"method":"ping"}\n')
 const hello=await request('initialize',{protocolVersion:'2024-11-05',capabilities:{},clientInfo:{name:'independent-node-probe',version:'1'}})
 assert.equal(hello.result.protocolVersion,'2024-11-05')
 assert.equal(parseErrors,2)
 child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n')
 const list=await request('tools/list',{})
 assert.deepEqual(list.result.tools.map(x=>x.name).sort(),['calculateBattleDamage','checkPokemonBuild','lookupPokemonReference'])
 const fact=await call('lookupPokemonReference',{query:'7',dataReleaseId:'pokeapi-reference-v1'})
 assert.equal(fact.result.isError,false);assert.equal(JSON.parse(text(fact.result)).fact.hp,44)
 const build=await call('checkPokemonBuild',{request:{pokemonId:7,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet'}})
 assert.equal(build.result.isError,false);assert.equal(JSON.parse(text(build.result)).status,'INCOMPLETE')
 const damage=await call('calculateBattleDamage',{request:{generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle'},defender:{species:'squirtle'},move:'tackle',field:{gameType:'Singles'}}})
 assert.equal(damage.result.isError,false)
 const number=JSON.parse(text(damage.result));assert.equal(number.conditionValidation.status,'VERIFIED');assert.ok(number.range.maximum>=number.range.minimum)
 const bad=await call('lookupPokemonReference',{query:'7',dataReleaseId:'wrong'});assert.equal(bad.result.isError,true)
 const spoof=await call('lookupPokemonReference',{query:'7',dataReleaseId:'pokeapi-reference-v1',userId:'other-account'});assert.equal(spoof.result.isError,true)
 const unknown=await call('readCurrentTeam',{teamId:'other-account'});assert.ok(unknown.error||unknown.result?.isError)
 if(main.endsWith('McpStdioFixtureMain')){
  const slowId=next+1
  const slow=call('lookupPokemonReference',{query:'slow',dataReleaseId:'pokeapi-reference-v1'})
  async function marker(name){for(let i=0;i<100;i++){try{await access(join(cache,name));return}catch{}await delay(25)}throw Error('FIXTURE_MARKER_TIMEOUT')}
  await marker('slow-started')
 if(mode==='eof'||mode==='duplicate'){
  slow.catch(()=>{})
  if(mode==='eof')child.stdin.end()
  else child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:slowId,method:'ping'})+'\n')
  let timer;try{await Promise.race([exited,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('MCP_DISCONNECT_TIMEOUT')),6000)})])}finally{clearTimeout(timer)}
  await marker('slow-stopped');assert.equal(invalidStdout,false)
  console.log(JSON.stringify({status:'PASS',mode}));
 }else{
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/cancelled',params:{requestId:String(slowId)}})+'\n')
  await request('ping',{})
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/cancelled',params:{requestId:slowId}})+'\n')
  const cancelled=await slow;assert.equal(cancelled.result.isError,true);assert.equal(text(cancelled.result),'MCP_REQUEST_CANCELLED')
  await marker('slow-stopped')
  const recovery=await call('lookupPokemonReference',{query:'7',dataReleaseId:'pokeapi-reference-v1'});assert.equal(recovery.result.isError,false)
 }
 }
 if(!mode||mode==='slow-consumer'){
 if(mode==='slow-consumer')child.stdout.pause()
 const pendingBurst=Promise.all(Array.from({length:80},()=>request(mode==='slow-consumer'?'tools/list':'ping',{})))
 if(mode==='slow-consumer'){await delay(2000);child.stdout.resume()}
 const burst=await pendingBurst
 assert.equal(burst.length,80);assert.ok(burst.every(x=>(mode==='slow-consumer'?Array.isArray(x.result?.tools):Boolean(x.result))||x.error?.message==='MCP_BUSY'))
 await request('ping',{})
 assert.equal(duplicateReplies,0)
 assert.equal(invalidStdout,false)
 assert.equal(privatePayloadLogged,false)
 console.log(JSON.stringify({status:'PASS',protocol:'2024-11-05',mode:mode||'normal',checks:main.endsWith('McpStdioFixtureMain')?15:13,normalTools:3,stderrBytes}))
 }
 if(mode==='endurance'){
 const started=performance.now(),samples=[]
 for(let batch=0;batch<5;batch++){
  for(let i=0;i<1000;i++)assert.deepEqual((await request('ping',{})).result,{})
  const read=await call('lookupPokemonReference',{query:'7',dataReleaseId:'pokeapi-reference-v1'})
  assert.equal(read.result.isError,false);assert.equal(JSON.parse(text(read.result)).fact.hp,44)
  const rejected=await call('lookupPokemonReference',{query:'7',dataReleaseId:'wrong'})
  assert.equal(rejected.result.isError,true)
  child.stdout.pause()
  const burst=Promise.all(Array.from({length:8},()=>request('tools/list',{})))
  await delay(40);child.stdout.resume()
  assert.ok((await burst).every(x=>Array.isArray(x.result?.tools)))
  assert.deepEqual((await request('ping',{})).result,{})
  samples.push({requests:next,clientRss:process.memoryUsage().rss,trackedReplyIds:received.size,pending:pending.size})
 }
 const finalDamage=await call('calculateBattleDamage',{request:{generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle'},defender:{species:'squirtle'},move:'tackle',field:{gameType:'Singles'}}})
 assert.equal(finalDamage.result.isError,false)
 assert.equal(JSON.parse(text(finalDamage.result)).conditionValidation.status,'VERIFIED')
 assert.equal(pending.size,0);assert.equal(duplicateReplies,0);assert.equal(invalidStdout,false);assert.equal(privatePayloadLogged,false)
 assert.ok(received.size<=4096)
 enduranceReport={status:'PASS',mode,protocol:'2024-11-05',scope:'OFFLINE_UPSTREAM_REAL_SDK_BOUNDED_ENDURANCE_NOT_LONG_DURATION_CERTIFICATION',requests:next,pingCycles:5000,slowConsumerCycles:5,durationMs:Math.round(performance.now()-started),samples,serverHeapMeasured:false}
 }
 }
}finally{
 child.stdin.end();let forcedTermination=false
 const timer=setTimeout(()=>{forcedTermination=true;child.kill()},3000)
 const exitCode=await exited;clearTimeout(timer);lines.close()
 if(forcedTermination)throw Error('MCP_FORCED_TERMINATION')
 if(enduranceReport){assert.equal(exitCode,0);console.log(JSON.stringify({...enduranceReport,naturalExit:true,forcedTermination:false}))}
}
