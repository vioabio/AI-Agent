import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {LocalMcpClient} from './mcp-local-client.mjs'
import {digest} from './evaluation.mjs'

export function verifyTimeoutRecovery(report) {
  assert.equal(report.scope,'PRODUCTION_STDIO_CLIENT_DEADLINE_AND_RECOVERY')
  assert.equal(report.records.length,5)
  assert.ok(report.tools.includes('calculateBattleDamage'))
  let previous=0
  for(const row of report.records) {
    assert.ok(Number.isSafeInteger(row.requestId)&&row.requestId>previous);previous=row.requestId
    assert.equal(row.expired,'MCP_REQUEST_TIMEOUT');assert.equal(row.timeoutMs,1)
    assert.deepEqual(row.tools,report.tools);assert.deepEqual(row.ping.result,{});assert.equal(row.ping.error,undefined)
    assert.ok(!row.recovered.error&&!row.recovered.result?.isError)
    const result=JSON.parse(row.recovered.result.content.map(item=>item.text||'').join(''))
    assert.equal(result.conditionValidation.status,'VERIFIED')
    assert.equal(result.range.minimum,13);assert.equal(result.range.maximum,16)
  }
  assert.equal(report.observation.invalidFrames,0);assert.equal(report.observation.duplicateResponses,0)
  assert.equal(report.observation.forcedTermination,false)
  return {status:'VERIFIED',deadlineCalls:5,successfulRecoveryCalls:5,qualityBenefit:'NOT_EVALUATED'}
}

async function main() {
  const [destination,configuration,seed]=process.argv.slice(2)
  if(!destination||!configuration||!seed)throw Error('Usage: mcp-timeout-recovery.mjs NEW_ARTIFACT_DIRECTORY CLIENT_CONFIG PUBLIC_CACHE_SEED')
  const artifacts=fs.realpathSync('artifacts'),output=path.resolve(destination),seedPath=fs.realpathSync(seed)
  assert.equal(path.dirname(output),artifacts);assert.equal(fs.existsSync(output),false)
  const seedRelative=path.relative(artifacts,seedPath)
  assert.ok(seedRelative&&!seedRelative.startsWith('..')&&!path.isAbsolute(seedRelative))
  const config=JSON.parse(fs.readFileSync(configuration,'utf8').replace(/^\uFEFF/,''))?.mcpServers?.['vio-pokemon']
  assert.equal(config?.args?.[2],'com.vio.vioaiagent.mcp.PokemonMcpServerMain')
  const classes=fs.realpathSync(config.args[1].split(path.delimiter)[0])
  const classesRelative=path.relative(artifacts,classes)
  assert.ok(classesRelative&&!classesRelative.startsWith('..')&&!path.isAbsolute(classesRelative))
  fs.mkdirSync(output)
  const cache=path.join(output,'public-cache');fs.cpSync(seedPath,cache,{recursive:true})
  const report={schemaVersion:'mcp-timeout-recovery-v1',scope:'PRODUCTION_STDIO_CLIENT_DEADLINE_AND_RECOVERY',
    status:'RUNNING',records:[],qualityBenefit:'NOT_EVALUATED',releaseDecision:'NOT_EVALUATED',
    sourceSha256:Object.fromEntries(['mcp-timeout-recovery.mjs','mcp-local-client.mjs','mcp-response-window.mjs']
      .map(file=>[file,digest(fs.readFileSync(new URL(file,import.meta.url)))])),
    implementationSha256:Object.fromEntries(['mcp/PokemonMcpServerMain','mcp/ReadOnlyMcpAdapter','mcp/McpStdioServer','mcp/McpWireControl','tools/governance/ToolExecutionGateway','pokemon/engine/LocalDamageEngine']
      .map(file=>[file,digest(fs.readFileSync(path.join(classes,'com/vio/vioaiagent',file+'.class')))]))}
  let client
  const request={request:{generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle'},defender:{species:'squirtle'},move:'tackle',field:{gameType:'Singles'}}}
  try {
    client=new LocalMcpClient({...config,env:{...config.env,VIO_MCP_CACHE_DIRECTORY:cache}})
    report.tools=await client.initialize();assert.ok(report.tools.includes('calculateBattleDamage'))
    for(let index=0;index<5;index++) {
      client.timeoutMs=1
      const pending=client.call('calculateBattleDamage',request),requestId=client.next
      client.timeoutMs=15000
      let expired
      try{await pending;throw Error('CLIENT_DEADLINE_NOT_OBSERVED')}catch(error){expired=error.message}
      assert.equal(expired,'MCP_REQUEST_TIMEOUT')
      // Repeated cancellation remains a notification and cannot consume a new request ID.
      client.send({jsonrpc:'2.0',method:'notifications/cancelled',params:{requestId}})
      const ping=await client.request('ping'),discovery=await client.request('tools/list')
      const recovered=await client.call('calculateBattleDamage',request)
      report.records.push({requestId,timeoutMs:1,expired,ping,tools:discovery.result.tools.map(tool=>tool.name).sort(),recovered})
    }
  }catch(error){report.status='FAIL';report.failure=error.message;process.exitCode=1}
  finally {
    if(client)report.observation=await client.close()
    if(report.status!=='FAIL')try{report.integrity=verifyTimeoutRecovery(report);report.status='PASS'}
    catch(error){report.status='FAIL';report.failure=error.code||error.name;process.exitCode=1}
    fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'})
    console.log(JSON.stringify({status:report.status,deadlineCalls:report.records.length,observation:report.observation,failure:report.failure}))
  }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main()
